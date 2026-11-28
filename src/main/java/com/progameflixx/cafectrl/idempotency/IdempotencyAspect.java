package com.progameflixx.cafectrl.idempotency;

import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.repository.UserRepository;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.WebUtils;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Enforces {@link Idempotent}. For a request with an Idempotency-Key:
 * <ol>
 *   <li>opens a transaction and claims the key (INSERT into a unique index);</li>
 *   <li>if the key is new: runs the action in that same transaction, stores the response, commits. If the action
 *       fails (exception or 4xx/5xx), everything rolls back, including the key, so a retry may run again;</li>
 *   <li>if the key exists: returns the stored response (header {@code Idempotent-Replay: true}); the action is NOT
 *       run, so nothing is charged, written to the ledger or audited twice. A key reused for a different request
 *       is refused with 422.</li>
 * </ol>
 * Runs inside the audit failure aspect (order 2), so failures are still audited and replays are not.
 */
@Aspect
@Component
@Order(2)
public class IdempotencyAspect {

    private static final Logger logger = LoggerFactory.getLogger(IdempotencyAspect.class);
    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAY_HEADER = "Idempotent-Replay";
    private static final Pattern VALID_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,100}$");

    private final IdempotencyStore store;
    private final UserRepository userRepository;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate tx;

    public IdempotencyAspect(IdempotencyStore store, UserRepository userRepository, JsonMapper jsonMapper,
                             PlatformTransactionManager txManager) {
        this.store = store;
        this.userRepository = userRepository;
        this.jsonMapper = jsonMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    @Around("@annotation(idem)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idem) throws Throwable {
        HttpServletRequest request = currentRequest();
        String key = request == null ? null : request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            if (idem.required()) {
                logger.warn("{} refused: no {} header", idem.value(), HEADER);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Missing " + HEADER + " header. Please reload the page and try again.");
            }
            return pjp.proceed();
        }
        key = key.trim();
        if (!VALID_KEY.matcher(key).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + HEADER + " header");
        }
        String scope = scope();
        String hash = requestHash(idem.value(), request, pjp);
        String finalKey = key;

        Outcome outcome;
        try {
            outcome = tx.execute(status -> {
                if (!store.claim(scope, finalKey, idem.value(), hash)) {
                    return Outcome.replay(store.find(scope, finalKey).orElse(null));
                }
                Object result;
                try {
                    result = pjp.proceed();
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                }
                int code = result instanceof ResponseEntity<?> re ? re.getStatusCode().value() : 200;
                if (code >= 400) {
                    status.setRollbackOnly(); // nothing is stored for a failed attempt; it may be retried
                    return Outcome.ran(result);
                }
                Object body = result instanceof ResponseEntity<?> re ? re.getBody() : result;
                store.complete(scope, finalKey, code, body == null ? null : jsonMapper.writeValueAsString(body));
                return Outcome.ran(result);
            });
        } catch (PessimisticLockingFailureException | QueryTimeoutException e) {
            // another request with this key is still running and holds it
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This action is still being processed. Please wait a moment.");
        }

        if (!outcome.isReplay()) return outcome.result();
        if (outcome.stored() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This action is still being processed. Please wait a moment.");
        }
        return replay(pjp, idem, outcome.stored(), hash);
    }

    /** Either the action ran now (result), or the key was already used (stored = what the first request saved). */
    private record Outcome(boolean isReplay, Object result, IdempotencyStore.Stored stored) {
        static Outcome ran(Object result) { return new Outcome(false, result, null); }
        static Outcome replay(IdempotencyStore.Stored stored) { return new Outcome(true, null, stored); }
    }

    private Object replay(ProceedingJoinPoint pjp, Idempotent idem, IdempotencyStore.Stored stored, String hash) throws Exception {
        if (!stored.action().equals(idem.value()) || !stored.requestHash().equals(hash)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "This request id was already used for a different action. Please reload and try again.");
        }
        if (!stored.completed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This action is still being processed. Please wait a moment.");
        }
        logger.info("{} replayed for key (already done); returning the original response", idem.value());
        Class<?> returnType = ((MethodSignature) pjp.getSignature()).getReturnType();
        if (ResponseEntity.class.isAssignableFrom(returnType)) {
            Object body = stored.body() == null ? null : jsonMapper.readTree(stored.body());
            return ResponseEntity.status(stored.status()).header(REPLAY_HEADER, "true")
                    .contentType(MediaType.APPLICATION_JSON).body(body);
        }
        currentResponseHeader();
        JavaType type = jsonMapper.getTypeFactory().constructType(((MethodSignature) pjp.getSignature()).getMethod().getGenericReturnType());
        return stored.body() == null ? null : jsonMapper.readValue(stored.body(), type);
    }

    // =====================================================================

    /** Keys are per user: another user can never receive someone else's stored response. */
    private String scope() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) return "anonymous";
        return userRepository.findByEmail(auth.getName()).map(User::getId).orElse("user:" + auth.getName());
    }

    /**
     * SHA-256 of action + method + path + query + the raw request body (cached by {@link IdempotencyBodyCachingFilter}).
     * Falls back to the controller's arguments when no cached body is available.
     */
    private String requestHash(String action, HttpServletRequest req, ProceedingJoinPoint pjp) {
        ContentCachingRequestWrapper cached = WebUtils.getNativeRequest(req, ContentCachingRequestWrapper.class);
        if (cached != null) {
            byte[] body = cached.getContentAsByteArray();
            String material = action + "|" + req.getMethod() + "|" + req.getRequestURI() + "|" + req.getQueryString() + "|"
                    + new String(body, StandardCharsets.UTF_8);
            return sha256(material);
        }
        List<Object> args = new ArrayList<>();
        for (Object a : pjp.getArgs()) {
            if (a instanceof Authentication || a instanceof ServletRequest || a instanceof ServletResponse) continue;
            args.add(a);
        }
        String material;
        try {
            material = action + "|" + req.getMethod() + "|" + req.getRequestURI() + "|" + req.getQueryString() + "|"
                    + jsonMapper.writeValueAsString(args);
        } catch (RuntimeException e) {
            material = action + "|" + req.getMethod() + "|" + req.getRequestURI() + "|" + req.getQueryString() + "|" + args;
        }
        return sha256(material);
    }

    private static String sha256(String material) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }

    private static void currentResponseHeader() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra && sra.getResponse() != null) {
            sra.getResponse().setHeader(REPLAY_HEADER, "true");
        }
    }
}
