package fr.claudegateway.speech.elevenlabs;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>ElevenLabs</b> (F-233, D-PO-1 b) : sous-traitant de l'écoute en flux (SF-233-04) et voix candidate (SF-233-06).
 *
 * <p>La clé vient <b>exclusivement</b> de l'environnement ({@code APP_ELEVENLABS_API_KEY}), jamais commitée, jamais
 * journalisée, jamais rendue au client. Sans clé, tout ce qui en dépend est éteint : aucun appel sortant.</p>
 *
 * @param apiKey                  la clé (en-tête {@code xi-api-key})
 * @param baseUrl                 l'API HTTP (défaut {@code https://api.elevenlabs.io})
 * @param wsBaseUrl               l'API WebSocket (défaut {@code wss://api.elevenlabs.io})
 * @param timeout                 délai des appels HTTP (défaut 30 s)
 * @param ttsModel                le modèle de voix (défaut {@code eleven_flash_v2_5}, SF-233-06)
 * @param ttsCostUsdPerThousandChars le tarif de la voix, en dollars pour 1 000 caractères (défaut 0,05)
 */
@ConfigurationProperties(prefix = "app.elevenlabs")
public record ElevenLabsProperties(String apiKey, String baseUrl, String wsBaseUrl, Duration timeout,
        String ttsModel, BigDecimal ttsCostUsdPerThousandChars) {

    public ElevenLabsProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.elevenlabs.io";
        }
        if (wsBaseUrl == null || wsBaseUrl.isBlank()) {
            wsBaseUrl = "wss://api.elevenlabs.io";
        }
        baseUrl = baseUrl.strip().replaceAll("/+$", "");
        wsBaseUrl = wsBaseUrl.strip().replaceAll("/+$", "");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(30);
        }
        if (ttsModel == null || ttsModel.isBlank()) {
            ttsModel = "eleven_flash_v2_5";
        }
        if (ttsCostUsdPerThousandChars == null || ttsCostUsdPerThousandChars.signum() < 0) {
            ttsCostUsdPerThousandChars = new BigDecimal("0.05");
        }
    }

    /** Vrai si une clé est fournie. */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
