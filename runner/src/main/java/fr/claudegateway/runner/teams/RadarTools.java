package fr.claudegateway.runner.teams;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.ToolOutcome;

/**
 * <b>Les appels du Radar au runner</b> (F-100) : ce que la gateway demande au poste pour la synchro du
 * soir. Ce ne sont <b>pas</b> des outils de l'agent — ils ne figurent pas dans le catalogue qu'un tour
 * de conversation reçoit ({@link TeamsTools#CATALOG}).
 *
 * <p>Ils partagent la liaison et le registre d'observation du volet Teams : ce que Teams a servi à un
 * outil de lecture sert aussi à la vérification, et inversement.</p>
 */
final class RadarTools {

    /** La vérification guidée (SF-100-01). */
    static final String VERIFY = "teams_radar_verify";

    /** Le lancement de la synchro du soir (SF-100-02). */
    static final String COLLECT = "teams_radar_collect";

    /** L'annulation de la synchro en cours (SF-100-04). */
    static final String CANCEL = "teams_radar_cancel";

    /** Fenêtre de décodage de la vérification : large, on compte ce que Teams a servi. */
    private static final Duration VERIFY_LOOKBACK = Duration.ofDays(400);

    private final ObjectMapper mapper = new ObjectMapper();
    private final TeamsSession session;
    private final Supplier<TeamsLedger> ledger;
    private final BrowserLink.Sleeper sleeper;
    private final String disabledReason;
    private final RadarSyncAgent agent;

    RadarTools(TeamsSession session, Supplier<TeamsLedger> ledger, BrowserLink.Sleeper sleeper,
            String disabledReason, RadarSyncAgent agent) {
        this.session = session;
        this.ledger = ledger;
        this.sleeper = sleeper;
        this.disabledReason = disabledReason == null ? "" : disabledReason;
        this.agent = agent;
    }

    // ------------------------------------------------------------------ synchro (SF-100-02)

    /** Annule la synchro en cours si c'est celle demandée (SF-100-04). */
    ToolOutcome cancel(com.fasterxml.jackson.databind.JsonNode input) {
        String syncId = input == null ? "" : input.path("sync_id").asText("").strip();
        ObjectNode answer = mapper.createObjectNode();
        answer.put("cancelled", agent != null && agent.cancel(syncId));
        return ToolOutcome.ok(answer.toString());
    }

    /**
     * <b>Lance la synchro</b> demandée par la gateway : le runner accepte et rend la main, le travail
     * tourne en tâche de fond et rend compte par la remontée. Un seul à la fois.
     */
    ToolOutcome collect(com.fasterxml.jackson.databind.JsonNode input) {
        if (session == null || agent == null) {
            ObjectNode refused = mapper.createObjectNode();
            refused.put("accepted", false);
            refused.put("reason", session == null ? "TEAMS_DISABLED" : "NO_UPLINK");
            return ToolOutcome.ok(refused.toString());
        }
        RadarAssignment assignment;
        try {
            assignment = RadarAssignment.from(input);
        } catch (IllegalArgumentException e) {
            return ToolOutcome.error("invalid_input", "Synchro illisible : " + e.getMessage() + ".");
        }
        return ToolOutcome.ok(agent.accept(assignment).toJson(mapper).toString());
    }

    // ------------------------------------------------------------------ vérification (SF-100-01)

    /**
     * <b>Ce que le runner voit</b> de la session Microsoft : session active, conversations, réunions,
     * transcriptions. Des <b>compteurs et des états</b>, jamais un titre, un nom ou une adresse.
     */
    ToolOutcome verify() {
        ObjectNode root = mapper.createObjectNode();
        root.put("tool", VERIFY);
        ObjectNode checks = root.putObject("checks");
        if (session == null) {
            session(checks, false, "TEAMS_DISABLED", disabledReason.isEmpty()
                    ? "Le volet Teams est désactivé sur ce poste." : disabledReason);
            unseen(checks);
            return done(root, checks);
        }
        root.put("adapter", session.adapter().version());
        BrowserLink link;
        try {
            link = session.link();
        } catch (BrowserLinkException e) {
            session(checks, false, stateOf(e.code()), e.getMessage());
            unseen(checks);
            return done(root, checks);
        } catch (RuntimeException e) {
            session(checks, false, "BROWSER_NOT_DETECTED", "La liaison au navigateur n'a pas abouti sur ce poste.");
            unseen(checks);
            return done(root, checks);
        }

        TeamsLedger book = ledger.get();
        Instant now = Instant.now();
        TeamsReadWindow window = new TeamsReadWindow(now.minus(VERIFY_LOOKBACK), now.plus(Duration.ofDays(1)),
                null, null, TeamsReadWindow.DEFAULT_MAX_MESSAGES, false, false);
        try {
            new TeamsHarvester(link, book, new PageGestures(link, sleeper)).harvestInPlace(window);
        } catch (BrowserLinkException e) {
            session(checks, false, stateOf(e.code()), e.getMessage());
            unseen(checks);
            return done(root, checks);
        }

        if (book.bodiesRead() > 0 && book.health().verdict() == TeamsHealthVerdict.NONE) {
            session(checks, false, "TEAMS_CHANGED", "Teams a changé : l'adaptateur ne lit plus ce que le service "
                    + "renvoie. Rien ne sera synchronisé tant qu'il n'aura pas été mis à jour.");
        } else {
            session(checks, true, "LINKED", "Session Microsoft active : le runner observe l'onglet Teams.");
        }

        Set<String> threads = new HashSet<>();
        book.conversations().forEach(conversation -> threads.add(conversation.id()));
        book.messagesOf("", null).forEach(message -> threads.add(message.conversationId()));
        threads.remove("");
        ObjectNode conversations = checks.putObject("conversations");
        conversations.put("ok", !threads.isEmpty());
        conversations.put("count", threads.size());
        conversations.put("sentence", threads.isEmpty()
                ? "Aucune conversation vue : ouvrez un fil dans Teams."
                : threads.size() + " conversation(s) vue(s).");

        int meetingsSeen = book.meetings().size();
        ObjectNode meetings = checks.putObject("meetings");
        meetings.put("ok", meetingsSeen > 0);
        meetings.put("count", meetingsSeen);
        meetings.put("sentence", meetingsSeen == 0
                ? "Aucune réunion vue : ouvrez une réunion passée dans le calendrier de Teams."
                : meetingsSeen + " réunion(s) vue(s).");

        int cues = book.transcriptOf("").size();
        ObjectNode transcripts = checks.putObject("transcripts");
        transcripts.put("ok", cues > 0);
        transcripts.put("count", cues);
        if (cues > 0) {
            transcripts.put("reason", "SEEN");
            transcripts.put("sentence", "Transcription lue (" + cues + " réplique(s)).");
        } else if (link.observer().denied(TeamsPayloadKind.MEETING_TRANSCRIPT) > 0) {
            transcripts.put("reason", "ACCESS_DENIED");
            transcripts.put("sentence", "Accès à la transcription refusé : votre rôle dans la réunion ne "
                    + "l'autorise pas. Le Radar dira que ces réunions n'ont pas pu être lues.");
        } else if (meetingsSeen > 0 && book.meetings().stream().noneMatch(TeamsMeeting::transcriptAvailable)) {
            transcripts.put("reason", "DISABLED_OR_NOT_PRODUCED");
            transcripts.put("sentence", "Aucune des réunions vues n'annonce de transcription : la politique du "
                    + "client la désactive peut-être, ou elle n'a pas été produite. Le Radar l'annoncera au lieu "
                    + "de présenter ces réunions comme vides.");
        } else {
            transcripts.put("reason", "NOT_SEEN");
            transcripts.put("sentence", "Aucune transcription vue : ouvrez l'onglet Transcription d'une réunion "
                    + "passée.");
        }
        return done(root, checks);
    }

    private ToolOutcome done(ObjectNode root, ObjectNode checks) {
        boolean complete = checks.path("session").path("ok").asBoolean(false)
                && checks.path("conversations").path("ok").asBoolean(false)
                && checks.path("meetings").path("ok").asBoolean(false)
                && checks.path("transcripts").path("ok").asBoolean(false);
        root.put("complete", complete);
        return ToolOutcome.ok(root.toString());
    }

    private static void session(ObjectNode checks, boolean ok, String state, String sentence) {
        ObjectNode node = checks.putObject("session");
        node.put("ok", ok);
        node.put("state", state);
        node.put("sentence", sentence == null ? "" : sentence);
    }

    private static void unseen(ObjectNode checks) {
        for (String name : new String[] { "conversations", "meetings", "transcripts" }) {
            ObjectNode node = checks.putObject(name);
            node.put("ok", false);
            node.put("count", 0);
            if ("transcripts".equals(name)) {
                node.put("reason", "NOT_SEEN");
            }
            node.put("sentence", "Rien n'a pu être observé : la session n'est pas reliée.");
        }
    }

    /** L'état de liaison, dit avec les mots de l'indicateur de F-87. */
    static String stateOf(String code) {
        if (BrowserLinkException.NOT_SIGNED_IN.equals(code) || BrowserLinkException.SIGN_IN_REFUSED.equals(code)) {
            return "NOT_SIGNED_IN";
        }
        if (BrowserLinkException.TEAMS_NOT_OPEN.equals(code)) {
            return "TEAMS_NOT_OPEN";
        }
        if (BrowserLinkException.LINK_LOST.equals(code)) {
            return "LINK_LOST";
        }
        return "BROWSER_NOT_DETECTED";
    }
}
