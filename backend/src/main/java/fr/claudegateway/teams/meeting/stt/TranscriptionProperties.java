package fr.claudegateway.teams.meeting.stt;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration du <b>service de transcription (STT)</b> relayé (F-128 / SF-128-04).
 *
 * <p><b>Éteint par défaut (DRAPEAU FORT).</b> L'audio de réunion sort du poste vers ce service —
 * sensible en banque. Tant que {@link #baseUrl} <b>et</b> {@link #apiKey} ne sont pas fournis par
 * l'environnement (jamais commités, jamais journalisés), {@link #isConfigured()} est faux et
 * <b>aucun octet ne part</b> : le déclenchement répond « STT non configuré ». C'est une décision
 * explicite du PO d'allumer le tuyau (opt-in par configuration).</p>
 */
@ConfigurationProperties(prefix = "app.stt")
public record TranscriptionProperties(
        String baseUrl,
        String apiKey,
        String model,
        String language,
        Duration timeout,
        Long maxAudioBytes) {

    /** Limite de taille par défaut d'un audio envoyé au STT (25 Mo — limite usuelle Whisper). */
    public static final long DEFAULT_MAX_AUDIO_BYTES = 25L * 1024 * 1024;

    public TranscriptionProperties {
        if (model == null || model.isBlank()) {
            model = "whisper-1";
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(120);
        }
        if (maxAudioBytes == null || maxAudioBytes <= 0) {
            maxAudioBytes = DEFAULT_MAX_AUDIO_BYTES;
        }
    }

    /**
     * Vrai si le service STT est réellement appelable : une base URL <b>et</b> une clé sont fournies.
     * Faux par défaut ⇒ transcription éteinte, aucune donnée ne sort.
     */
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }
}
