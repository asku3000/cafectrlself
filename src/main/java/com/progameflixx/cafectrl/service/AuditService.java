package com.progameflixx.cafectrl.service;

import com.progameflixx.cafectrl.entity.AuditLog;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.repository.AuditLogRepository;
import com.progameflixx.cafectrl.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AuditService {

    private static final Logger logger = LoggerFactory.getLogger(AuditService.class);

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private UserRepository userRepository;

    public void log(Authentication auth, String action, String target, Map<String, Object> meta) {
        User user = userRepository.findByEmail(auth.getName()).orElse(null);
        if (user == null) {
            logger.warn("Audit '{}' on target {} skipped: user {} not found", action, target, auth.getName());
            return;
        }

        AuditLog log = new AuditLog();
        log.setCafeId(user.getCafeId());
        log.setUserId(user.getId());
        log.setUserName(user.getName());
        log.setUserRole(user.getRole());
        log.setAction(action);
        log.setTarget(target);
        log.setMeta(meta);

        auditLogRepository.save(log);
        logger.debug("Audit recorded: action={} target={} by {}", action, target, user.getName());
    }
}
