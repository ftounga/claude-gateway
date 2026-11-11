package fr.claudegateway.jarvis.voicestream;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>La boucle vocale en flux</b> (F-257 / SF-257-02, ADR-021).
 *
 * @param enabled   coupe-circuit serveur {@code APP_JARVIS_VOICE_STREAM_ENABLED} (défaut {@code false} : aucun ticket
 *                  n'est émis, l'écran garde l'ancienne boucle)
 * @param publicUrl l'adresse WebSocket du service {@code jarvis-voice} vue de l'écran (défaut {@code /voice/ws})
 * @param ticketTtl durée de vie d'un ticket de session vocale (défaut 30 min, bornée à 2 h)
 */
@ConfigurationProperties(prefix = "app.jarvis.voice-stream")
public record JarvisVoiceStreamProperties(Boolean enabled, String publicUrl, Duration ticketTtl) {

    /** Plafond de la durée de vie d'un ticket. */
    public static final Duration MAX_TTL = Duration.ofHours(2);

    public JarvisVoiceStreamProperties {
        enabled = Boolean.TRUE.equals(enabled);
        publicUrl = publicUrl == null || publicUrl.isBlank() ? "/voice/ws" : publicUrl.strip();
        if (ticketTtl == null || ticketTtl.isNegative() || ticketTtl.isZero()) {
            ticketTtl = Duration.ofMinutes(30);
        }
        if (ticketTtl.compareTo(MAX_TTL) > 0) {
            ticketTtl = MAX_TTL;
        }
    }

    /** Vrai si le coupe-circuit est ouvert. */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
