package com.progameflixx.cafectrl.audit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Records a FAILURE audit entry for every {@link Audited} controller method that throws or answers 4xx/5xx,
 * with the error message as the reason. Never changes the response.
 */
@Aspect
@Component
@org.springframework.core.annotation.Order(1) // outside the idempotency aspect: failures are audited, replays are not
public class AuditFailureAspect {

    private final AuditService audit;

    public AuditFailureAspect(AuditService audit) {
        this.audit = audit;
    }

    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        Object result;
        try {
            result = pjp.proceed();
        } catch (ResponseStatusException e) {
            fail(pjp, audited, e.getReason() != null ? e.getReason() : e.getStatusCode().toString());
            throw e;
        } catch (RuntimeException e) {
            fail(pjp, audited, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            throw e;
        }
        if (result instanceof ResponseEntity<?> re && re.getStatusCode().value() >= 400) {
            fail(pjp, audited, messageOf(re));
        }
        return result;
    }

    private void fail(ProceedingJoinPoint pjp, Audited a, String reason) {
        AuditRecord r = AuditRecord.of(a.action());
        if (!a.entityType().isEmpty()) r.entity(a.entityType(), entityId(pjp, a.entityIdParam()));
        audit.recordFailure(r, reason);
    }

    private static String entityId(ProceedingJoinPoint pjp, String param) {
        if (param.isEmpty()) return null;
        String[] names = ((MethodSignature) pjp.getSignature()).getParameterNames();
        Object[] args = pjp.getArgs();
        if (names == null) return null;
        for (int i = 0; i < names.length; i++) {
            if (param.equals(names[i]) && args[i] != null) return String.valueOf(args[i]);
        }
        return null;
    }

    /** "HTTP 400: <message>" from a ResponseEntity body that is a string or a {detail|message} map. */
    static String messageOf(ResponseEntity<?> re) {
        Object body = re.getBody();
        String msg = null;
        if (body instanceof String s) msg = s;
        else if (body instanceof Map<?, ?> m) {
            Object v = m.get("detail") != null ? m.get("detail") : m.get("message");
            if (v != null) msg = String.valueOf(v);
        }
        return "HTTP " + re.getStatusCode().value() + (msg != null && !msg.isBlank() ? ": " + msg : "");
    }
}
