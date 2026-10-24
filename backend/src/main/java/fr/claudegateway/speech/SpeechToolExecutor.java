package fr.claudegateway.speech;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.ProjectFileDeposit;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.teams.meeting.stt.TranscriptionProvider;
import fr.claudegateway.teams.meeting.stt.TranscriptionProvider.TimedWord;
import fr.claudegateway.teams.meeting.stt.TranscriptionProvider.WordTranscript;
import fr.claudegateway.videos.AudioBlock;
import fr.claudegateway.videos.VideoToolExecutor;

/**
 * <b>Exécute {@code voix_off}</b> (F-215 / SF-215-01) : synthèse via {@link SpeechProvider}, dépôt du mp3 dans
 * le projet du poste, horodatage des mots via {@link TranscriptionProvider} (mode mots), ou auditions publiées
 * par {@code audio_publier} (F-213).
 *
 * <p>La garde et l'accord sont posés <b>avant</b>, par la boucle. Toute erreur est un résultat d'outil en
 * erreur : le tour continue. <b>Isolation</b> : le {@link Workspace} est celui du tour, déjà vérifié possédé ;
 * le modèle ne donne qu'un chemin relatif, nettoyé ici.</p>
 */
@Component
public class SpeechToolExecutor {

    /** Mots rendus en ligne au plus ; au-delà, l'agent lit le fichier {@code .mots.json}. */
    static final int MAX_INLINE_WORDS = 800;
    static final int MAX_PATH_CHARS = 200;
    static final String AUDITIONS_DIR = "voix/auditions/";

    private final SpeechProvider provider;
    private final TranscriptionProvider transcription;
    private final ProjectFileDeposit deposit;
    private final SpeechPreferenceService preferences;
    private final SpeechUsage usage;
    private final SpeechProperties properties;
    private final VideoToolExecutor videoToolExecutor;
    private final ObjectMapper mapper;

    public SpeechToolExecutor(SpeechProvider provider, TranscriptionProvider transcription,
            ProjectFileDeposit deposit, SpeechPreferenceService preferences, SpeechUsage usage,
            SpeechProperties properties, VideoToolExecutor videoToolExecutor, ObjectMapper mapper) {
        this.provider = provider;
        this.transcription = transcription;
        this.deposit = deposit;
        this.preferences = preferences;
        this.usage = usage;
        this.properties = properties;
        this.videoToolExecutor = videoToolExecutor;
        this.mapper = mapper;
    }

    /**
     * @param userId    propriétaire du terminal (celui du tour)
     * @param workspace terminal du tour, déjà vérifié comme possédé
     */
    public Outcome execute(UUID userId, Workspace workspace, String callId, JsonNode input) {
        if (!properties.isActive()) {
            return Outcome.error("Voix off non configurée sur cette instance : poursuis sans voix, ou avec des "
                    + "sous-titres seuls.");
        }
        if (workspace == null || !workspace.isRunnerTarget()) {
            return Outcome.error("La voix off se dépose sur un poste connecté : ce sujet n'en a pas.");
        }
        String text = text(input, "texte");
        if (text.isEmpty()) {
            return Outcome.error("texte est requis : la narration à lire.");
        }
        String requestedVoice = text(input, "voix").toLowerCase(Locale.ROOT);
        if (!requestedVoice.isEmpty() && !properties.allows(requestedVoice)) {
            return Outcome.error("Voix « " + requestedVoice + " » inconnue. Voix permises : "
                    + String.join(", ", properties.voices()) + ".");
        }
        String requestedInstructions = text(input, "consignes");
        if (requestedInstructions.length() > properties.maxInstructionsChars()) {
            return Outcome.error("consignes trop longues (" + properties.maxInstructionsChars()
                    + " caractères au plus).");
        }
        SpeechPreferenceService.Resolved resolved = preferences.resolve(userId, requestedVoice, requestedInstructions);
        if (input != null && input.path("auditions").asBoolean(false)) {
            return auditions(userId, workspace, callId, input, text, resolved.instructions());
        }
        if (text.length() > properties.maxTextChars()) {
            return Outcome.error("texte trop long (" + text.length() + " caractères, " + properties.maxTextChars()
                    + " au plus) : découpe la narration par chapitre, un appel par chapitre.");
        }
        return narrate(userId, workspace, callId, input, text, resolved);
    }

    // ------------------------------------------------------------------ narration

    private Outcome narrate(UUID userId, Workspace workspace, String callId, JsonNode input, String text,
            SpeechPreferenceService.Resolved resolved) {
        byte[] mp3;
        try {
            mp3 = provider.synthesize(text, resolved.voice(), resolved.instructions()).bytes();
        } catch (SpeechProviderUnavailableException e) {
            return Outcome.error("Voix off non configurée sur cette instance : poursuis sans voix.");
        } catch (RuntimeException e) {
            return Outcome.error("Synthèse vocale en échec : réessaie dans un instant.");
        }
        String path = filePath(text(input, "fichier"));
        if (deposit.deposit(userId, workspace, callId, path, mp3, "audio/mpeg", SpeechToolCatalog.TOOL) == null) {
            usage.record(userId, workspace.getId(), workspace.getHostId(), text.length(), 0d);
            return Outcome.error("Voix générée, mais son dépôt sur le poste a échoué (poste hors ligne ou runner "
                    + "à mettre à jour) : réessaie.");
        }
        WordTranscript words = null;
        String wordsNote = "";
        try {
            words = transcription.transcribeWords(mp3, "audio/mpeg", null);
        } catch (UnsupportedOperationException | fr.claudegateway.teams.meeting.stt.TranscriptionProviderUnavailableException e) {
            wordsNote = "Horodatage des mots indisponible sur cette instance (transcription non configurée).";
        } catch (RuntimeException e) {
            wordsNote = "Horodatage des mots en échec (le mp3 est bien déposé) : relance voix_off pour le retenter.";
        }
        usage.record(userId, workspace.getId(), workspace.getHostId(), text.length(),
                words == null ? 0d : words.durationSeconds());

        StringBuilder out = new StringBuilder();
        out.append("Voix off déposée : « ").append(path).append(" » (voix ").append(resolved.voice());
        if (words == null) {
            out.append(").\n").append(wordsNote);
            return new Outcome(out.toString(), false, null);
        }
        out.append(", ").append(String.format(Locale.ROOT, "%.2f", words.durationSeconds())).append(" s).\n");
        String wordsPath = wordsPath(path);
        byte[] json = wordsJson(resolved.voice(), words);
        if (deposit.deposit(userId, workspace, callId + ".mots", wordsPath, json, "application/json",
                SpeechToolCatalog.TOOL) != null) {
            out.append("Mots horodatés : « ").append(wordsPath).append(" » ({mot, debut, fin} en secondes).\n");
        }
        out.append("Horodatage (début–fin  mot) :\n");
        List<TimedWord> list = words.words();
        int shown = Math.min(list.size(), MAX_INLINE_WORDS);
        for (int i = 0; i < shown; i++) {
            TimedWord w = list.get(i);
            out.append(String.format(Locale.ROOT, "%.2f–%.2f %s%n", w.start(), w.end(), w.word()));
        }
        if (list.size() > shown) {
            out.append("… ").append(list.size() - shown).append(" mots de plus : lis « ").append(wordsPath)
                    .append(" ».\n");
        }
        return new Outcome(out.toString().strip(), false, null);
    }

    // ------------------------------------------------------------------ auditions

    private Outcome auditions(UUID userId, Workspace workspace, String callId, JsonNode input, String text,
            String instructions) {
        if (text.length() > properties.maxAuditionChars()) {
            return Outcome.error("Pour des auditions, une seule phrase (" + properties.maxAuditionChars()
                    + " caractères au plus).");
        }
        List<String> voices = auditionVoices(input);
        if (voices == null) {
            return Outcome.error("voix_auditions : 2 à 4 voix distinctes parmi " + String.join(", ", properties.voices())
                    + ".");
        }
        ObjectNode publish = mapper.createObjectNode().put("titre", "Auditions de voix");
        ArrayNode tracks = publish.putArray("pistes");
        long characters = 0;
        int index = 1;
        for (String voice : voices) {
            byte[] mp3;
            try {
                mp3 = provider.synthesize(text, voice, instructions).bytes();
            } catch (SpeechProviderUnavailableException e) {
                return Outcome.error("Voix off non configurée sur cette instance.");
            } catch (RuntimeException e) {
                usage.record(userId, workspace.getId(), workspace.getHostId(), characters, 0d);
                return Outcome.error("Synthèse vocale en échec pour la voix « " + voice + " » : réessaie.");
            }
            characters += text.length();
            String path = AUDITIONS_DIR + voice + ".mp3";
            if (deposit.deposit(userId, workspace, callId + "." + index, path, mp3, "audio/mpeg",
                    SpeechToolCatalog.TOOL) == null) {
                usage.record(userId, workspace.getId(), workspace.getHostId(), characters, 0d);
                return Outcome.error("Dépôt des auditions sur le poste en échec : réessaie.");
            }
            String label = "Voix " + index + " — " + SpeechVoices.label(voice);
            tracks.addObject().put("path", path).put("libelle", label.length() > 80 ? label.substring(0, 80) : label);
            index++;
        }
        usage.record(userId, workspace.getId(), workspace.getHostId(), characters, 0d);

        VideoToolExecutor.AudioOutcome published = videoToolExecutor.publishAudio(userId, workspace,
                callId + ".auditions", publish);
        StringBuilder out = new StringBuilder("Auditions déposées sous « " + AUDITIONS_DIR + " » : ");
        for (int i = 0; i < voices.size(); i++) {
            out.append(i == 0 ? "" : ", ").append("Voix ").append(i + 1).append(" = ").append(voices.get(i));
        }
        out.append(".\n");
        if (published.error()) {
            out.append("Publication à l'écoute en échec : ").append(published.content())
                    .append(" Tu peux réessayer avec audio_publier sur ces fichiers.");
            return new Outcome(out.toString(), false, null);
        }
        out.append("Publiées dans le fil (lecteurs empilés). Demande maintenant à l'utilisateur laquelle il "
                + "préfère (question à choix), puis fais la voix off avec voix=<son choix>.");
        return new Outcome(out.toString(), false, published.block());
    }

    /** Les voix des auditions : celles demandées (2 à 4, distinctes, permises), sinon le jeu par défaut. */
    List<String> auditionVoices(JsonNode input) {
        JsonNode raw = input == null ? null : input.get("voix_auditions");
        if (raw == null || raw.isNull() || (raw.isArray() && raw.isEmpty())) {
            return SpeechVoices.DEFAULT_AUDITIONS.stream().filter(properties::allows).toList();
        }
        if (!raw.isArray()) {
            return null;
        }
        Set<String> voices = new LinkedHashSet<>();
        for (JsonNode node : raw) {
            String voice = node.asText("").strip().toLowerCase(Locale.ROOT);
            if (!properties.allows(voice)) {
                return null;
            }
            voices.add(voice);
        }
        return voices.size() < 2 || voices.size() > 4 ? null : List.copyOf(voices);
    }

    // ------------------------------------------------------------------ util

    /**
     * Le chemin relatif du mp3 : segments sûrs ({@code [A-Za-z0-9._-]}), sans {@code ..} ni racine, extension
     * {@code .mp3} imposée ; {@code voix/voix-off-<8 hex>.mp3} sinon.
     */
    static String filePath(String raw) {
        String fallback = "voix/voix-off-" + UUID.randomUUID().toString().substring(0, 8) + ".mp3";
        String value = raw == null ? "" : raw.strip().replace('\\', '/');
        List<String> segments = new ArrayList<>();
        for (String segment : value.split("/")) {
            String clean = segment.strip().replace(' ', '-').replaceAll("[^A-Za-z0-9._-]", "");
            if (clean.isEmpty() || clean.matches("\\.+")) {
                continue;
            }
            segments.add(clean);
        }
        if (segments.isEmpty()) {
            return fallback;
        }
        String last = segments.remove(segments.size() - 1);
        int dot = last.lastIndexOf('.');
        String stem = dot > 0 ? last.substring(0, dot) : last;
        if (stem.isBlank() || stem.matches("\\.+")) {
            return fallback;
        }
        segments.add(stem + ".mp3");
        String path = String.join("/", segments);
        return path.length() > MAX_PATH_CHARS ? fallback : path;
    }

    /** {@code a/b.mp3} → {@code a/b.mots.json}. */
    static String wordsPath(String mp3Path) {
        return mp3Path.substring(0, mp3Path.length() - ".mp3".length()) + ".mots.json";
    }

    private byte[] wordsJson(String voice, WordTranscript words) {
        ObjectNode root = mapper.createObjectNode();
        root.put("voix", voice);
        root.put("duree_s", round(words.durationSeconds()));
        ArrayNode list = root.putArray("mots");
        for (TimedWord w : words.words()) {
            list.addObject().put("mot", w.word()).put("debut", round(w.start())).put("fin", round(w.end()));
        }
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}".getBytes(StandardCharsets.UTF_8);
        }
    }

    private static double round(double seconds) {
        return Math.round(seconds * 1000d) / 1000d;
    }

    /** Titre lisible d'un appel, pour l'accord et le journal : le début du texte, jamais l'audio. */
    public static String auditTarget(JsonNode input) {
        String text = text(input, "texte");
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
    }

    private static String text(JsonNode input, String field) {
        if (input == null || !input.hasNonNull(field)) {
            return "";
        }
        return input.get(field).asText("").strip();
    }

    /**
     * L'issue d'un appel.
     *
     * @param auditions le bloc audio des auditions publiées, ou {@code null}
     */
    public record Outcome(String content, boolean error, AudioBlock auditions) {

        static Outcome error(String message) {
            return new Outcome(message, true, null);
        }
    }
}
