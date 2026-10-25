package fr.claudegateway.push.dto;

/**
 * Réponse de {@code POST /api/push/test} (F-217 / SF-217-01) : le push est-il configuré, et combien
 * d'appareils <b>de ce compte</b> la notification de test vise. L'envoi lui-même est asynchrone.
 */
public record PushTestResponse(boolean configured, int devices) {
}
