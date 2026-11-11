package fr.claudegateway.jarvis.timing;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.JarvisReplies;
import fr.claudegateway.jarvis.JarvisSpokenWords;

/**
 * <b>Le chronomètre du tour vocal</b> (F-233 / SF-233-01, D2) : range une mesure sans contenu par tour vocal et rend,
 * à l'administrateur, p50/p90 par étape sur <b>ses</b> tours.
 *
 * <p>Les marques sont des millisecondes depuis la fin de parole détectée (t0). Rien de ce qui est dit ou lu n'est
 * reçu ni rangé : seulement des durées, des noms de fournisseurs (liste fermée) et des compteurs.</p>
 */
@Service
public class JarvisLatencyService {

    /** Au-delà, une marque est refusée (10 minutes). */
    public static final int MAX_MARK_MS = 600_000;
    /** Au-delà, un compteur est refusé. */
    public static final int MAX_COUNTER = 1_000;
    /** Durée de conservation des mesures. */
    public static final int RETENTION_DAYS = 90;
    static final int MAX_DAYS = 90;

    private static final Pattern PROVIDER = Pattern.compile("^[a-z0-9-]{1,32}$");
    /** SF-233-11 — {@code CONV_*} : le tour a été pris en conversation mains libres. */
    private static final List<String> LISTEN_MODES = List.of("BATCH", "STREAM", "CONV_BATCH", "CONV_STREAM");

    /** Les étapes mesurées, dans l'ordre du tour. */
    static final Map<String, Function<JarvisTurnTiming, Integer>> STEPS = steps();

    private final JarvisTurnTimingRepository repository;
    private final WorkspaceRepository workspaces;
    private final AtelierMessageRepository messages;
    private final Clock clock;

    public JarvisLatencyService(JarvisTurnTimingRepository repository, WorkspaceRepository workspaces,
            AtelierMessageRepository messages, Clock clock) {
        this.repository = repository;
        this.workspaces = workspaces;
        this.messages = messages;
        this.clock = clock;
    }

    private static Map<String, Function<JarvisTurnTiming, Integer>> steps() {
        Map<String, Function<JarvisTurnTiming, Integer>> steps = new LinkedHashMap<>();
        steps.put("speech", JarvisTurnTiming::getSpeechMs);
        steps.put("transcript", JarvisTurnTiming::getTranscriptMs);
        steps.put("reaction", JarvisTurnTiming::getReactionMs);
        steps.put("sent", JarvisTurnTiming::getSentMs);
        steps.put("firstToken", JarvisTurnTiming::getFirstTokenMs);
        steps.put("firstOralSentence", JarvisTurnTiming::getFirstOralSentenceMs);
        steps.put("firstAudioByte", JarvisTurnTiming::getFirstAudioByteMs);
        steps.put("firstSound", JarvisTurnTiming::getFirstSoundMs);
        steps.put("done", JarvisTurnTiming::getDoneMs);
        return java.util.Collections.unmodifiableMap(steps);
    }

    /**
     * Range la mesure d'un tour vocal.
     *
     * @throws IllegalArgumentException une marque, un compteur ou un nom hors bornes
     */
    public void record(UUID userId, TimingReport report) {
        if (report == null) {
            throw new IllegalArgumentException("Mesure vide.");
        }
        String mode = report.listenMode() == null ? "BATCH" : report.listenMode().strip().toUpperCase();
        if (!LISTEN_MODES.contains(mode)) {
            throw new IllegalArgumentException("Mode d'écoute inconnu.");
        }
        UUID workspaceId = report.workspaceId() == null ? null
                : workspaces.findByIdAndUserId(report.workspaceId(), userId).map(w -> w.getId()).orElse(null);
        repository.save(JarvisTurnTiming.builder()
                .userId(userId)
                .workspaceId(workspaceId)
                .createdAt(OffsetDateTime.now(clock))
                .listenMode(mode)
                .sttProvider(provider(report.sttProvider()))
                .ttsProvider(provider(report.ttsProvider()))
                .toolTurn(Boolean.TRUE.equals(report.toolTurn()))
                .speechMs(mark(report.speechMs()))
                .transcriptMs(mark(report.transcriptMs()))
                .reactionMs(mark(report.reactionMs()))
                .sentMs(mark(report.sentMs()))
                .firstTokenMs(mark(report.firstTokenMs()))
                .firstOralSentenceMs(mark(report.firstOralSentenceMs()))
                .firstAudioByteMs(mark(report.firstAudioByteMs()))
                .firstSoundMs(mark(report.firstSoundMs()))
                .doneMs(mark(report.doneMs()))
                .interruptionsDetected(counter(report.interruptionsDetected()))
                .interruptionsRestored(counter(report.interruptionsRestored()))
                .interruptionsConfirmed(counter(report.interruptionsConfirmed()))
                .acknowledgements(counter(report.acknowledgements()))
                .falseTriggers(counter(report.falseTriggers()))
                // F-235 / SF-235-07 (D14)
                .brain(closed(report.brain(), BRAINS))
                .fastModel(model(report.fastModel()))
                .fastFirstTokenMs(mark(report.fastFirstTokenMs()))
                .groundingTrips(counter(report.groundingTrips()))
                .fillersRemoved(counter(report.fillersRemoved()))
                .repeatsRemoved(counter(report.repeatsRemoved()))
                .imitationsRemoved(counter(report.imitationsRemoved()))
                .forcedDelegation(Boolean.TRUE.equals(report.forcedDelegation()))
                .delegationReason(closed(report.delegationReason(), REASONS))
                .correction(Boolean.TRUE.equals(report.correction()))
                // F-239 / SF-239-02 (D3b) — raison et longueur d'une parole refusée, jamais le texte.
                .rejectedKind(closed(report.rejectedKind(), REJECTIONS))
                .rejectedChars(report.rejectedKind() == null || report.rejectedChars() == null ? null
                        : Math.max(0, Math.min(MAX_REJECTED_CHARS, report.rejectedChars())))
                // F-240 / SF-240-01 — ce qui est dit : longueur, coupe, invitation, étage. Jamais le texte.
                .spokenWords(bounded(report.spokenWords(), MAX_WORDS))
                .spokenChars(bounded(report.spokenChars(), MAX_SPOKEN_CHARS))
                .spokenMs(mark(report.spokenMs()))
                .announceWords(bounded(report.announceWords(), MAX_WORDS))
                .oralCut(report.oralCut())
                .invitation(report.invitation())
                .depthReached(depth(report.depthReached()))
                .depthVia(report.depthReached() == null ? null : closed(report.depthVia(), DEPTH_VIA))
                .portraitOn(report.portraitOn())
                .portraitBlocked(report.portraitBlocked() == null ? null : counter(report.portraitBlocked()))
                .personalAllusion(report.personalAllusion() == null ? null
                        : ALLUSIONS.contains(report.personalAllusion()) ? report.personalAllusion() : null)
                // F-247 / SF-247-01 — la question d'état et sa réponse, sans contenu.
                .hostOffline(report.hostOffline())
                .stateQuestion(report.stateQuestion())
                .stateAnswer(closed(report.stateAnswer(), STATE_ANSWERS))
                .stateBlockMs(mark(report.stateBlockMs()))
                // F-248 / SF-248-01 — le délai premier mot → pancartes et leur nombre, sans contenu.
                .cardsLagMs(mark(report.cardsLagMs()))
                .cardsCount(report.cardsCount() == null ? null : counter(report.cardsCount()))
                // F-233 / SF-233-13 — pourquoi le flux n'a pas servi (liste fermée), et la page du compagnon.
                .streamFallback(closed(report.streamFallback(), STREAM_FALLBACKS))
                .companion(report.companion())
                // F-252 / SF-252-01 — la vitesse : préparation, réponse provisoire, silence de fin, anticipations.
                .prepMs(mark(report.prepMs()))
                .emptyDiagnostic(diagnostic(report.emptyDiagnostic()))
                .speculation(closed(report.speculation(), SPECULATIONS))
                .endSilenceMs(mark(report.endSilenceMs()))
                .anticipationsDropped(report.anticipationsDropped() == null ? null
                        : counter(report.anticipationsDropped()))
                // F-255 / SF-255-01 — le tour et la page, écartés s'ils n'ont pas la forme d'un identifiant.
                .turnId(fr.claudegateway.jarvis.trace.JarvisTurnIds.clean(report.turnId()))
                .pageId(fr.claudegateway.jarvis.trace.JarvisTurnIds.clean(report.pageId()))
                // F-257 / SF-257-01 — la boucle vocale du tour (liste fermée), legacy si absente.
                .voiceLoop(loop(report.loop()))
                .build());
    }

    /** p50/p90 par étape et cumul des interruptions, sur les tours du compte depuis {@code days} jours. */
    public Latency latency(UUID userId, int days) {
        int window = Math.max(1, Math.min(days, MAX_DAYS));
        List<JarvisTurnTiming> rows = repository.findByUserIdAndCreatedAtGreaterThanEqual(userId,
                OffsetDateTime.now(clock).minusDays(window));
        List<Step> steps = new ArrayList<>();
        STEPS.forEach((name, read) -> steps.add(step(name, rows.stream().map(read).toList())));
        Map<String, Long> byMode = new LinkedHashMap<>();
        LISTEN_MODES.forEach(m -> byMode.put(m, rows.stream().filter(r -> m.equals(r.getListenMode())).count()));
        Interruptions interruptions = new Interruptions(
                rows.stream().mapToLong(JarvisTurnTiming::getInterruptionsDetected).sum(),
                rows.stream().mapToLong(JarvisTurnTiming::getInterruptionsRestored).sum(),
                rows.stream().mapToLong(JarvisTurnTiming::getInterruptionsConfirmed).sum(),
                rows.stream().mapToLong(JarvisTurnTiming::getAcknowledgements).sum(),
                rows.stream().mapToLong(JarvisTurnTiming::getFalseTriggers).sum());
        return new Latency(window, rows.size(), rows.stream().filter(JarvisTurnTiming::isToolTurn).count(), steps,
                interruptions, byMode, spoken(rows), oralLines(userId, window), allusions(rows), byLoop(rows));
    }

    /** F-257 / SF-257-01 — les boucles vocales, dans l'ordre de comparaison. */
    public static final List<String> LOOPS = List.of("legacy", "stream");

    /** F-257 / SF-257-01 — la boucle d'un tour : absente = {@code legacy} ; hors liste = refus. */
    static String loop(String value) {
        String closed = closed(value, LOOPS);
        return closed == null ? "legacy" : closed;
    }

    /**
     * F-257 / SF-257-01 — les deux boucles côte à côte : tours, p50/p90 par étape, replis hors flux, adoption de la
     * réponse provisoire. Une ligne d'avant la colonne ({@code voice_loop} NULL) compte en {@code legacy}.
     */
    static List<LoopMeasure> byLoop(List<JarvisTurnTiming> rows) {
        List<LoopMeasure> loops = new ArrayList<>();
        for (String loop : LOOPS) {
            List<JarvisTurnTiming> mine = rows.stream()
                    .filter(r -> loop.equals(r.getVoiceLoop() == null ? "legacy" : r.getVoiceLoop())).toList();
            List<Step> steps = new ArrayList<>();
            STEPS.forEach((name, read) -> steps.add(step(name, mine.stream().map(read).toList())));
            long fallbacks = mine.stream().filter(r -> r.getStreamFallback() != null).count();
            long speculated = mine.stream().filter(r -> r.getSpeculation() != null).count();
            long adopted = mine.stream().filter(r -> "adoptee".equals(r.getSpeculation())).count();
            loops.add(new LoopMeasure(loop, mine.size(), steps, fallbacks,
                    speculated == 0 ? null : Math.round(adopted * 1000.0 / speculated) / 1000.0));
        }
        return loops;
    }

    /** F-257 / SF-257-01 — une boucle : tours, étapes, replis hors flux, taux d'adoption ({@code null} sans essai). */
    public record LoopMeasure(String loop, int turns, List<Step> steps, long streamFallbacks, Double adoptionRate) {
    }

    /** F-240 / SF-240-01 — ce qui a été dit sur les tours de la fenêtre (sans contenu). */
    static Spoken spoken(List<JarvisTurnTiming> rows) {
        List<Integer> words = rows.stream().map(JarvisTurnTiming::getSpokenWords).filter(v -> v != null).sorted()
                .toList();
        List<Integer> ms = rows.stream().map(JarvisTurnTiming::getSpokenMs).filter(v -> v != null).sorted().toList();
        List<Integer> announce = rows.stream().map(JarvisTurnTiming::getAnnounceWords).filter(v -> v != null)
                .sorted().toList();
        long cut = rows.stream().filter(r -> Boolean.TRUE.equals(r.getOralCut())).count();
        long invited = rows.stream().filter(r -> Boolean.TRUE.equals(r.getInvitation())).count();
        Map<String, Long> depth = new LinkedHashMap<>();
        for (int d = 1; d <= MAX_DEPTH; d++) {
            int level = d;
            depth.put(String.valueOf(d), rows.stream()
                    .filter(r -> r.getDepthReached() != null && r.getDepthReached() == level).count());
        }
        Map<String, Long> via = new LinkedHashMap<>();
        DEPTH_VIA.forEach(v -> via.put(v, rows.stream().filter(r -> v.equals(r.getDepthVia())).count()));
        return new Spoken(words.size(), percentile(words, 50), percentile(words, 90), percentile(ms, 50),
                percentile(ms, 90), percentile(announce, 90), rate(cut, words.size()), rate(invited, words.size()),
                depth, via);
    }

    /**
     * F-240 / SF-240-01 — la longueur <b>écrite</b> des lignes 🎙 des réponses d'Opus du compte sur la fenêtre (la
     * base de départ, lisible avant comme après F-240). Lecture sous {@code user_id}, rien n'est recopié.
     */
    Spoken.OralLines oralLines(UUID userId, int days) {
        List<Integer> words = messages
                .findTop500ByUserIdAndRoleAndCreatedAtGreaterThanEqualAndContentContainingOrderByCreatedAtDesc(userId,
                        "ASSISTANT", OffsetDateTime.now(clock).minusDays(days), JarvisReplies.ORAL_MARK)
                .stream().filter(m -> !m.isFastBrain()).map(AtelierMessage::getContent)
                .map(JarvisReplies::oralLine).filter(l -> l != null && !l.isBlank())
                .map(JarvisSpokenWords::count).sorted().toList();
        return new Spoken.OralLines(words.size(), percentile(words, 50), percentile(words, 90));
    }

    private static Double rate(long part, int total) {
        return total == 0 ? null : Math.round(part * 1000.0 / total) / 1000.0;
    }

    /** Purge des mesures de plus de {@link #RETENTION_DAYS} jours. */
    public int purge() {
        return repository.deleteOlderThan(OffsetDateTime.now(clock).minus(Duration.ofDays(RETENTION_DAYS)));
    }

    static Step step(String name, List<Integer> values) {
        List<Integer> present = values.stream().filter(v -> v != null).sorted().toList();
        return new Step(name, present.size(), percentile(present, 50), percentile(present, 90));
    }

    /** Percentile au rang le plus proche sur une liste triée ; {@code null} si vide. */
    static Integer percentile(List<Integer> sorted, int p) {
        return JarvisSpokenWords.percentile(sorted, p);
    }

    /** F-240 / SF-240-01 — une longueur bornée : refusée si négative, plafonnée sinon. */
    private static Integer bounded(Integer value, int max) {
        if (value == null) {
            return null;
        }
        if (value < 0) {
            throw new IllegalArgumentException("Longueur négative.");
        }
        return Math.min(max, value);
    }

    /** F-240 / SF-240-01 — l'étage atteint : 1, 2 ou 3. */
    private static Integer depth(Integer value) {
        if (value == null) {
            return null;
        }
        if (value < 1 || value > MAX_DEPTH) {
            throw new IllegalArgumentException("Étage hors bornes (1 à " + MAX_DEPTH + ").");
        }
        return value;
    }

    private static Integer mark(Integer ms) {
        if (ms == null) {
            return null;
        }
        if (ms < 0 || ms > MAX_MARK_MS) {
            throw new IllegalArgumentException("Marque hors bornes (0 à " + MAX_MARK_MS + " ms).");
        }
        return ms;
    }

    private static int counter(Integer value) {
        if (value == null) {
            return 0;
        }
        if (value < 0 || value > MAX_COUNTER) {
            throw new IllegalArgumentException("Compteur hors bornes (0 à " + MAX_COUNTER + ").");
        }
        return value;
    }

    /** F-235 / SF-235-07 — les cerveaux d'un tour vocal (liste fermée). */
    static final List<String> BRAINS = List.of("FAST", "FAST_DELEGATED", "OPUS");
    /** F-239 / SF-239-02 — les raisons d'un refus de parole (liste fermée de la porte de l'écran). */
    static final List<String> REJECTIONS = List.of("empty", "noise", "hallucination", "no_voice", "implausible");
    /**
     * F-233 / SF-233-13 — pourquoi l'écoute n'a pas été faite en flux (liste fermée) : le jeton de session (refusé,
     * indisponible, en échec), le micro, l'ouverture chez le fournisseur (délai, refus, réseau, fermeture), la capture
     * audio, une session coupée pendant l'écoute, ou autre.
     */
    static final List<String> STREAM_FALLBACKS = List.of("ticket_refuse", "ticket_indisponible", "ticket_echec",
            "micro", "ouverture_delai", "ouverture_refusee", "ouverture_reseau", "ouverture_fermee", "capture", "coupe",
            "autre");
    /** F-239 / SF-239-02 — borne de la longueur rapportée. */
    static final int MAX_REJECTED_CHARS = 10_000;
    /** F-235 / SF-235-07 — les raisons d'une délégation d'office ou d'un repli (liste fermée). */
    static final List<String> REASONS = List.of("grounding", "refusal", "error", "timeout", "empty", "tool_text",
            "forced", "budget", "quota", "no_subject");
    private static final Pattern MODEL = Pattern.compile("^[a-z0-9.\\-]{1,64}$");
    /** F-240 / SF-240-01 — bornes de ce qui est dit. */
    static final int MAX_WORDS = 2_000;
    static final int MAX_SPOKEN_CHARS = 20_000;
    static final int MAX_DEPTH = 3;
    /** F-240 / SF-240-01 — comment un étage est atteint (liste fermée). */
    static final List<String> DEPTH_VIA = List.of("voix", "toucher");

    private static String closed(String value, List<String> allowed) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        String match = allowed.stream().filter(a -> a.equalsIgnoreCase(v)).findFirst().orElse(null);
        if (match == null) {
            throw new IllegalArgumentException("Valeur hors liste.");
        }
        return match;
    }

    private static String model(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String flat = name.strip().toLowerCase();
        if (!MODEL.matcher(flat).matches()) {
            throw new IllegalArgumentException("Nom de modèle illisible.");
        }
        return flat;
    }

    private static String provider(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String flat = name.strip().toLowerCase();
        if (!PROVIDER.matcher(flat).matches()) {
            throw new IllegalArgumentException("Nom de fournisseur illisible.");
        }
        return flat;
    }

    /** La mesure d'un tour vocal, telle que l'écran la déclare. Aucun contenu. */
    public record TimingReport(UUID workspaceId, String listenMode, String sttProvider, String ttsProvider,
            Boolean toolTurn, Integer speechMs, Integer transcriptMs, Integer reactionMs, Integer sentMs,
            Integer firstTokenMs, Integer firstOralSentenceMs, Integer firstAudioByteMs, Integer firstSoundMs,
            Integer doneMs, Integer interruptionsDetected, Integer interruptionsRestored,
            Integer interruptionsConfirmed, Integer acknowledgements, Integer falseTriggers,
            // F-235 / SF-235-07 (D14) — facultatifs, absents des écrans d'avant F-235.
            String brain, String fastModel, Integer fastFirstTokenMs, Integer groundingTrips, Integer fillersRemoved,
            Integer repeatsRemoved, Integer imitationsRemoved, Boolean forcedDelegation, String delegationReason,
            Boolean correction,
            // F-239 / SF-239-02 (D3b) — facultatifs : une parole refusée par l'écran (aucun tour).
            String rejectedKind, Integer rejectedChars,
            // F-240 / SF-240-01 — facultatifs : ce qui est dit (jamais le texte) et l'étage atteint.
            Integer spokenWords, Integer spokenChars, Integer spokenMs, Integer announceWords, Boolean oralCut,
            Boolean invitation, Integer depthReached, String depthVia,
            // F-241 / SF-241-03 — facultatifs : le portrait servait, phrases écartées par ses interdits.
            Boolean portraitOn, Integer portraitBlocked,
            // F-241 / SF-241-04 — facultatif : l'allusion personnelle du tour (SPONTANEE, REPONSE, AUCUNE).
            String personalAllusion,
            // F-247 / SF-247-01 — facultatifs : poste éteint, question d'état, réponse, lecture du bloc.
            Boolean hostOffline, Boolean stateQuestion, String stateAnswer, Integer stateBlockMs,
            // F-248 / SF-248-01 — facultatifs : délai première phrase jouée → première pancarte, nombre de pancartes.
            Integer cardsLagMs, Integer cardsCount,
            // F-233 / SF-233-13 — facultatifs : raison du repli hors flux (liste fermée), page ouverte dans le compagnon.
            String streamFallback, Boolean companion,
            // F-252 / SF-252-01 — facultatifs : préparation serveur, réponse provisoire, silence de fin, anticipations.
            Integer prepMs, String speculation, Integer endSilenceMs, Integer anticipationsDropped,
            // F-253 / SF-253-06 — facultatif : la cause d'un tour vide (arrêt, outils, motifs de rejet).
            String emptyDiagnostic,
            // F-255 / SF-255-01 — facultatifs : le tour et la page de l'écran (identifiants aléatoires).
            String turnId, String pageId,
            // F-257 / SF-257-01 — facultatif : la boucle vocale (legacy, stream), legacy si absente.
            String loop) {
    }

    /** F-253 / SF-253-06 — vocabulaire du diagnostic, borné : tout autre caractère retire la valeur. */
    private static final Pattern DIAGNOSTIC = Pattern.compile("[a-z0-9_=:, ]{1,200}");

    static String diagnostic(String raw) {
        return raw != null && DIAGNOSTIC.matcher(raw).matches() ? raw : null;
    }

    /** F-252 / SF-252-01 — les issues d'une réponse provisoire (SF-252-02/04, liste fermée). */
    static final List<String> SPECULATIONS = List.of("adoptee", "relancee", "perdue", "anticipee");

    /** F-247 / SF-247-01 — les valeurs admises de {@code stateAnswer}. */
    public static final List<String> STATE_ANSWERS = List.of("UNKNOWN", "KNOWN_STALE", "KNOWN_FRESH", "NA");

    /** F-241 / SF-241-04 — les valeurs admises de {@code personalAllusion}. */
    static final java.util.Set<String> ALLUSIONS = java.util.Set.of("SPONTANEE", "REPONSE", "AUCUNE");

    /**
     * F-241 / SF-241-04 — les allusions sur les tours du compte où le portrait servait : nombre de ces tours, allusions
     * spontanées, en réponse, et le <b>taux d'allusion spontanée</b> (par tour, donc par échange de conversation).
     */
    public record Allusions(long portraitTurns, long spontaneous, long inResponse, Double spontaneousRate) {
    }

    /** Une étape : nombre de tours où elle a eu lieu, p50 et p90 en ms (nuls sans mesure). */
    public record Step(String step, int count, Integer p50, Integer p90) {
    }

    /** Le cumul des interruptions de la fenêtre. */
    public record Interruptions(long detected, long restored, long confirmed, long acknowledgements,
            long falseTriggers) {
    }

    /** La mesure rendue à l'administrateur. */
    public record Latency(int days, int turns, long toolTurns, List<Step> steps, Interruptions interruptions,
            Map<String, Long> byListenMode, Spoken spoken, Spoken.OralLines oralLines, Allusions allusions,
            List<LoopMeasure> byLoop) {
    }

    /** F-241 / SF-241-04 — le taux d'allusion spontanée, sur les tours où le portrait servait. */
    static Allusions allusions(List<JarvisTurnTiming> rows) {
        long on = rows.stream().filter(r -> Boolean.TRUE.equals(r.getPortraitOn())).count();
        long spontaneous = rows.stream().filter(r -> "SPONTANEE".equals(r.getPersonalAllusion())).count();
        long response = rows.stream().filter(r -> "REPONSE".equals(r.getPersonalAllusion())).count();
        return new Allusions(on, spontaneous, response,
                on == 0 ? null : Math.round(spontaneous * 1000.0 / on) / 1000.0);
    }

    /**
     * F-240 / SF-240-01 — ce qui a été dit : nombre de réponses mesurées, p50/p90 des mots et de la durée dite, p90
     * des mots d'annonce, taux de coupe et d'invitation (sur les réponses mesurées), étages atteints et par quel
     * chemin.
     */
    public record Spoken(int answers, Integer wordsP50, Integer wordsP90, Integer msP50, Integer msP90,
            Integer announceWordsP90, Double cutRate, Double invitationRate, Map<String, Long> depthReached,
            Map<String, Long> depthVia) {

        /** La longueur écrite des lignes 🎙 des réponses d'Opus (base de départ). */
        public record OralLines(int replies, Integer wordsP50, Integer wordsP90) {
        }
    }
}
