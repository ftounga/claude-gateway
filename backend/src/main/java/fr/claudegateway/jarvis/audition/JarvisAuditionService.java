package fr.claudegateway.jarvis.audition;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.claudegateway.jarvis.JarvisVoiceService;
import fr.claudegateway.jarvis.JarvisVoiceService.VoiceRef;

/**
 * <b>L'audition à l'aveugle de la voix de Jarvis</b> (F-233 / SF-233-07, D7, D-PO-6).
 *
 * <p>Les mêmes phrases françaises (constantes, sans donnée client), dites par des voix anonymisées : la liste ne rend
 * que des lettres (A, B, C…), dans un ordre mélangé de façon stable par compte et par nombre de choix déjà faits —
 * rien ne révèle fournisseur ni voix avant le choix. Le choix fait de la voix celle de Jarvis <b>pour ce compte</b>,
 * et la révèle. Aucun clonage de voix réelle.</p>
 */
@Service
public class JarvisAuditionService {

    /** Les phrases de l'audition : fixes, françaises, sans donnée client. */
    public static final List<String> PHRASES = List.of(
            "Bonjour, Monsieur. Les tests du connecteur sont passés : je pousse la branche ?",
            "Je regarde les journaux du build, une seconde.",
            "Monsieur, la veille signale deux tickets bloqués depuis ce matin.",
            "C'est terminé. Le détail est à l'écran.");

    private final JarvisAuditionProperties properties;
    private final JarvisVoiceService voice;
    private final JarvisVoiceChoiceRepository choices;
    private final Clock clock;

    public JarvisAuditionService(JarvisAuditionProperties properties, JarvisVoiceService voice,
            JarvisVoiceChoiceRepository choices, Clock clock) {
        this.properties = properties;
        this.voice = voice;
        this.choices = choices;
        this.clock = clock;
    }

    /** Les candidats configurés (la voix décrite en dernier si son identifiant est fourni). */
    List<Candidate> candidates() {
        List<Candidate> list = new ArrayList<>();
        for (String raw : properties.candidates()) {
            String[] parts = raw.split(":", 3);
            if (parts.length >= 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                list.add(new Candidate(new VoiceRef(parts[0].strip().toLowerCase(Locale.ROOT), parts[1].strip()),
                        parts.length == 3 ? parts[2].strip() : parts[1].strip()));
            }
        }
        if (properties.designedVoiceId() != null) {
            list.add(new Candidate(new VoiceRef("elevenlabs", properties.designedVoiceId()), "Voix décrite (ElevenLabs)"));
        }
        return list;
    }

    /** Les candidats dans l'ordre de l'audition de ce compte (mélange stable, renouvelé à chaque choix). */
    List<Candidate> ordered(UUID userId) {
        List<Candidate> list = new ArrayList<>(candidates());
        Collections.shuffle(list, new Random(seed(userId, choices.countByUserId(userId))));
        return list;
    }

    static long seed(UUID userId, long round) {
        return userId.getMostSignificantBits() ^ (userId.getLeastSignificantBits() * 31) ^ (round * 0x9E3779B97F4A7C15L);
    }

    static String label(int index) {
        return String.valueOf((char) ('A' + index));
    }

    /** L'audition de ce compte : phrases, candidats anonymisés, voix choisie (révélée) ou {@code null}. */
    public Audition audition(UUID userId) {
        List<Candidate> ordered = ordered(userId);
        List<CandidateView> views = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            views.add(new CandidateView(label(i), voice.available(ordered.get(i).voice())));
        }
        Reveal chosen = choices.findFirstByUserIdOrderByChosenAtDesc(userId)
                .map(c -> new Reveal(c.getLabel(), c.getProvider(), c.getVoice(), nameOf(c.getProvider(), c.getVoice())))
                .orElse(null);
        return new Audition(PHRASES, views, chosen);
    }

    /**
     * L'échantillon (mp3) du candidat {@code label} pour la phrase {@code phrase}.
     *
     * @throws NoSuchElementException   libellé inconnu
     * @throws IllegalArgumentException index de phrase hors liste
     */
    public byte[] sample(UUID userId, String label, Integer phrase) {
        if (phrase == null || phrase < 0 || phrase >= PHRASES.size()) {
            throw new IllegalArgumentException("Phrase inconnue.");
        }
        Candidate candidate = byLabel(userId, label);
        return voice.synthesizeWith(userId, candidate.voice(), PHRASES.get(phrase));
    }

    /**
     * Choisit le candidat {@code label} : sa voix devient celle de Jarvis pour ce compte. Rend la voix, révélée.
     *
     * @throws NoSuchElementException libellé inconnu
     * @throws fr.claudegateway.speech.SpeechProviderUnavailableException voix indisponible
     */
    public Reveal choose(UUID userId, String label) {
        Candidate candidate = byLabel(userId, label);
        if (!voice.available(candidate.voice())) {
            throw new fr.claudegateway.speech.SpeechProviderUnavailableException("Cette voix n'est pas disponible.");
        }
        choices.save(JarvisVoiceChoice.builder().userId(userId).provider(candidate.voice().provider())
                .voice(candidate.voice().voice()).label(label).chosenAt(OffsetDateTime.now(clock)).build());
        return new Reveal(label, candidate.voice().provider(), candidate.voice().voice(), candidate.name());
    }

    /** Revient à la voix par défaut (configuration) pour ce compte. */
    public void reset(UUID userId) {
        choices.deleteByUser(userId);
    }

    private Candidate byLabel(UUID userId, String label) {
        List<Candidate> ordered = ordered(userId);
        for (int i = 0; i < ordered.size(); i++) {
            if (label(i).equals(label)) {
                return ordered.get(i);
            }
        }
        throw new NoSuchElementException("Voix inconnue.");
    }

    private String nameOf(String provider, String voiceId) {
        return candidates().stream()
                .filter(c -> c.voice().provider().equals(provider) && c.voice().voice().equals(voiceId))
                .map(Candidate::name).findFirst().orElse(voiceId + " (" + provider + ")");
    }

    /** Un candidat : sa voix et son nom (jamais montré avant le choix). */
    record Candidate(VoiceRef voice, String name) {
    }

    /** Ce que la liste montre d'un candidat : une lettre, et s'il peut parler. */
    public record CandidateView(String label, boolean available) {
    }

    /** La voix choisie, révélée. */
    public record Reveal(String label, String provider, String voice, String name) {
    }

    /** L'audition d'un compte. */
    public record Audition(List<String> phrases, List<CandidateView> candidates, Reveal chosen) {
    }
}
