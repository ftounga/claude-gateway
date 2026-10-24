package fr.claudegateway.speech;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration de la <b>voix off</b> (F-215 / SF-215-01) : fournisseur de synthèse vocale relayé,
 * sur le modèle des images (F-142) et du STT (F-128).
 *
 * <p><b>Aucun nouveau secret</b> : la clé est celle d'OpenAI déjà fournie à la dictée / au STT
 * ({@code APP_STT_API_KEY}) ; {@code APP_SPEECH_API_KEY} ne sert qu'à la remplacer si besoin. La clé
 * vient exclusivement de l'environnement, jamais commitée ni journalisée.</p>
 *
 * <p><b>Coupe-circuit</b> : {@code enabled=false} ({@code APP_ATELIER_SPEECH_ENABLED}) éteint l'outil et
 * les routes, sans redéploiement de code.</p>
 */
@ConfigurationProperties(prefix = "app.speech")
public record SpeechProperties(
        Boolean enabled,
        String baseUrl,
        String apiKey,
        String model,
        Duration timeout,
        Integer maxTextChars,
        Integer maxAuditionChars,
        Integer maxInstructionsChars,
        Integer maxPerTurn,
        List<String> voices,
        BigDecimal costUsdPerThousandChars,
        BigDecimal transcriptionCostUsdPerMinute) {

    /** Les voix du fournisseur (OpenAI {@code gpt-4o-mini-tts}), liste blanche par défaut. */
    public static final List<String> DEFAULT_VOICES = List.of("alloy", "ash", "ballad", "coral", "echo",
            "fable", "nova", "onyx", "sage", "shimmer", "verse");

    public SpeechProperties {
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com/v1";
        }
        if (model == null || model.isBlank()) {
            model = "gpt-4o-mini-tts";
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(120);
        }
        if (maxTextChars == null || maxTextChars <= 0) {
            maxTextChars = 4_000;
        }
        if (maxAuditionChars == null || maxAuditionChars <= 0) {
            maxAuditionChars = 300;
        }
        if (maxInstructionsChars == null || maxInstructionsChars <= 0) {
            maxInstructionsChars = 1_000;
        }
        if (maxPerTurn == null || maxPerTurn <= 0) {
            maxPerTurn = 20;
        }
        voices = voices == null || voices.isEmpty() ? DEFAULT_VOICES
                : voices.stream().filter(v -> v != null && !v.isBlank())
                        .map(v -> v.strip().toLowerCase(Locale.ROOT)).distinct().toList();
        if (costUsdPerThousandChars == null || costUsdPerThousandChars.signum() < 0) {
            costUsdPerThousandChars = new BigDecimal("0.015");
        }
        if (transcriptionCostUsdPerMinute == null || transcriptionCostUsdPerMinute.signum() < 0) {
            transcriptionCostUsdPerMinute = new BigDecimal("0.006");
        }
    }

    /** Vrai si le fournisseur est appelable : une base URL et une clé sont fournies. */
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    /** Vrai si la voix off est ouverte : allumée (coupe-circuit) <b>et</b> configurée. */
    public boolean isActive() {
        return Boolean.TRUE.equals(enabled) && isConfigured();
    }

    /** Vrai si cette voix est dans la liste blanche. */
    public boolean allows(String voice) {
        return voice != null && voices.contains(voice.strip().toLowerCase(Locale.ROOT));
    }
}
