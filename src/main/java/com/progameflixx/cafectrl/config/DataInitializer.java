package com.progameflixx.cafectrl.config;

import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${app.super-admin.email:superadmin@gamebiller.com}")
    private String adminEmail;

    @Value("${app.super-admin.password:SuperAdmin@123}")
    private String adminPassword;

    @Override
    public void run(String... args) {
        if (userRepository.findByEmail(adminEmail).isEmpty()) {
            User admin = new User();
            admin.setEmail(adminEmail);
            admin.setPassword(passwordEncoder.encode(adminPassword));
            admin.setName("Super Admin");
            admin.setRole("SUPER_ADMIN");
            admin.setActive(true);
            admin.setCreatedAt(Instant.now());

            userRepository.save(admin);
            logger.info("Super Admin seeded: {}", adminEmail);
        } else {
            logger.info("Super Admin already present: {}", adminEmail);
        }
    }
}