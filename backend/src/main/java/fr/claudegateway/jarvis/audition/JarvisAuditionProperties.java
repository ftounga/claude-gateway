package fr.claudegateway.jarvis.audition;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>Les candidats de l'audition</b> (F-233 / SF-233-07), imposés par le PO le 2026-11-01.
 *
 * @param candidates      les voix, {@code fournisseur:voix:nom} ; défaut : ElevenLabs Daniel et George, OpenAI Onyx
 * @param designedVoiceId l'identifiant ElevenLabs de la voix décrite (Voice Design), ajoutée si fourni
 */
@ConfigurationProperties(prefix = "app.jarvis.audition")
public record JarvisAuditionProperties(List<String> candidates, String designedVoiceId) {

    /** Les voix imposées par le PO : Daniel et George (ElevenLabs, britanniques), Onyx (OpenAI) en référence. */
    public static final List<String> DEFAULT_CANDIDATES = List.of(
            "elevenlabs:onwK4e9ZLuTAKqWW03F9:Daniel (ElevenLabs)",
            "elevenlabs:JBFqnCBsd6RMkjVDRZzb:George (ElevenLabs)",
            "openai:onyx:Onyx (OpenAI)");

    public JarvisAuditionProperties {
        candidates = candidates == null || candidates.isEmpty() ? DEFAULT_CANDIDATES
                : candidates.stream().filter(c -> c != null && !c.isBlank()).map(String::strip).toList();
        designedVoiceId = designedVoiceId == null || designedVoiceId.isBlank() ? null : designedVoiceId.strip();
    }
}
