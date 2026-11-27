package com.progameflixx.cafectrl.service;

import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.logging.LogContext;
import com.progameflixx.cafectrl.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves the logged-in user (and their cafe) the same way the existing controllers do,
 * and enforces operator permissions server-side for the membership endpoints.
 */
@Service
public class CurrentUserService {

    public static final String PERM_MEMBERSHIPS = "memberships";

    private final UserRepository userRepository;

    public CurrentUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User require(Authentication auth) {
        User user = userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
        LogContext.putCafeId(user.getCafeId());
        return user;
    }

    public static boolean isAdmin(User user) {
        return "CAFE_ADMIN".equalsIgnoreCase(user.getRole());
    }

    /** Admins always pass; operators need the given permission in their permission list. */
    public static void requirePermission(User user, String permission) {
        if (isAdmin(user)) return;
        if (user.getPermissions() == null || !user.getPermissions().contains(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You don't have the '" + permission + "' permission");
        }
    }
}
