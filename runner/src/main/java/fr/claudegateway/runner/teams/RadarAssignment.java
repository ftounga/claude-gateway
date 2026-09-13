package fr.claudegateway.runner.teams;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>Ce que la gateway demande</b> quand elle lance une synchro (F-100 / SF-100-02) : laquelle, pourquoi,
 * et depuis quand lire.
 *
 * @param syncId     identifiant de la synchro ouverte par la gateway (UUID, vérifié)
 * @param trigger    {@code SCHEDULED}, {@code MANUAL} ou {@code CATCH_UP}
 * @param firstSync  vrai pour la première synchro du poste (fenêtre bornée à 30 jours, la plus longue)
 * @param windowFrom plancher de lecture pour ce qui n'a pas encore de curseur
 * @param input      l'entrée complète, pour ce que le collecteur lit en plus (curseurs, fils ignorés)
 */
public record RadarAssignment(String syncId, String trigger, boolean firstSync, Instant windowFrom, JsonNode input) {

    /**
     * Lit l'entrée de {@code teams_radar_collect}.
     *
     * @throws IllegalArgumentException si l'identifiant de synchro ou la fenêtre sont illisibles
     */
    static RadarAssignment from(JsonNode input) {
        if (input == null || !input.isObject()) {
            throw new IllegalArgumentException("entrée absente");
        }
        String syncId = input.path("sync_id").asText("").strip();
        try {
            syncId = UUID.fromString(syncId).toString(); // jamais une chaîne libre dans une adresse
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("sync_id illisible");
        }
        Instant from;
        try {
            from = Instant.parse(input.path("window_from").asText(""));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("window_from illisible");
        }
        String trigger = input.path("trigger").asText("MANUAL").strip().toUpperCase(Locale.ROOT);
        if (!trigger.matches("SCHEDULED|MANUAL|CATCH_UP")) {
            trigger = "MANUAL";
        }
        return new RadarAssignment(syncId, trigger, input.path("first_sync").asBoolean(false), from, input);
    }
}
