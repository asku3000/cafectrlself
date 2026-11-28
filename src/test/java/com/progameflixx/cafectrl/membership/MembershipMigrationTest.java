package com.progameflixx.cafectrl.membership;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards that membership tables come from Flyway scripts, not from Hibernate's ddl-auto. */
@SpringBootTest
class MembershipMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayAppliedMembershipMigrations() {
        List<String> applied = jdbc.queryForList(
                "SELECT \"version\" FROM \"flyway_schema_history\" WHERE \"success\" = TRUE", String.class);
        assertTrue(applied.containsAll(List.of("1", "2", "3", "4", "5", "6")), "Migrations not applied, history: " + applied);
    }
}
