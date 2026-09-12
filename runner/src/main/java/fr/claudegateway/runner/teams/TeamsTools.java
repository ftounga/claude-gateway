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
    public static final String CAPABILITY = "teams";

    /** Le catalogue, dans l'ordre où il est donné à l'agent. */
    public static final List<String> CATALOG = List.of(STATUS, FIND_CONVERSATIONS,
            READ_CONVERSATION, MENTIONS, SEARCH, FIND_MEETINGS, MEETING_TRANSCRIPT,
            MEETING_RECORDING);

    private final ObjectMapper mapper = new ObjectMapper();
    private final TeamsSession session;
    private final TeamsProbe probe;
    private final BrowserLink.Sleeper sleeper;
    private final TeamsScopeNotice scope = new TeamsScopeNotice();
    private final boolean enabled;
    private final String disabledReason;

    private volatile boolean firstUseSaid;
    private TeamsLedger ledger;

    public TeamsTools(TeamsSession session, BrowserLink.Sleeper sleeper) {
        this.session = session;
        this.probe = new TeamsProbe(session.adapter());
        this.sleeper = sleeper;
        this.enabled = true;
        this.disabledReason = "";
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
