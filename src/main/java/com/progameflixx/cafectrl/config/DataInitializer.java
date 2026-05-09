package com.progameflixx.cafectrl.config;

import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class DataInitializer implements CommandLineRunner {

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
            System.out.println(">>> Super Admin seeded: " + adminEmail);
        }
    }
}