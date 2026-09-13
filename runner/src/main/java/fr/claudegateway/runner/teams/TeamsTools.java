package fr.claudegateway.runner.teams;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.ToolContext;
import fr.claudegateway.runner.ToolExecutor;
import fr.claudegateway.runner.ToolOutcome;

/**
 * <b>Les outils Teams</b> donnés à l'agent (F-87 / SF-87-03 pour {@code teams_status},
 * F-88 / SF-88-01 pour les outils de lecture).
 *
 * <p><b>Aucun n'est un bouton.</b> L'agent les compose : « qu'est-ce qu'on attend de moi ? »
 * appellera les mentions, puis la recherche sur les variantes du nom, puis la lecture des fils
 * récemment actifs — et il conclura. Une autre question appellera autre chose.</p>
 *
 * <p><b>Deux règles de forme, valables pour tous.</b></p>
 * <ol>
 *   <li>Une liaison impossible reste un <b>succès</b> d'outil porteur d'un état et de son remède.
 *       Une erreur d'outil ferait dire à l'agent « je n'ai pas réussi », là où il faut dire
 *       « lancez votre navigateur comme ceci ».</li>
 *   <li>Chaque résultat porte, à côté de ce qui a été lu, <b>ce qui n'a pas pu l'être</b> et la
 *       <b>fenêtre réellement lue</b>. Un trou se voit ; un trou silencieux ne se voit jamais.</li>
 * </ol>
 */
public final class TeamsTools implements ToolExecutor {

    public static final String STATUS = "teams_status";
    /** Retrouver une conversation par personne, groupe ou sujet (F-88 / SF-88-01). */
    public static final String FIND_CONVERSATIONS = "teams_find_conversations";
    /** Lire une conversation sur une fenêtre de temps (F-88 / SF-88-01). */
    public static final String READ_CONVERSATION = "teams_read_conversation";
    /** Là où l'on m'a mentionné — par le flux d'activité (F-88 / SF-88-02). */
    public static final String MENTIONS = "teams_mentions";
    /** Rechercher dans le contenu — par l'index de Teams (F-88 / SF-88-02). */
    public static final String SEARCH = "teams_search";
    /** Retrouver une réunion, par date, sujet ou participant (F-88 / SF-88-02). */
    public static final String FIND_MEETINGS = "teams_find_meetings";
    /** La transcription d'une réunion enregistrée (F-88 / SF-88-02). */
    public static final String MEETING_TRANSCRIPT = "teams_meeting_transcript";
    /** L'enregistrement d'une réunion : où il est, et ce qu'on n'en fait pas (F-88 / SF-88-02). */
    public static final String MEETING_RECORDING = "teams_meeting_recording";
    /**
     * <b>Démarre</b> l'extraction et l'alignement des captures d'un enregistrement
     * (F-90 / SF-90-03). Traitement lourd, donc asynchrone : il rend la main tout de suite.
     */
    public static final String MEETING_MOMENTS = "teams_meeting_moments";
    /** Où en est un travail de moments, et — quand il est fini — ses moments (F-90 / SF-90-03). */
    public static final String MOMENTS_STATUS = "teams_moments_status";
    /**
     * <b>Démarre un enregistrement local</b> (F-91 / SF-91-02). Le seul outil du volet qui
     * <b>crée</b> au lieu de relire — et le seul qui puisse refuser pour une raison qui n'est pas
     * technique.
     */
    public static final String CAPTURE_START = "teams_capture_start";
    /** Arrête l'enregistrement local en cours (F-91 / SF-91-02). */
    public static final String CAPTURE_STOP = "teams_capture_stop";
    /** Où en est l'enregistrement local, et ceux d'avant (F-91 / SF-91-02). */
    public static final String CAPTURE_STATUS = "teams_capture_status";
    public static final String CAPABILITY = "teams";

    /** Le catalogue, dans l'ordre où il est donné à l'agent. */
    public static final List<String> CATALOG = List.of(STATUS, FIND_CONVERSATIONS,
            READ_CONVERSATION, MENTIONS, SEARCH, FIND_MEETINGS, MEETING_TRANSCRIPT,
            MEETING_RECORDING, MEETING_MOMENTS, MOMENTS_STATUS, CAPTURE_START, CAPTURE_STOP,
            CAPTURE_STATUS);

    private final ObjectMapper mapper = new ObjectMapper();
    private final TeamsSession session;
    private final TeamsProbe probe;
    private final BrowserLink.Sleeper sleeper;
    private final TeamsScopeNotice scope = new TeamsScopeNotice();
    private final boolean enabled;
    private final String disabledReason;

    private volatile boolean firstUseSaid;
    private TeamsLedger ledger;

    /**
     * Le travail long des captures (F-90 / SF-90-03). {@code null} quand ce runner n'en a pas —
     * l'outil le <b>dit</b> alors, il ne fait pas semblant.
     */
    private MomentsWorker momentsWorker;

    /**
     * L'enregistrement local (F-91). {@code null} quand ce runner n'en a pas — l'outil le <b>dit</b>
     * alors, il ne fait pas semblant.
     */
    private LocalCapture capture;

    /**
     * La transcription locale (F-91 / SF-91-03). {@code null} quand ce runner n'en a pas — une
     * capture reste possible, mais elle n'aura pas de transcription, et c'est <b>dit</b>.
     */
    private TranscriptionWorker transcription;

    public TeamsTools(TeamsSession session, BrowserLink.Sleeper sleeper) {
        this.session = session;
        this.probe = new TeamsProbe(session.adapter());
        this.sleeper = sleeper;
        this.enabled = true;
        this.disabledReason = "";
    }

    /**
     * Branche le travail long des captures (F-90 / SF-90-03). Posé après construction parce que la
     * remontée a besoin du jeton du poste, que le montage des outils ne connaît pas encore.
     */
    public TeamsTools withMoments(MomentsWorker worker) {
        this.momentsWorker = worker;
        return this;
    }

    /**
     * Branche l'enregistrement local (F-91 / SF-91-02). Posé après construction pour la même raison
     * que les moments : le moteur dépend du dossier de travail et du témoin, que le montage des
     * outils ne connaît pas encore.
     */
    public TeamsTools withCapture(LocalCapture value) {
        this.capture = value;
        return this;
    }

    /**
     * Branche la transcription locale (F-91 / SF-91-03). {@code null} quand ce runner n'en a pas :
     * l'outil le <b>dit</b> alors — une capture sans transcription reste une capture, et personne ne
     * doit croire qu'un compte rendu va suivre.
     */
    public TeamsTools withTranscription(TranscriptionWorker value) {
        this.transcription = value;
        return this;
    }

    public static TeamsTools disabled(String reason) {
        return new TeamsTools(reason);
    }

    private TeamsTools(String reason) {
        this.session = null;
        this.probe = null;
        this.sleeper = null;
        this.enabled = false;
        this.disabledReason = reason == null || reason.isBlank()
                ? "Le volet Teams est désactivé sur cette machine." : reason.strip();
    }

    public boolean enabled() {
        return enabled;
    }

    @Override
    public ToolOutcome execute(String tool, JsonNode input, ToolContext context) {
        return switch (tool == null ? "" : tool) {
            case STATUS -> status();
            case FIND_CONVERSATIONS -> findConversations(input);
            case READ_CONVERSATION -> readConversation(input);
            case MENTIONS -> mentions(input);
            case SEARCH -> search(input);
            case FIND_MEETINGS -> findMeetings(input);
            case MEETING_TRANSCRIPT -> meetingTranscript(input);
            case MEETING_RECORDING -> meetingRecording(input);
            case MEETING_MOMENTS -> meetingMoments(input, context);
            case MOMENTS_STATUS -> momentsStatus(input);
            case CAPTURE_START -> captureStart(input);
            case CAPTURE_STOP -> captureStop(input, context);
            case CAPTURE_STATUS -> captureStatus(input);
            default -> ToolOutcome.error("unsupported_tool", "Outil Teams inconnu : " + tool);
        };
    }

    // ------------------------------------------------------------------ teams_status

    private ToolOutcome status() {
        if (!enabled) {
            return ToolOutcome.ok(render(new TeamsProbeResult(TeamsLinkState.BROWSER_NOT_DETECTED,
                    TeamsHealth.full(0), 0, "", disabledReason), ""));
        }
        // D3 : l'annonce de premier usage voyage avec le PREMIER résultat, et une seule fois. Elle
        // est dite sur la console par la session ; ici, elle est écrite là où l'utilisateur regarde.
        String firstUse = firstUse();
        TeamsProbeResult result;
        try {
            result = probe.probe(link(), sleeper);
        } catch (BrowserLinkException e) {
            result = TeamsProbe.notLinked(e);
        } catch (RuntimeException e) {
            result = new TeamsProbeResult(TeamsLinkState.BROWSER_NOT_DETECTED, TeamsHealth.full(0),
                    0, "", "La liaison au navigateur n'a pas abouti sur cette machine.");
        }
        return ToolOutcome.ok(render(result, firstUse));
    }

    String render(TeamsProbeResult result, String firstUse) {
        ObjectNode node = mapper.createObjectNode();
        node.put("state", result.state().name());
        node.put("label", result.state().label());
        node.put("sentence", result.sentence());
        node.put("remedy", result.remedy());
        node.put("browser", result.browser());
        node.put("observedResponses", result.observed());
        node.put("conclusive", result.conclusive());
        node.put("adapter", enabled ? session.adapter().version() : "");
        ObjectNode health = node.putObject("health");
        health.put("verdict", result.health().verdict().name());
        health.put("recognizedFields", result.health().recognizedFields());
        health.put("expectedFields", result.health().expectedFields());
        ArrayNode missing = health.putArray("missingFields");
        result.health().missingFields().forEach(missing::add);
        ArrayNode versions = health.putArray("observedApiVersions");
        result.health().observedApiVersions().forEach(versions::add);
        StringBuilder text = new StringBuilder(result.sentence());
        if (!result.remedy().isEmpty()) {
            text.append(System.lineSeparator()).append(result.remedy());
        }
        if (firstUse != null && !firstUse.isBlank()) {
            text.append(System.lineSeparator()).append(firstUse);
        }
        node.put("text", text.toString());
        return node.toString();
    }

    // ------------------------------------------------------------------ teams_find_conversations

    /**
     * Les conversations que Teams a servies depuis le rattachement, <b>classées par dernière
     * activité</b>, filtrées par rapprochement sur le sujet, les participants et l'identifiant.
     *
     * <p>Le rapprochement porte <b>autant</b> sur le nom d'un participant que sur le sujet : un
     * tête-à-tête n'a pas de sujet, et « la conversation avec Paul » est la façon normale de la
     * nommer.</p>
     */
    private ToolOutcome findConversations(JsonNode input) {
        Refusal refusal = refusalIfUnavailable(FIND_CONVERSATIONS);
        if (refusal != null) {
            return refusal.outcome();
        }
        BrowserLink link = link();
        TeamsLedger book = ledger();
        TeamsAsk ask = TeamsAsk.standard(null);
        List<TeamsGap> harvested = new TeamsHarvester(link, book,
                new PageGestures(link, sleeper)).harvestInPlace(ask.window());

        String query = TeamsAsk.text(input, "query", "who", "topic");
        int limit = (int) Math.max(1, Math.min(200, TeamsAsk.number(input, 50, "limit")));
        List<TeamsConversation> matching = new ArrayList<>();
        for (TeamsConversation conversation : book.conversations()) {
            if (matches(conversation, query)) {
                matching.add(conversation);
            }
            if (matching.size() >= limit) {
                break;
            }
        }

        TeamsToolResult result = new TeamsToolResult(FIND_CONVERSATIONS,
                session.adapter().version(), TeamsLinkState.LINKED);
        ArrayNode items = result.array("conversations");
        matching.forEach(conversation -> TeamsViews.conversation(items, conversation));
        List<TeamsGap> gaps = new ArrayList<>(book.gaps());
        if (matching.isEmpty()) {
            gaps.add(TeamsGap.of(TeamsGapKind.NOTHING_OBSERVED,
                    query.isEmpty() ? "liste des conversations" : query,
                    "aucune conversation servie par Teams depuis le rattachement ne correspond"));
        }
        result.window(ask.window().covering(null, null, false, false))
                .gaps(gaps)
                .health(book.health())
                .text(sentence(matching.size(), "conversation", gaps, book, query));
        return ToolOutcome.ok(result.render());
    }

    // ------------------------------------------------------------------ teams_read_conversation

    /**
     * Le morceau difficile : lire un fil sur une fenêtre de temps. Le plafond (D4) est <b>annoncé</b>
     * dans la description de l'outil, <b>négociable</b> par {@code from} / {@code max_messages}, et
     * le résultat porte <b>toujours</b> la fenêtre réellement lue.
     */
    private ToolOutcome readConversation(JsonNode input) {
        Refusal refusal = refusalIfUnavailable(READ_CONVERSATION);
        if (refusal != null) {
            return refusal.outcome();
        }
        BrowserLink link = link();
        TeamsLedger book = ledger();
        TeamsAsk ask = TeamsAsk.of(input, null);
        String wanted = TeamsAsk.text(input, "conversation_id", "conversationId", "id");

        TeamsHarvester.Harvest harvest =
                new TeamsHarvester(link, book, new PageGestures(link, sleeper))
                        .readConversation(wanted, ask.window());

        TeamsConversation conversation = book.conversation(harvest.conversationId());
        String label = conversation == null ? harvest.conversationId() : conversation.label();

        TeamsToolResult result = new TeamsToolResult(READ_CONVERSATION,
                session.adapter().version(), TeamsLinkState.LINKED);
        TeamsViews.conversation(result.put("conversation"), conversation == null
                ? new TeamsConversation(harvest.conversationId(), TeamsConversationKind.GROUP, "",
                        List.of(), null, "")
                : conversation);
        ArrayNode messages = result.array("messages");
        harvest.messages().forEach(message -> TeamsViews.message(messages, message, book.self()));

        StringBuilder text = new StringBuilder(harvest.reading().summary("messages"));
        ask.notes().forEach(note -> text.append(' ').append(note));
        if (book.self() == null) {
            text.append(" L'utilisateur relié n'a pas encore été identifié : « m'a-t-on "
                    + "mentionné ? » ne peut pas être tranché ici — le flux d'activité y répond.");
        }
        result.window(harvest.window())
                .gaps(harvest.gaps())
                .health(harvest.health())
                .viewport(harvest.viewport())
                .notice(scope.announceOnce(harvest.conversationId(), "les messages de ce fil", label))
                .with("firstUse", firstUse())
                .text(text.toString());
        return ToolOutcome.ok(result.render());
    }

    // ------------------------------------------------------------------ teams_mentions

    /**
     * <b>Le premier gisement</b> : la mention explicite, lue dans le <b>flux d'activité</b> que Teams
     * calcule déjà. Exact, et peu cher — c'est Provider-First appliqué à l'interface : on ne
     * parcourt pas des dizaines de conversations pour retrouver ce que le service a déjà trié.
     *
     * <p>Le flux porte aussi les réactions et les réponses ; l'adaptateur ne retient que les
     * mentions. Ce n'est pas un défaut du flux, c'est un tri.</p>
     */
    private ToolOutcome mentions(JsonNode input) {
        Refusal refusal = refusalIfUnavailable(MENTIONS);
        if (refusal != null) {
            return refusal.outcome();
        }
        BrowserLink link = link();
        TeamsLedger book = ledger();
        TeamsAsk ask = TeamsAsk.of(input, null);
        List<TeamsGap> harvested = new TeamsHarvester(link, book,
                new PageGestures(link, sleeper)).harvestInPlace(ask.window());

        int limit = (int) Math.max(1, Math.min(TeamsAsk.MAX_CAP,
                TeamsAsk.number(input, ask.window().cap(), "max_mentions", "limit")));
        List<TeamsMentionEvent> events = book.mentionsIn(ask.window());
        if (events.size() > limit) {
            events = events.subList(0, limit);
        }

        TeamsToolResult result = new TeamsToolResult(MENTIONS, session.adapter().version(),
                TeamsLinkState.LINKED);
        ArrayNode items = result.array("mentions");
        events.forEach(event -> TeamsViews.mention(items, event));

        List<TeamsGap> gaps = new ArrayList<>(harvested);
        if (events.isEmpty()) {
            gaps.add(TeamsGap.of(TeamsGapKind.NOTHING_OBSERVED, "flux d'activité",
                    "aucune mention servie par Teams depuis le rattachement"));
        }
        StringBuilder text = new StringBuilder(count(events.size(), "mention trouvée", "mentions trouvées")
                + " dans le flux d'activité, " + ask.window().describe() + '.');
        appendGaps(text, gaps);
        if (book.self() == null) {
            // On rend ce que le flux porte, et on dit qu'on n'a pas pu vérifier qu'il s'agit bien de
            // l'utilisateur : affirmer « ce sont vos mentions » sans le savoir serait faux.
            text.append(" L'utilisateur relié n'a pas encore été identifié : ce sont les mentions"
                    + " que le flux de cette session porte, sans vérification supplémentaire.");
        }
        ask.notes().forEach(note -> text.append(' ').append(note));
        result.window(ask.window().covering(
                        events.isEmpty() ? null : events.get(events.size() - 1).at(),
                        events.isEmpty() ? null : events.get(0).at(), false, false))
                .gaps(gaps)
                .health(book.health())
                .with("firstUse", firstUse())
                .text(text.toString());
        return ToolOutcome.ok(result.render());
    }

    // ------------------------------------------------------------------ teams_search

    /**
     * <b>Le deuxième gisement</b> : le nom écrit en clair — « Francky s'occupe du MFA ». On pose la
     * question à l'<b>index de Teams</b>, qui indexe le contenu, au lieu de fabriquer le nôtre.
     *
     * <p>Quand le champ de recherche n'est pas trouvable dans la page, l'outil rend <b>zéro résultat
     * et un manque nommé</b>, avec le remède. Une liste vide silencieuse se lirait « personne n'a
     * écrit votre nom », ce qui serait faux.</p>
     */
    private ToolOutcome search(JsonNode input) {
        Refusal refusal = refusalIfUnavailable(SEARCH);
        if (refusal != null) {
            return refusal.outcome();
        }
        String query = TeamsAsk.text(input, "query", "q", "text");
        TeamsAsk ask = TeamsAsk.of(input, null);
        TeamsLedger book = ledger();

        TeamsToolResult result = new TeamsToolResult(SEARCH, session.adapter().version(),
                TeamsLinkState.LINKED);
        result.with("query", query);
        List<TeamsGap> gaps = new ArrayList<>();
        if (query.isEmpty()) {
            gaps.add(TeamsGap.of(TeamsGapKind.MISSING_FIELD, "recherche Teams", "query"));
            result.array("results");
            result.window(ask.window()).gaps(gaps).health(book.health())
                    .text("Aucune recherche n'a été faite : il faut dire quoi chercher.");
            return ToolOutcome.ok(result.render());
        }

        BrowserLink link = link();
        PageGestures gestures = new PageGestures(link, sleeper);
        TeamsHarvester harvester = new TeamsHarvester(link, book, gestures);
        List<TeamsGap> harvested = new ArrayList<>(harvester.harvestInPlace(ask.window()));

        PageGestures.Ask asked = gestures.ask(query);
        String viewport = "";
        if (asked.done()) {
            harvested.addAll(harvester.harvestInPlace(ask.window()));
            gestures.restoreSearch(asked);
            viewport = "J'ai posé cette question dans la recherche de Teams ; le champ a été remis "
                    + "tel qu'il était.";
        } else {
            gaps.add(TeamsGap.of(TeamsGapKind.CONVERSATION_NOT_REACHED, "recherche Teams",
                    "le champ de recherche n'a pas été trouvé dans la page"));
        }
        gaps.addAll(harvested);

        int limit = (int) Math.max(1, Math.min(TeamsAsk.MAX_CAP,
                TeamsAsk.number(input, 50, "max_results", "limit")));
        List<TeamsMessage> hits = book.searchHits(ask.window());
        if (hits.size() > limit) {
            hits = hits.subList(0, limit);
        }
        ArrayNode items = result.array("results");
        List<TeamsMessage> rendered = hits;
        rendered.forEach(hit -> TeamsViews.message(items, hit, book.self()));

        if (rendered.isEmpty() && !asked.done()) {
            gaps.add(TeamsGap.of(TeamsGapKind.NOTHING_OBSERVED, query,
                    "aucun résultat de recherche n'a été servi par Teams"));
        }
        StringBuilder text = new StringBuilder(count(rendered.size(), "résultat trouvé", "résultats trouvés")
                + " pour « " + query + " », " + ask.window().describe() + '.');
        appendGaps(text, gaps);
        if (!asked.done()) {
            text.append(" Je n'ai pas pu poser la question dans Teams : tapez « ").append(query)
                    .append(" » dans la recherche de Teams, puis redemandez — je lirai ce qu'il"
                            + " aura servi. Ce résultat ne veut donc PAS dire qu'il n'y a rien.");
        }
        result.window(ask.window().covering(
                        rendered.isEmpty() ? null : rendered.get(rendered.size() - 1).sentAt(),
                        rendered.isEmpty() ? null : rendered.get(0).sentAt(), false, false))
                .gaps(gaps)
                .health(book.health())
                .viewport(viewport)
                .with("firstUse", firstUse())
                .text(text.toString());
        return ToolOutcome.ok(result.render());
    }

    // ------------------------------------------------------------------ réunions

    private ToolOutcome findMeetings(JsonNode input) {
        Refusal refusal = refusalIfUnavailable(FIND_MEETINGS);
        if (refusal != null) {
            return refusal.outcome();
        }
        BrowserLink link = link();
        TeamsLedger book = ledger();
        TeamsAsk ask = TeamsAsk.of(input, null);
        List<TeamsGap> harvested = new TeamsHarvester(link, book,
                new PageGestures(link, sleeper)).harvestInPlace(ask.window());

        String query = TeamsAsk.text(input, "query", "subject", "who");
        int limit = (int) Math.max(1, Math.min(200, TeamsAsk.number(input, 50, "limit")));
        List<TeamsMeeting> found = new ArrayList<>();
        for (TeamsMeeting meeting : book.meetings()) {
            if (!matches(meeting, query) || outside(meeting, ask.window())) {
                continue;
            }
            found.add(meeting);
            if (found.size() >= limit) {
                break;
            }
        }

        TeamsToolResult result = new TeamsToolResult(FIND_MEETINGS, session.adapter().version(),
                TeamsLinkState.LINKED);
        ArrayNode items = result.array("meetings");
        found.forEach(meeting -> TeamsViews.meeting(items, meeting));
        List<TeamsGap> gaps = new ArrayList<>(harvested);
        if (found.isEmpty()) {
            gaps.add(TeamsGap.of(TeamsGapKind.NOTHING_OBSERVED,
                    query.isEmpty() ? "réunions" : query,
                    "aucune réunion servie par Teams depuis le rattachement ne correspond"));
        }
        StringBuilder text = new StringBuilder(count(found.size(), "réunion trouvée", "réunions trouvées")
                + (query.isEmpty() ? "" : " pour « " + query + " »") + '.');
        appendGaps(text, gaps);
        result.window(ask.window()).gaps(gaps).health(book.health())
                .with("firstUse", firstUse()).text(text.toString());
        return ToolOutcome.ok(result.render());
    }

    /**
     * La transcription d'une réunion enregistrée. <b>D1 s'applique ici aussi</b> : la déclaration de
     * portée paraît une fois par réunion, avant le premier traitement des paroles de tiers.
     */
    private ToolOutcome meetingTranscript(JsonNode input) {
        Refusal refusal = refusalIfUnavailable(MEETING_TRANSCRIPT);
        if (refusal != null) {
            return refusal.outcome();
        }
        BrowserLink link = link();
        TeamsLedger book = ledger();
        TeamsAsk ask = TeamsAsk.standard(null);
        List<TeamsGap> harvested = new TeamsHarvester(link, book,
                new PageGestures(link, sleeper)).harvestInPlace(ask.window());

        String meetingId = TeamsAsk.text(input, "meeting_id", "meetingId", "id");
        TeamsMeeting meeting = book.meeting(meetingId);
        List<TeamsTranscriptCue> cues =
                meetingId.isEmpty() ? List.of() : book.transcriptOf(meetingId);

        TeamsToolResult result = new TeamsToolResult(MEETING_TRANSCRIPT,
                session.adapter().version(), TeamsLinkState.LINKED);
        result.with("meetingId", meetingId);
        if (meeting != null) {
            TeamsViews.meeting(result.put("meeting"), meeting);
        }
        ArrayNode items = result.array("cues");
        cues.forEach(cue -> TeamsViews.cue(items, cue));

        List<TeamsGap> gaps = new ArrayList<>(harvested);
        if (meetingId.isEmpty()) {
            gaps.add(TeamsGap.of(TeamsGapKind.MISSING_FIELD, "transcription", "meeting_id"));
        } else if (cues.isEmpty()) {
            gaps.add(TeamsGap.of(TeamsGapKind.NOTHING_OBSERVED, meetingId,
                    meeting != null && !meeting.transcriptAvailable()
                            ? "cette réunion n'annonce aucune transcription"
                            : "aucune transcription n'a été servie par Teams pour cette réunion"));
        }
        StringBuilder text = new StringBuilder(count(cues.size(), "réplique lue", "répliques lues")
                + (meeting == null ? "" : " pour « " + meeting.subject() + " »") + '.');
        appendGaps(text, gaps);
        if (cues.isEmpty() && !meetingId.isEmpty()) {
            text.append(" Ouvrez la transcription dans Teams, puis redemandez : je lirai ce qu'il"
                    + " aura servi.");
        }
        result.window(ask.window().covering(cues.isEmpty() ? null : cues.get(0).at(),
                        cues.isEmpty() ? null : cues.get(cues.size() - 1).at(), false, true))
                .gaps(gaps)
                .health(book.health())
                .notice(cues.isEmpty() ? "" : scope.announceOnce(meetingId,
                        "la transcription de cette réunion",
                        meeting == null ? meetingId : meeting.subject()))
                .with("firstUse", firstUse())
                .text(text.toString());
        return ToolOutcome.ok(result.render());
    }

    /**
     * L'enregistrement d'une réunion : <b>où il est, et ce que cet outil ne fait pas</b>.
     *
     * <p><b>Il ne télécharge pas les octets, et il le dit</b> (arbitrage A5). L'adresse signée qui
     * permettrait de les chercher est <b>retirée par construction</b> en SF-87-02 — l'adresse perd sa
     * chaîne de requête à l'entrée, « un jeton qui n'entre jamais ne peut pas sortir ». Rouvrir ce
     * garde-fou pour télécharger une vidéo serait rouvrir une décision de sécurité au profit d'une
     * commodité. L'acquisition des octets appartient à F-91, qui télécharge son outillage au premier
     * usage (D3).</p>
     *
     * <p>Ce que l'outil ne fait pas est écrit dans le <b>résultat</b>, pas seulement dans une note :
     * l'agent ne doit pas pouvoir croire qu'un fichier existe.</p>
     */
    private ToolOutcome meetingRecording(JsonNode input) {
        Refusal refusal = refusalIfUnavailable(MEETING_RECORDING);
        if (refusal != null) {
            return refusal.outcome();
        }
        BrowserLink link = link();
        TeamsLedger book = ledger();
        TeamsAsk ask = TeamsAsk.standard(null);
        List<TeamsGap> harvested = new TeamsHarvester(link, book,
                new PageGestures(link, sleeper)).harvestInPlace(ask.window());

        String meetingId = TeamsAsk.text(input, "meeting_id", "meetingId", "id");
        TeamsMeeting meeting = book.meeting(meetingId);

        TeamsToolResult result = new TeamsToolResult(MEETING_RECORDING,
                session.adapter().version(), TeamsLinkState.LINKED);
        result.with("meetingId", meetingId);
        result.json().put("downloaded", false);
        result.with("destination",
                "sur cette machine, dans le dossier de travail du volet Teams — il est créé au"
                        + " premier enregistrement, jamais avant");
        result.with("whyNotDownloaded",
                "Les octets ne sont pas rapatriés ici : l'adresse signée qui permettrait d'aller les"
                        + " chercher est retirée à l'entrée de la liaison (SF-87-02), et ce"
                        + " garde-fou ne se rouvre pas pour une commodité. L'enregistrement reste"
                        + " sur la machine, et son acquisition est le travail de l'enregistrement"
                        + " local.");
        List<TeamsGap> gaps = new ArrayList<>(harvested);
        if (meetingId.isEmpty()) {
            gaps.add(TeamsGap.of(TeamsGapKind.MISSING_FIELD, "enregistrement", "meeting_id"));
        } else if (meeting == null) {
            gaps.add(TeamsGap.of(TeamsGapKind.NOTHING_OBSERVED, meetingId,
                    "cette réunion n'a pas été servie par Teams depuis le rattachement"));
        }
        if (meeting != null) {
            TeamsViews.meeting(result.put("meeting"), meeting);
            result.json().put("available", meeting.recorded());
            result.with("webUrl", meeting.webUrl());
        } else {
            result.json().putNull("available");
        }

        StringBuilder text = new StringBuilder();
        if (meeting == null) {
            text.append("Je ne connais pas cette réunion.");
        } else if (meeting.recorded()) {
            text.append("« ").append(meeting.subject())
                    .append(" » annonce un enregistrement. Je ne l'ai PAS téléchargé.");
        } else {
            text.append("« ").append(meeting.subject())
                    .append(" » n'annonce aucun enregistrement.");
        }
        appendGaps(text, gaps);
        text.append(' ').append(result.json().path("whyNotDownloaded").asText(""));
        result.window(ask.window()).gaps(gaps).health(book.health())
                .with("firstUse", firstUse()).text(text.toString());
        return ToolOutcome.ok(result.render());
    }


    // ------------------------------------------------------------------ F-90 : les captures

    /**
     * <b>Démarre</b> l'extraction et l'alignement des captures d'un enregistrement
     * (F-90 / SF-90-03).
     *
     * <p><b>Traitement lourd, donc asynchrone</b> — la règle de {@code CLAUDE.md}, sur le modèle
     * d'{@code OcrPollingWorker}. Cet outil <b>rend la main tout de suite</b> avec un identifiant de
     * travail ; le décodage tourne ailleurs et <b>dit où il en est</b> dans le fil, comme une
     * commande longue.</p>
     *
     * <p><b>D1 s'applique, et plus lourdement qu'ailleurs</b> : une capture est plus indiscrète
     * qu'une phrase. La transcription dit ce qui a été <b>dit</b> ; les captures montrent ce qui
     * était <b>visible</b>. L'annonce le nomme, une fois par réunion.</p>
     */
    private ToolOutcome meetingMoments(JsonNode input, ToolContext context) {
        // F-91 / SF-91-03 — un enregistrement LOCAL n'a pas besoin de la liaison au navigateur :
        // sa vidéo, sa transcription et son origine du temps viennent de cette machine, pas de
        // Teams. Exiger le rattachement ici refuserait un compte rendu qui n'a rien à demander à
        // Teams — une friction gratuite, et les frictions gratuites usent celle qui compte.
        String askedCapture = TeamsAsk.text(input, "capture_id", "captureId");
        if (askedCapture.isEmpty()) {
            Refusal refusal = refusalIfUnavailable(MEETING_MOMENTS);
            if (refusal != null) {
                return refusal.outcome();
            }
        } else if (!enabled) {
            return unavailable(MEETING_MOMENTS, TeamsLinkState.BROWSER_NOT_DETECTED, disabledReason,
                    "Le volet Teams est désactivé sur cette machine.");
        }
        TeamsToolResult result = new TeamsToolResult(MEETING_MOMENTS, session.adapter().version(),
                TeamsLinkState.LINKED);
        if (momentsWorker == null) {
            return momentsUnavailable(result,
                    "Le traitement des captures n'est pas monté sur ce poste.");
        }
        // F-91 / SF-91-03 — LE POINT OÙ L'ENREGISTREMENT LOCAL REJOINT LE CHEMIN EXISTANT.
        // Une capture locale apporte trois choses que Teams n'apporte pas : sa vidéo, sa
        // transcription (produite ici), et surtout SON ORIGINE DU TEMPS — le seul cas du volet où
        // l'alignement ne repose sur aucune hypothèse, puisque c'est NOUS qui avons démarré.
        CaptureRecord fromCapture = askedCapture.isEmpty() || capture == null
                ? null : capture.find(askedCapture).orElse(null);
        if (!askedCapture.isEmpty() && fromCapture == null) {
            return momentsUnknownCapture(result, askedCapture);
        }
        if (fromCapture != null && !fromCapture.isOver()) {
            return momentsCaptureStillRunning(result, fromCapture);
        }

        String rawVideo = fromCapture != null ? fromCapture.video()
                : TeamsAsk.text(input, "video", "video_path", "path");
        if (rawVideo.isEmpty()) {
            return momentsMissingVideo(result);
        }
        java.nio.file.Path video = java.nio.file.Path.of(rawVideo).toAbsolutePath().normalize();

        String meetingId = TeamsAsk.text(input, "meeting_id", "meetingId");
        TeamsLedger book = ledger();
        TeamsMeeting meeting = meetingId.isEmpty() ? null : book.meeting(meetingId);
        List<TeamsTranscriptCue> cues;
        MomentTimeline timeline;
        String subject;
        if (fromCapture != null) {
            cues = transcription == null ? List.of() : transcription.cuesOf(fromCapture.id());
            timeline = MomentTimeline.given(fromCapture.startedAt());
            subject = fromCapture.subject().isEmpty()
                    ? (meeting == null ? "" : meeting.subject()) : fromCapture.subject();
        } else {
            cues = meetingId.isEmpty() ? List.of() : book.transcriptOf(meetingId);
            timeline = timelineOf(input, meeting);
            subject = meeting == null ? "" : meeting.subject();
        }
        MomentsWorker.Request request = new MomentsWorker.Request(video,
                TeamsAsk.text(input, "workspace_id", "workspaceId"), cues, timeline,
                TeamsAsk.number(input, 0, "scene_threshold", "sceneThreshold") / 1000d,
                subject,
                "true".equalsIgnoreCase(TeamsAsk.text(input, "restart")));

        MomentsJob job = momentsWorker.startOrResume(request,
                message -> context.stream("stdout", message + System.lineSeparator()));
        return ToolOutcome.ok(renderJob(result, job, book,
                scope.announceOnce("moments:" + (meetingId.isEmpty() ? rawVideo : meetingId),
                        "la transcription ET LES IMAGES de cet enregistrement — une capture est "
                                + "plus indiscrète qu'une phrase : la transcription dit ce qui a "
                                + "été DIT, les captures montrent ce qui était VISIBLE, y compris "
                                + "un tableau de bord avec des noms de clients ou une messagerie "
                                + "ouverte à côté. Seules les 20 à 60 images retenues remontent ; "
                                + "la vidéo, l'audio et les fichiers bruts restent sur cette "
                                + "machine, et ce qui remonte vit avec le compte rendu et sera "
                                + "supprimé avec lui",
                        meeting == null ? rawVideo : meeting.subject())));
    }

    /** Où en est un travail de captures, et — quand il est fini — <b>ses moments</b>. */
    private ToolOutcome momentsStatus(JsonNode input) {
        Refusal refusal = refusalIfUnavailable(MOMENTS_STATUS);
        if (refusal != null) {
            return refusal.outcome();
        }
        TeamsToolResult result = new TeamsToolResult(MOMENTS_STATUS, session.adapter().version(),
                TeamsLinkState.LINKED);
        if (momentsWorker == null) {
            return momentsUnavailable(result,
                    "Le traitement des captures n'est pas monté sur ce poste.");
        }
        String jobId = TeamsAsk.text(input, "job_id", "jobId");
        if (jobId.isEmpty()) {
            String rawVideo = TeamsAsk.text(input, "video", "video_path", "path");
            if (rawVideo.isEmpty()) {
                return momentsMissingVideo(result);
            }
            jobId = MomentsJobStore.idFor(
                    java.nio.file.Path.of(rawVideo).toAbsolutePath().normalize());
        }
        MomentsJob job = momentsWorker.find(jobId).orElse(null);
        if (job == null) {
            // Zéro moment ET un manque nommé : une réponse vide se lirait « rien trouvé ».
            result.with("jobId", jobId);
            result.array("moments");
            result.window(null)
                    .gaps(List.of(TeamsGap.of(TeamsGapKind.NOTHING_OBSERVED, "travail " + jobId,
                            "aucun travail de captures ne porte cet identifiant sur cette machine")))
                    .health(ledger().health())
                    .with("firstUse", firstUse())
                    .text("Je ne connais aucun travail de captures sous cet identifiant. "
                            + "Redemandez-le en me donnant le chemin de l'enregistrement : "
                            + "l'identifiant en est tiré, je le retrouverai.");
            return ToolOutcome.ok(result.render());
        }
        return ToolOutcome.ok(renderJob(result, job, ledger(), ""));
    }

    /**
     * L'origine du temps de la vidéo (SF-90-02) : l'instant donné, sinon le début de réunion
     * observé — et <b>rien</b> sinon. Ce « rien » fera échouer le travail en nommant les deux
     * remèdes : on ne devine pas une origine, parce qu'un alignement faux est silencieusement faux.
     */
    private static MomentTimeline timelineOf(JsonNode input, TeamsMeeting meeting) {
        String raw = TeamsAsk.text(input, "video_started_at", "videoStartedAt");
        MomentTimeline timeline = MomentTimeline.unknown();
        if (!raw.isEmpty()) {
            try {
                timeline = MomentTimeline.given(java.time.Instant.parse(raw));
            } catch (RuntimeException e) {
                timeline = MomentTimeline.unknown();
            }
        }
        if (!timeline.isKnown()) {
            timeline = MomentTimeline.fromMeeting(meeting);
        }
        long offset = TeamsAsk.number(input, 0, "offset_seconds", "offsetSeconds");
        return offset == 0 ? timeline : timeline.shiftedBy(offset);
    }

    /** Le rendu d'un travail : son étape, ses compteurs, ses moments, et ce qui manque. */
    private String renderJob(TeamsToolResult result, MomentsJob job, TeamsLedger book,
            String notice) {
        result.with("jobId", job.id());
        result.with("phase", job.phase().name());
        result.with("phaseLabel", job.phase().label());
        result.with("subject", job.subject());
        result.json().put("done", job.isOver());
        result.json().put("framesExamined", job.extracted());
        result.json().put("framesKept", job.kept());
        result.json().put("imagesUploaded", job.uploaded());
        result.json().put("imagesRefused", job.uploadRefused());
        result.with("failure", job.failure());
        result.with("remedy", job.remedy());
        result.with("timeline", job.timeline());
        ArrayNode items = result.array("moments");
        for (MomentsJob.Moment moment : job.moments()) {
            ObjectNode entry = items.addObject();
            entry.put("at", moment.at());
            entry.put("offsetSeconds", moment.offsetSeconds());
            entry.put("quote", moment.quote());
            entry.put("speaker", moment.speaker());
            // Un IDENTIFIANT, jamais des octets : soixante images en base64 traverseraient le
            // modèle pour rien, et coûteraient plus cher que tout le reste de la fonctionnalité.
            entry.put("imageId", moment.imageId());
            entry.put("otherCues", moment.otherCues());
        }
        result.window(null)
                .gaps(job.gaps())
                .health(book.health())
                .notice(notice)
                .with("firstUse", firstUse())
                .text(job.describe());
        return result.render();
    }

    private ToolOutcome momentsUnavailable(TeamsToolResult result, String sentence) {
        result.array("moments");
        result.window(null)
                .gaps(List.of(TeamsGap.of(TeamsGapKind.NOTHING_OBSERVED, "captures",
                        "le traitement des captures n'est pas disponible sur ce poste")))
                .health(TeamsHealth.full(0))
                .with("firstUse", firstUse())
                .text(sentence + " Rien n'a été extrait, et rien n'est remonté.");
        return ToolOutcome.ok(result.render());
    }

    /** Un identifiant de capture qu'on ne connaît pas : on refuse, on n'invente pas de fichier. */
    private ToolOutcome momentsUnknownCapture(TeamsToolResult result, String captureId) {
        result.array("moments");
        result.window(null)
                .gaps(List.of(TeamsGap.of(TeamsGapKind.NOTHING_OBSERVED, "capture " + captureId,
                        "aucun enregistrement local ne porte cet identifiant sur cette machine")))
                .health(TeamsHealth.full(0))
                .with("firstUse", firstUse())
                .text("Je ne connais aucun enregistrement local sous l'identifiant « " + captureId
                        + " ». Demandez l'état des captures : celles de cette machine y sont, avec "
                        + "leurs identifiants.");
        return ToolOutcome.ok(result.render());
    }

    /**
     * Une capture <b>encore en cours</b>. On n'extrait pas d'images d'un fichier en train d'être
     * écrit : le résultat serait tronqué au moment exact où on a regardé, et il aurait l'air complet.
     */
    private ToolOutcome momentsCaptureStillRunning(TeamsToolResult result, CaptureRecord record) {
        result.array("moments");
        result.with("captureId", record.id());
        result.window(null)
                .gaps(List.of(TeamsGap.of(TeamsGapKind.NOTHING_OBSERVED, "capture " + record.id(),
                        "cet enregistrement tourne encore : son fichier est en cours d'écriture")))
                .health(TeamsHealth.full(0))
                .with("firstUse", firstUse())
                .text("Cet enregistrement tourne encore. Arrêtez-le d'abord — je n'extrais pas "
                        + "d'images d'un fichier en cours d'écriture : le résultat serait tronqué "
                        + "au moment où j'ai regardé, en ayant l'air complet.");
        return ToolOutcome.ok(result.render());
    }

    private ToolOutcome momentsMissingVideo(TeamsToolResult result) {
        result.array("moments");
        result.window(null)
                .gaps(List.of(TeamsGap.of(TeamsGapKind.MISSING_FIELD, "captures", "video")))
                .health(TeamsHealth.full(0))
                .with("firstUse", firstUse())
                .text("Donnez-moi le chemin de l'enregistrement sur cette machine (« video »). "
                        + "Je ne le télécharge pas depuis Teams : l'adresse signée qui le "
                        + "permettrait est retirée à l'entrée de la liaison, et ce garde-fou ne se "
                        + "rouvre pas pour une commodité.");
        return ToolOutcome.ok(result.render());
    }

    // ------------------------------------------------------------------ F-91 : la capture locale

    /**
     * <b>Démarre un enregistrement local</b> (F-91 / SF-91-02).
     *
     * <h2>Le seul outil du volet qui CRÉE</h2>
     *
     * <p>Tous les autres relisent ce que Teams a servi. Celui-ci fabrique un artefact — et les
     * participants ne le sauront pas, là où Teams affiche un bandeau quand c'est lui qui enregistre.
     * D'où la seule différence de forme du catalogue : <b>cet outil refuse</b>, et il refuse pour
     * une raison qui n'est pas technique.</p>
     *
     * <h2>Il ne demande PAS la liaison au navigateur</h2>
     *
     * <p>Volontairement : capturer son propre écran n'a rien à voir avec Teams web. Refuser une
     * démo parce que le navigateur n'est pas rattaché serait une friction gratuite, et les frictions
     * gratuites usent celle qui compte.</p>
     */
    private ToolOutcome captureStart(JsonNode input) {
        TeamsToolResult result = new TeamsToolResult(CAPTURE_START, adapterVersion(),
                TeamsLinkState.LINKED);
        if (!enabled || capture == null) {
            return captureUnavailable(result);
        }
        LocalCapture.Request request = new LocalCapture.Request(
                TeamsAsk.text(input, "purpose", "usage"),
                TeamsAsk.flag(input, "participants_informed", "participantsInformed"),
                !Boolean.FALSE.equals(TeamsAsk.flag(input, "audio")),
                identity(TeamsAsk.text(input, "identity", "recorded_by")),
                TeamsAsk.text(input, "subject", "meeting_subject"),
                TeamsAsk.text(input, "screen_device", "screenDevice"),
                TeamsAsk.text(input, "audio_device", "audioDevice"));
        CaptureRecord record;
        try {
            record = capture.start(request);
        } catch (CaptureRefusedException refused) {
            return captureRefused(result, refused);
        }
        renderCapture(result, record);
        // D1, appliqué à ce qui CRÉE plutôt qu'à ce qui relit : la portée nomme ce qui sera
        // enregistré et où cela ira — une fois, pour cette capture.
        result.notice(scope.announceOnce("capture:" + record.id(),
                "TOUT CE QUI PASSERA À L'ÉCRAN de ce poste et tout ce qui s'y dira, pendant que "
                        + "l'enregistrement tourne — y compris ce que vous n'aviez pas l'intention "
                        + "de montrer. La vidéo et l'audio RESTENT sur cette machine ; seuls le "
                        + "compte rendu et les images retenues remonteront, et ils vivront avec lui",
                record.subject().isEmpty() ? record.purpose().label() : record.subject()));
        result.text(record.describe(null) + System.lineSeparator() + capture.ceiling().sentence()
                + System.lineSeparator() + CaptureConsent.NOT_GUARANTEED);
        return ToolOutcome.ok(result.render());
    }

    /**
     * <b>Arrête</b> l'enregistrement local (F-91 / SF-91-02), et <b>démarre sa transcription</b>
     * (F-91 / SF-91-03). Sans identifiant : celui qui tourne.
     *
     * <p>La transcription démarre <b>sans qu'on la demande</b>, et c'est délibéré : une capture sans
     * transcription ne sert à rien — ni compte rendu, ni moments —, et faire attendre un tour de plus
     * ferait perdre des minutes à quelqu'un qui vient de raccrocher. Elle est <b>asynchrone</b> :
     * cet outil rend la main tout de suite.</p>
     */
    private ToolOutcome captureStop(JsonNode input, ToolContext context) {
        TeamsToolResult result = new TeamsToolResult(CAPTURE_STOP, adapterVersion(),
                TeamsLinkState.LINKED);
        if (!enabled || capture == null) {
            return captureUnavailable(result);
        }
        CaptureRecord record;
        try {
            record = capture.stop(TeamsAsk.text(input, "capture_id", "captureId", "id"));
        } catch (CaptureRefusedException refused) {
            return captureRefused(result, refused);
        }
        StringBuilder text = new StringBuilder(record.describe(null));
        if (record.state() == CaptureRecord.State.TERMINEE) {
            text.append(System.lineSeparator()).append(startTranscription(record, context));
        }
        renderCapture(result, record);
        renderTranscription(result, record.id());
        result.text(text.toString());
        return ToolOutcome.ok(result.render());
    }

    /**
     * Démarre la transcription et rend la phrase qui l'annonce — ou celle qui dit <b>pourquoi il n'y
     * en aura pas</b>. Un silence se lirait « le compte rendu arrive », et il n'arriverait jamais.
     */
    private String startTranscription(CaptureRecord record, ToolContext context) {
        if (transcription == null) {
            return "La transcription locale n'est pas montée sur ce poste : cet enregistrement "
                    + "n'aura pas de transcription, et donc pas de compte rendu de ce qui s'est "
                    + "dit. La vidéo, elle, est bien là.";
        }
        TranscriptionJob job = transcription.startOrResume(record,
                message -> context.stream("stdout", message + System.lineSeparator()));
        if (job.isOver()) {
            return job.describe();
        }
        return "Je transcris maintenant cet enregistrement SUR CETTE MACHINE : ni la vidéo ni "
                + "l'audio n'en sortiront, seulement le texte. C'est long — redemandez l'état de la "
                + "capture pour savoir où j'en suis, et ne concluez pas avant.";
    }

    /**
     * Ce que la transcription a produit, à côté de la capture : ses répliques, son étape, et
     * <b>ce qu'elle n'a pas pu faire</b> — le locuteur inconnu y figure toujours.
     */
    private void renderTranscription(TeamsToolResult result, String captureId) {
        if (transcription == null) {
            result.with("transcription", "ABSENTE");
            return;
        }
        TranscriptionJob job = transcription.find(captureId).orElse(null);
        if (job == null) {
            return;
        }
        result.with("transcription", job.phase().name());
        result.with("transcriptionLabel", job.phase().label());
        result.json().put("transcriptionDone", job.isOver());
        result.with("transcriptFile", job.file());
        result.with("transcriptionFailure", job.failure());
        result.with("transcriptionRemedy", job.remedy());
        ArrayNode cues = result.array("cues");
        job.cues().forEach(cue -> TeamsViews.cue(cues, cue));
        if (!job.gaps().isEmpty()) {
            result.gaps(mergeGaps(result, job.gaps()));
        }
    }

    /**
     * Les manques de la capture <b>et</b> ceux de la transcription, dans le même endroit. Deux listes
     * séparées feraient qu'on en lirait une seule.
     */
    private static List<TeamsGap> mergeGaps(TeamsToolResult result, List<TeamsGap> extra) {
        List<TeamsGap> merged = new ArrayList<>();
        for (JsonNode gap : result.json().path("gaps")) {
            TeamsGapKind kind;
            try {
                kind = TeamsGapKind.valueOf(gap.path("kind").asText(""));
            } catch (IllegalArgumentException | NullPointerException e) {
                continue;
            }
            merged.add(new TeamsGap(kind, gap.path("where").asText(""),
                    gap.path("detail").asText(""), gap.path("count").asInt(1)));
        }
        merged.addAll(extra);
        return merged;
    }

    /**
     * <b>Où en est l'enregistrement local</b> (F-91 / SF-91-02), et ceux d'avant.
     *
     * <p>Sans identifiant, il rend celui <b>qui tourne</b> : c'est ce qui permet de l'arrêter quand
     * l'agent a changé de tour et ne se souvient plus de rien.</p>
     */
    private ToolOutcome captureStatus(JsonNode input) {
        TeamsToolResult result = new TeamsToolResult(CAPTURE_STATUS, adapterVersion(),
                TeamsLinkState.LINKED);
        if (!enabled || capture == null) {
            return captureUnavailable(result);
        }
        String id = TeamsAsk.text(input, "capture_id", "captureId", "id");
        CaptureRecord record = id.isEmpty() ? capture.current().orElse(null)
                : capture.find(id).orElse(null);
        ArrayNode previous = result.array("captures");
        for (CaptureRecord known : capture.all()) {
            ObjectNode entry = previous.addObject();
            entry.put("captureId", known.id());
            entry.put("state", known.state().name());
            entry.put("purpose", known.purpose().name());
            entry.put("startedAt", known.startedAt() == null ? "" : known.startedAt().toString());
            entry.put("video", known.video());
        }
        if (record == null) {
            // Zéro capture ET un manque nommé : une réponse vide se lirait « tout va bien ».
            result.window(null)
                    .gaps(List.of(TeamsGap.of(TeamsGapKind.NOTHING_OBSERVED,
                            id.isEmpty() ? "enregistrement local" : "capture " + id,
                            id.isEmpty() ? "aucun enregistrement local ne tourne sur cette machine"
                                    : "aucun enregistrement local ne porte cet identifiant")))
                    .health(TeamsHealth.full(0))
                    .with("firstUse", firstUse())
                    .text(id.isEmpty()
                            ? "Aucun enregistrement local ne tourne sur cette machine."
                            : "Je ne connais aucun enregistrement local sous cet identifiant.");
            return ToolOutcome.ok(result.render());
        }
        renderCapture(result, record);
        renderTranscription(result, record.id());
        result.text(record.describe(null));
        return ToolOutcome.ok(result.render());
    }

    /**
     * Ce qu'un résultat de capture porte, toujours : l'identifiant, l'état, <b>le filigrane tel
     * qu'il est incrusté</b>, et <b>la mention à poser en tête du compte rendu</b>.
     *
     * <p>Les deux derniers ne sont pas décoratifs : ils sont les deux autres endroits où la trace
     * voyage. L'image en porte une, le compte rendu doit porter l'autre.</p>
     */
    private void renderCapture(TeamsToolResult result, CaptureRecord record) {
        result.with("captureId", record.id());
        result.with("state", record.state().name());
        result.with("stateLabel", record.state().label());
        result.with("purpose", record.purpose().name());
        result.json().put("running", !record.isOver());
        result.json().put("participantsInformed", record.participantsInformed());
        result.json().put("audio", record.audio());
        result.with("startedAt", record.startedAt() == null ? "" : record.startedAt().toString());
        result.with("stoppedAt", record.stoppedAt() == null ? "" : record.stoppedAt().toString());
        result.with("elapsed", CaptureRecord.clock(record.elapsed(null)));
        result.with("video", record.video());
        result.json().put("bytes", record.bytes());
        result.with("watermark", record.watermark());
        result.with("recordingNotice", record.mention());
        result.with("devices", record.devices());
        result.with("subject", record.subject());
        result.with("failure", record.failure());
        result.with("remedy", record.remedy());
        result.window(null).gaps(record.gaps()).health(TeamsHealth.full(0))
                .with("firstUse", firstUse());
    }

    /**
     * <b>Un refus rendu comme un SUCCÈS d'outil</b> — règle de forme n° 1 du volet.
     *
     * <p>Une erreur d'outil ferait dire à l'agent « je n'ai pas réussi », là où il faut dire
     * « confirmez que vous avez prévenu les participants, et voici pourquoi ». Le refus porte donc
     * son code, sa phrase et son remède, et l'agent les répète.</p>
     */
    private ToolOutcome captureRefused(TeamsToolResult result, CaptureRefusedException refused) {
        result.with("refused", refused.code());
        result.json().put("running", false);
        result.with("remedy", refused.remedy());
        result.window(null)
                .gaps(List.of(TeamsGap.of(TeamsGapKind.NOTHING_OBSERVED, "enregistrement local",
                        "rien n'a été enregistré : " + refused.getMessage())))
                .health(TeamsHealth.full(0))
                .with("firstUse", firstUse())
                .text(refused.sentence());
        return ToolOutcome.ok(result.render());
    }

    /** Ce poste ne sait pas enregistrer. Il le <b>dit</b> ; il ne fait pas semblant. */
    private ToolOutcome captureUnavailable(TeamsToolResult result) {
        result.json().put("running", false);
        result.window(null)
                .gaps(List.of(TeamsGap.of(TeamsGapKind.NOTHING_OBSERVED, "enregistrement local",
                        "l'enregistrement local n'est pas monté sur ce poste")))
                .health(TeamsHealth.full(0))
                .with("firstUse", firstUse())
                .text((enabled
                        ? "L'enregistrement local n'est pas disponible sur ce poste."
                        : disabledReason)
                        + " Rien n'a été enregistré, et rien n'est remonté.");
        return ToolOutcome.ok(result.render());
    }

    /**
     * Qui enregistre, pour le filigrane. L'identité <b>proposée par l'agent</b> n'est retenue que si
     * elle est donnée ; sinon on prend celle du poste. Elle n'est jamais vide — un filigrane anonyme
     * n'est pas un filigrane.
     */
    private static String identity(String asked) {
        if (asked != null && !asked.isBlank()) {
            return asked.strip();
        }
        String user = System.getProperty("user.name", "");
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            host = System.getenv("COMPUTERNAME");
        }
        String machine = host == null || host.isBlank() ? "" : "@" + host.strip();
        return user.isBlank() ? "poste inconnu" : user.strip() + machine;
    }

    private String adapterVersion() {
        return enabled ? session.adapter().version() : "";
    }

    // ------------------------------------------------------------------ plomberie

    /**
     * Une liaison qu'on n'a pas pu établir : <b>succès</b> d'outil portant l'état et le remède.
     * {@code null} quand tout va bien.
     */
    private Refusal refusalIfUnavailable(String tool) {
        if (!enabled) {
            return new Refusal(unavailable(tool, TeamsLinkState.BROWSER_NOT_DETECTED,
                    disabledReason, "Le volet Teams est désactivé sur cette machine."));
        }
        try {
            link();
        } catch (BrowserLinkException e) {
            return new Refusal(unavailable(tool, TeamsLinkState.BROWSER_NOT_DETECTED,
                    e.getMessage(), "Le navigateur du poste n'est pas relié : rien n'a été lu."));
        } catch (RuntimeException e) {
            return new Refusal(unavailable(tool, TeamsLinkState.BROWSER_NOT_DETECTED, "",
                    "La liaison au navigateur n'a pas abouti sur cette machine."));
        }
        return null;
    }

    private ToolOutcome unavailable(String tool, TeamsLinkState state, String remedy,
            String sentence) {
        TeamsToolResult result = new TeamsToolResult(tool,
                enabled ? session.adapter().version() : "", state);
        result.with("remedy", remedy)
                .window(null)
                .gaps(List.of(TeamsGap.of(TeamsGapKind.NOTHING_OBSERVED, "liaison Teams",
                        "la liaison au navigateur n'est pas établie")))
                .health(TeamsHealth.full(0))
                .with("firstUse", firstUse())
                .text(sentence + (remedy == null || remedy.isBlank()
                        ? "" : System.lineSeparator() + remedy));
        return ToolOutcome.ok(result.render());
    }

    private BrowserLink link() {
        return session.link();
    }

    /** Le registre vit avec la liaison — et repart avec elle : rien n'est mis en cache (D2). */
    private synchronized TeamsLedger ledger() {
        if (ledger == null) {
            ledger = new TeamsLedger(session.adapter());
        }
        return ledger;
    }

    private synchronized String firstUse() {
        if (firstUseSaid || !enabled) {
            return "";
        }
        firstUseSaid = true;
        return session.notice().text();
    }

    /** La phrase du cadrage : « n éléments lus, … ». */
    private static String sentence(int count, String noun, List<TeamsGap> gaps, TeamsLedger book,
            String query) {
        StringBuilder text = new StringBuilder();
        text.append(count).append(' ').append(noun).append(count > 1 ? "s" : "")
                .append(count > 1 ? " trouvées" : " trouvée");
        if (!query.isBlank()) {
            text.append(" pour « ").append(query).append(" »");
        }
        text.append('.');
        if (!gaps.isEmpty()) {
            List<String> described = new ArrayList<>();
            gaps.forEach(gap -> described.add(gap.describe()));
            text.append(" Ce qui n'a pas pu être lu : ").append(String.join(" ; ", described))
                    .append('.');
        }
        if (book.health().verdict() != TeamsHealthVerdict.FULL) {
            text.append(' ').append(book.health().describe());
        }
        return text.toString();
    }

    /** « 3 mentions » / « 1 mention » : la phrase doit se lire, pas se décoder. */
    private static String count(int howMany, String singular, String plural) {
        return howMany + " " + (howMany > 1 ? plural : singular);
    }

    /** Ce qui n'a pas pu être lu, à côté du résultat — jamais après lui, jamais à part. */
    private static void appendGaps(StringBuilder text, List<TeamsGap> gaps) {
        if (gaps.isEmpty()) {
            return;
        }
        List<String> described = new ArrayList<>();
        gaps.forEach(gap -> described.add(gap.describe()));
        text.append(" Ce qui n'a pas pu être lu : ").append(String.join(" ; ", described))
                .append('.');
    }

    private static boolean matches(TeamsMeeting meeting, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String needle = fold(query);
        if (fold(meeting.subject()).contains(needle) || fold(meeting.id()).contains(needle)) {
            return true;
        }
        return meeting.participants().stream()
                .anyMatch(participant -> fold(participant.label()).contains(needle));
    }

    private static boolean outside(TeamsMeeting meeting, TeamsReadWindow window) {
        if (window == null || meeting.startedAt() == null) {
            return false;
        }
        if (window.requestedFrom() != null && meeting.startedAt().isBefore(window.requestedFrom())) {
            return true;
        }
        return window.requestedTo() != null && meeting.startedAt().isAfter(window.requestedTo());
    }

    /** Rapprochement insensible à la casse et aux accents : « francky » trouve « Francky ». */
    private static boolean matches(TeamsConversation conversation, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String needle = fold(query);
        if (fold(conversation.topic()).contains(needle) || fold(conversation.id()).contains(needle)
                || fold(conversation.label()).contains(needle)) {
            return true;
        }
        return conversation.participants().stream()
                .anyMatch(participant -> fold(participant.label()).contains(needle)
                        || fold(participant.email() == null ? "" : participant.email())
                                .contains(needle));
    }

    static String fold(String raw) {
        if (raw == null) {
            return "";
        }
        return Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.FRENCH)
                .strip();
    }

    /** Un refus rendu comme un succès porteur d'un état : c'est la règle de forme n° 1. */
    private record Refusal(ToolOutcome outcome) {
    }
}
