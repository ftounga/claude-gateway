package fr.claudegateway.jarvis.stt;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>La retranscription de l'énoncé complet</b> (F-239 / SF-239-04, D1, D2).
 *
 * @param provider        le fournisseur ({@code elevenlabs}, ou {@code none} pour éteindre : l'écran garde le flux)
 * @param model           le modèle batch (défaut {@code scribe_v2})
 * @param language        la langue attendue (défaut {@code fr})
 * @param providerTimeout le délai d'appel au fournisseur (défaut 6 s ; l'écran, lui, n'attend que 2,5 s)
 * @param maxVocabulary   le nombre maximal de termes du vocabulaire (défaut 100 : au-delà, Scribe facture 20 s minimum)
 * @param costUsdPerHour  tarif en dollars par heure d'audio, mots-clés compris (défaut 0,27 = 0,22 + 0,05)
 */
@ConfigurationProperties(prefix = "app.jarvis.utterance")
public record JarvisUtteranceProperties(
        String provider,
        String model,
        String language,
        Duration providerTimeout,
        Integer maxVocabulary,
        BigDecimal costUsdPerHour) {

    public JarvisUtteranceProperties {
        provider = provider == null || provider.isBlank() ? "elevenlabs" : provider.strip().toLowerCase(Locale.ROOT);
        model = model == null || model.isBlank() ? "scribe_v2" : model.strip();
        language = language == null || language.isBlank() ? "fr" : language.strip();
        if (providerTimeout == null || providerTimeout.isNegative() || providerTimeout.isZero()) {
            providerTimeout = Duration.ofSeconds(6);
        }
        if (maxVocabulary == null || maxVocabulary < 0) {
            maxVocabulary = 100;
        }
        maxVocabulary = Math.min(maxVocabulary, 1_000);
        if (costUsdPerHour == null || costUsdPerHour.signum() < 0) {
            costUsdPerHour = new BigDecimal("0.27");
        }
    }

    /** Vrai si un fournisseur est désigné (pas {@code none}). */
    public boolean enabled() {
        return !"none".equals(provider);
    }
}
