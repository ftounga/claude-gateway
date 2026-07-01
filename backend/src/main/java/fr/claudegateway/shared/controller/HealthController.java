package fr.claudegateway.shared.controller;

import java.time.OffsetDateTime;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de fumée. Servi sous le context-path {@code /api} → accessible à {@code GET /api/hello}.
 * Utilisé par la page d'accueil du frontend pour afficher le statut du backend.
 */
@RestController
public class HealthController {

    @GetMapping("/hello")
    public Map<String, Object> hello() {
        return Map.of(
                "service", "claude-gateway-backend",
                "status", "ok",
                "timestamp", OffsetDateTime.now().toString()
        );
    }
}
