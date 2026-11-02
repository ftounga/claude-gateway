package fr.claudegateway.jarvis.stt;

import java.math.BigDecimal;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>L'écoute en flux de Jarvis</b> (F-233 / SF-233-04, D5, D6, D13).
 *
 * @param provider          le fournisseur ({@code elevenlabs}, ou {@code none} pour éteindre)
 * @param model             le modèle de transcription en flux (défaut {@code scribe_v2_realtime})
 * @param language          langue attendue (défaut {@code fr})
 * @param vadSilenceSeconds silence de fin de tour côté fournisseur, en secondes (défaut 1,2 — F-239 / SF-239-10)
 * @param maxSessionSeconds durée maximale comptée d'une session (défaut 120)
 * @param costUsdPerMinute  tarif en dollars par minute de session (défaut 0,0065)
 */
@ConfigurationProperties(prefix = "app.jarvis.stt-stream")
public record JarvisSttStreamProperties(
        String provider,
        String model,
        String language,
        Double vadSilenceSeconds,
        Integer maxSessionSeconds,
        BigDecimal costUsdPerMinute) {

    public JarvisSttStreamProperties {
        provider = provider == null || provider.isBlank() ? "elevenlabs" : provider.strip().toLowerCase(Locale.ROOT);
        model = model == null || model.isBlank() ? "scribe_v2_realtime" : model.strip();
        language = language == null || language.isBlank() ? "fr" : language.strip();
        if (vadSilenceSeconds == null || vadSilenceSeconds <= 0) {
            vadSilenceSeconds = 1.2; // F-239 / SF-239-10 : 0,7 s coupait le PO quand il réfléchit
        }
        if (maxSessionSeconds == null || maxSessionSeconds <= 0) {
            maxSessionSeconds = 120;
        }
        if (costUsdPerMinute == null || costUsdPerMinute.signum() < 0) {
            costUsdPerMinute = new BigDecimal("0.0065");
        }
    }

    /** Vrai si un fournisseur est désigné (pas {@code none}). */
    public boolean enabled() {
        return !"none".equals(provider);
    }
}
