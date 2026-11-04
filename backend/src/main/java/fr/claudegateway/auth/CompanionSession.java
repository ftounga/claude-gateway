package fr.claudegateway.auth;

import java.util.UUID;

/**
 * F-232 / SF-232-05 — les <b>détails</b> d'une authentification ouverte par une session « jarvis-compagnon » : le
 * compagnon Android qui l'a émise et l'état verrouillé du téléphone à l'émission (SF-232-06). Posés par
 * {@link JwtAuthenticationFilter} ; lus par {@link CurrentUser#companionSession()}. Absents d'une session ordinaire.
 */
public record CompanionSession(UUID companionId, boolean locked) {
}
