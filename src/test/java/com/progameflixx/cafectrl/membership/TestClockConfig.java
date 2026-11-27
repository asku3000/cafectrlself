package com.progameflixx.cafectrl.membership;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.*;

/** Replaces the business clock with one tests can set, so validity / expiry can be exercised. */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock();
    }

    public static class MutableClock extends Clock {
        private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
        private volatile Instant instant = Instant.now();

        public void setDate(LocalDate date) {
            instant = date.atTime(12, 0).atZone(ZONE).toInstant();
        }

        public void plusDays(long days) {
            instant = instant.plus(Duration.ofDays(days));
        }

        @Override public ZoneId getZone() { return ZONE; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
