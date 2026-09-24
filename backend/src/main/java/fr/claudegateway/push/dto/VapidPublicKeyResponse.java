package fr.claudegateway.push.dto;

/**
 * La clé <b>publique</b> VAPID servie au frontend (F-153 / SF-153-02) — jamais la privée. {@code key}
 * vaut {@code null} quand le Web Push n'est pas configuré (le frontend n'offre alors pas l'activation).
 *
 * @param key clé publique VAPID (base64url), ou {@code null} si non configuré
 */
public record VapidPublicKeyResponse(String key) {
}
