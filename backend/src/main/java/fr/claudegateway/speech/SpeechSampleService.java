package fr.claudegateway.speech;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * <b>L'échantillon fixe d'une voix</b> (F-215 / SF-215-01, bouton ▶ des Paramètres).
 *
 * <p>Même phrase et mêmes consignes (D2) pour toutes les voix : l'échantillon ne dépend de personne, il est
 * donc généré <b>une fois par voix</b> puis servi depuis la mémoire — le coût est borné à la liste des voix.
 * La génération est inscrite au journal d'usage du compte qui l'a déclenchée.</p>
 */
@Service
public class SpeechSampleService {

    private final SpeechProvider provider;
    private final SpeechProperties properties;
    private final SpeechUsage usage;
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    public SpeechSampleService(SpeechProvider provider, SpeechProperties properties, SpeechUsage usage) {
        this.provider = provider;
        this.properties = properties;
        this.usage = usage;
    }

    /**
     * L'échantillon mp3 de cette voix.
     *
     * @throws IllegalArgumentException           voix hors liste
     * @throws SpeechProviderUnavailableException voix off éteinte ou non configurée
     * @throws SpeechProviderException            fournisseur en échec
     */
    public byte[] sample(UUID userId, String voice) {
        if (!properties.allows(voice)) {
            throw new IllegalArgumentException("Voix inconnue.");
        }
        if (!properties.isActive()) {
            throw new SpeechProviderUnavailableException("Voix off non configurée.");
        }
        String key = voice.strip().toLowerCase(Locale.ROOT);
        byte[] cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        byte[] bytes = provider.synthesize(SpeechVoices.SAMPLE_TEXT, key, SpeechVoices.DEFAULT_INSTRUCTIONS).bytes();
        cache.put(key, bytes);
        usage.record(userId, null, null, SpeechVoices.SAMPLE_TEXT.length(), 0d);
        return bytes;
    }
}
