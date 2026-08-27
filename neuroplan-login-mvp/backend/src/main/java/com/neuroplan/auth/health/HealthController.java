package com.neuroplan.auth.health;

import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {
    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "ok", "service", "neuroplan-auth-api");
    }

    @GetMapping("/db-health")
    Map<String, String> dbHealth() {
        Integer value = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        return Map.of("status", value != null && value == 1 ? "ok" : "error", "database", "mariadb-via-maxscale");
    }
}
