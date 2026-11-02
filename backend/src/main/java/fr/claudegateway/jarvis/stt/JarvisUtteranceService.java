package fr.claudegateway.jarvis.stt;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.jarvis.JarvisVoiceBudgetException;
import fr.claudegateway.jarvis.JarvisVoiceService;
import fr.claudegateway.stt.utterance.UtteranceTranscriptionProvider;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderUnavailableException;

/**
 * <b>La retranscription de l'énoncé de Jarvis</b> (F-239 / SF-239-04, D1) : l'audio d'un énoncé complet, transcrit
 * par le fournisseur retenu avec le vocabulaire du compte ; la durée est comptée en € sous le plafond voix. <b>Rien
 * n'est conservé</b> (régime de la dictée F-145 / D8 F-231) : l'audio vit le temps de l'appel.
 */
@Service
public class JarvisUtteranceService {

    /** Débit d'un PCM 16 bits mono à 16 kHz : sert à estimer la durée d'un WAV. */
    static final int PCM16_BYTES_PER_SECOND = 32_000;

    private final List<UtteranceTranscriptionProvider> providers;
    private final JarvisUtteranceProperties properties;
    private final JarvisVocabulary vocabulary;
    private final JarvisVoiceService voice;
    private final JarvisUsageService usage;

    public JarvisUtteranceService(List<UtteranceTranscriptionProvider> providers, JarvisUtteranceProperties properties,
            JarvisVocabulary vocabulary, JarvisVoiceService voice, JarvisUsageService usage) {
        this.providers = providers;
        this.properties = properties;
        this.vocabulary = vocabulary;
        this.voice = voice;
        this.usage = usage;
    }

    /** Ce que l'écran reçoit : le texte, le fournisseur, la durée de l'appel. */
    public record Result(String text, String provider, long ms) {
    }

    /**
     * Retranscrit l'énoncé du compte.
     *
     * @param seconds la durée déclarée par l'écran, ou {@code null} (estimée depuis la taille sinon)
     * @throws TranscriptionProviderUnavailableException éteinte ou non configurée (aucun appel)
     * @throws JarvisVoiceBudgetException                plafond du mois atteint (aucun appel)
     */
    public Result transcribe(UUID userId, byte[] audio, String contentType, Double seconds) {
        UtteranceTranscriptionProvider provider = provider();
        if (voice.status(userId).budgetReached()) {
            throw new JarvisVoiceBudgetException();
        }
        long started = System.nanoTime();
        String text = provider.transcribe(audio, contentType, properties.language(), vocabulary.of(userId));
        long ms = (System.nanoTime() - started) / 1_000_000L;
        long billed = billedSeconds(audio.length, contentType, seconds);
        usage.addStream(userId, billed, costUsd(billed));
        return new Result(text == null ? "" : text.strip(), provider.name() + "-batch", ms);
    }

    /** Les secondes comptées : déclarées (bornées à 120 s), sinon estimées (WAV PCM 16 kHz), au moins 1. */
    static long billedSeconds(int bytes, String contentType, Double declared) {
        if (declared != null && declared > 0) {
            return Math.max(1L, Math.min(120L, Math.round(Math.ceil(declared))));
        }
        boolean wav = contentType == null || contentType.contains("wav");
        long estimate = wav ? Math.round(Math.ceil(bytes / (double) PCM16_BYTES_PER_SECOND))
                : Math.round(Math.ceil(bytes / 4_000.0)); // opus ≈ 32 kb/s
        return Math.max(1L, Math.min(120L, estimate));
    }

    BigDecimal costUsd(long seconds) {
        return properties.costUsdPerHour().multiply(BigDecimal.valueOf(seconds))
                .divide(BigDecimal.valueOf(3_600), 6, RoundingMode.HALF_UP);
    }

    private UtteranceTranscriptionProvider provider() {
        if (!properties.enabled()) {
            throw new TranscriptionProviderUnavailableException("La retranscription est éteinte.");
        }
        return providers.stream()
                .filter(p -> properties.provider().equals(p.name()) && p.isConfigured())
                .findFirst()
                .orElseThrow(() -> new TranscriptionProviderUnavailableException(
                        "La retranscription n'est pas configurée."));
    }
}
