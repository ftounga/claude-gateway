package fr.claudegateway.speech;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>La voix par défaut d'un compte</b> (F-215 / SF-215-01, amendement PO point 2).
 *
 * <p>Isolation : toujours lue et écrite par le {@code user_id} du contexte de sécurité ou du tour, jamais
 * par un identifiant venu du client. Absente = réglages D2.</p>
 */
@Service
public class SpeechPreferenceService {

    private final SpeechPreferenceRepository repository;
    private final SpeechProperties properties;

    public SpeechPreferenceService(SpeechPreferenceRepository repository, SpeechProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** La préférence enregistrée, si elle existe. */
    @Transactional(readOnly = true)
    public Optional<SpeechPreference> find(UUID userId) {
        return userId == null ? Optional.empty() : repository.findById(userId);
    }

    /**
     * La voix et les consignes à utiliser : celles de l'appel, sinon la préférence du compte, sinon D2.
     * Une préférence devenue hors liste (liste blanche réduite) retombe sur D2.
     */
    @Transactional(readOnly = true)
    public Resolved resolve(UUID userId, String requestedVoice, String requestedInstructions) {
        Optional<SpeechPreference> saved = find(userId);
        String voice = blankToNull(requestedVoice);
        if (voice == null) {
            voice = saved.map(SpeechPreference::getVoice).filter(properties::allows).orElse(SpeechVoices.DEFAULT_VOICE);
        }
        String instructions = blankToNull(requestedInstructions);
        if (instructions == null) {
            instructions = saved.map(SpeechPreference::getInstructions).map(SpeechPreferenceService::blankToNull)
                    .orElse(SpeechVoices.DEFAULT_INSTRUCTIONS);
        }
        return new Resolved(voice.toLowerCase(Locale.ROOT), instructions);
    }

    /**
     * Enregistre la voix par défaut.
     *
     * @throws IllegalArgumentException voix hors liste, consignes trop longues
     */
    @Transactional
    public SpeechPreference save(UUID userId, String voice, String instructions) {
        String v = blankToNull(voice);
        if (v == null || !properties.allows(v)) {
            throw new IllegalArgumentException("Voix inconnue. Voix permises : " + String.join(", ", properties.voices()));
        }
        String i = blankToNull(instructions);
        if (i != null && i.length() > properties.maxInstructionsChars()) {
            throw new IllegalArgumentException("Consignes trop longues (" + properties.maxInstructionsChars()
                    + " caractères au plus).");
        }
        SpeechPreference preference = repository.findById(userId)
                .orElseGet(() -> SpeechPreference.builder().userId(userId).build());
        preference.setVoice(v.toLowerCase(Locale.ROOT));
        preference.setInstructions(i);
        preference.setUpdatedAt(OffsetDateTime.now());
        return repository.save(preference);
    }

    /** Revient aux réglages D2. */
    @Transactional
    public void reset(UUID userId) {
        if (userId != null && repository.existsById(userId)) {
            repository.deleteById(userId);
        }
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Une voix et ses consignes, prêtes pour le fournisseur. */
    public record Resolved(String voice, String instructions) {
    }
}
