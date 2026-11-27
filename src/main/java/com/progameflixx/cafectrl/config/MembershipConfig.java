package com.progameflixx.cafectrl.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Membership infrastructure: the business clock (IST, replaceable in tests so expiry can be exercised)
 * and scheduling for the nightly expiry job.
 */
@Configuration
@EnableScheduling
public class MembershipConfig {

    public static final ZoneId CAFE_ZONE = ZoneId.of("Asia/Kolkata");

    /** Tests override this with a @Primary adjustable clock. */
    @Bean
    public Clock membershipClock() {
        return Clock.system(CAFE_ZONE);
    }
}
