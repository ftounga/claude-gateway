package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>La collecte Teams de la synchro du soir</b> (F-100 / SF-100-03).
 *
 * <h2>Rien à déclarer à l'avance</h2>
 *
 * <p>Correction du PO : un périmètre à coller « signifie déjà savoir quoi suivre ». La collecte
 * <b>découvre</b> les conversations actives dans la liste que Teams charge lui-même, lit <b>seulement le
 * nouveau</b> de chacune (curseur par fil), et l'utilisateur écarte après coup (<i>ignorer ce fil</i>).</p>
 *
 * <h2>Les canaux d'équipe</h2>
 *
 * <p>Souvent très volumineux : seuls les fils où l'utilisateur a <b>écrit, répondu ou été mentionné</b>
 * sont lus ; un canal actif sans tel fil est <b>compté</b> « non lu », et l'utilisateur peut dire <i>lire ce
 * canal</i>.</p>
 *
 * <h2>Les réunions, sans rien écrire</h2>
 *
 * <p>L'onglet est <b>navigué</b> vers le calendrier (gestes gardés de F-108 : domaine vérifié avant
 * émission, jamais une page d'identification), chaque réunion terminée voit son fil affiché le temps
 * d'observer ce que Teams sert, puis la vue est remise. <b>Aucun lien de participation n'est ouvert</b> —
 * ce ne serait plus une lecture.</p>
 *
 * <h2>Ce qui n'a pas été lu est dit</h2>
 *
 * <p>La couverture compte ce qui est lu, partiel, échoué, ignoré, reporté, non lu, sans transcription :
 * une synchro partielle ne se fait jamais passer pour complète.</p>
 */
final class TeamsRadarCollector implements RadarCollector {

    /** Conversations lues au plus par synchro : le reste est reporté, et dit. */
    static final int MAX_CONVERSATIONS = 150;
    /** Réunions examinées au plus par synchro. */
    static final int MAX_MEETINGS = 30;
    /** Fils listés dans la couverture (les non entièrement lus). */
    static final int MAX_LISTED_THREADS = 40;
    static final int MAX_LABEL_CHARS = 80;

    private final ObjectMapper mapper = new ObjectMapper();
    private final TeamsSession session;
    private final Supplier<TeamsLedger> ledger;
    private final BrowserLink.Sleeper sleeper;
    private final Supplier<Instant> clock;
    private final Consumer<PageActions.GestureRecord> journal;
    private final Object lock;

    TeamsRadarCollector(TeamsSession session, Supplier<TeamsLedger> ledger, BrowserLink.Sleeper sleeper,
            Supplier<Instant> clock, Consumer<PageActions.GestureRecord> journal, Object lock) {
        this.session = session;
        this.ledger = ledger;
        this.sleeper = sleeper;
        this.clock = clock == null ? Instant::now : clock;
        this.journal = journal == null ? record -> { } : journal;
        this.lock = lock == null ? new Object() : lock;
    }

    @Override
    public Outcome collect(RadarAssignment assignment, RadarSyncContext context) {
        Coverage coverage = new Coverage(assignment, clock.get());
        try {
            return run(assignment, context, coverage);
        } catch (BrowserLinkException e) {
            return coverage.failed(failureCode(e.code()), sentenceOf(e.code()), e.getMessage());
        }
    }

    private Outcome run(RadarAssignment assignment, RadarSyncContext context, Coverage coverage) {
        Instant now = clock.get();
        Instant floor = assignment.windowFrom();
        Map<String, Instant> cursors = cursors(assignment.input());
        Set<String> ignored = refs(assignment.input(), "ignored");
        Set<String> readChannels = refs(assignment.input(), "read_channels");
        TeamsReadWindow wide = new TeamsReadWindow(oldest(floor, cursors), now.plus(Duration.ofHours(1)), null, null,
                TeamsReadWindow.DEFAULT_MAX_MESSAGES, false, false);

        BrowserLink link;
        TeamsLedger book;
        TeamsHarvester harvester;
        PageGestures gestures;
        List<Planned> planned = new ArrayList<>();
        synchronized (lock) {
            link = session.link();
            // F-108 §4.8 : les cadres et workers des domaines Microsoft sont écoutés aussi (lecteur Stream
            // intégré, service worker) ; hors liste, rien.
            link.observer().observeFrames();
            book = ledger.get();
            gestures = new PageGestures(link, sleeper);
            harvester = new TeamsHarvester(link, book, gestures);
            harvester.harvestInPlace(wide);
            if (book.bodiesRead() > 0 && book.health().verdict() == TeamsHealthVerdict.NONE) {
                return coverage.failed("TEAMS_CHANGED", "Teams a changé : l'adaptateur ne lit plus ce que le service "
                        + "renvoie. Rien n'a été synchronisé.", "Mettez le runner à jour.");
            }

            // ---------------------------------------------------------------- découverte
            List<TeamsConversation> listed = book.conversations();
            if (listed.isEmpty()) {
                // F-89 / SF-89-06 : le réseau n'a rien servi — la liste AFFICHÉE est lue à l'écran.
                // SF-100-10 : mais la synchro navigue SEULE (Chrome managé) et n'atterrit pas sur la vue Chat
                // (la route CONVERSATIONS tombe sur un volet message) — d'où 0 collecté alors que la vérification,
                // partie d'une vue Chat, voit les conversations (prod CAGIP 2026-09-20). On se place donc dans le
                // MÊME contexte que la vérification : ouvrir la vue Chat et ATTENDRE que la liste « mid-nav » soit
                // chargée AVANT de lire. Puis la lecture par les sélecteurs recalés (SF-89-20/21), inchangés.
                TeamsScreenFallback fallback = new TeamsScreenFallback(link, sleeper, journal);
                PageActions nav = new PageActions(link, sleeper, journal);
                String beforeDiscovery = nav.currentUrl();
                TeamsScreenFallback.ChatView chat = fallback.reachChatList(TeamsScreenFallback.MAX_CHAT_LIST_POLLS);
                TeamsScreenFallback.ScreenList onScreen =
                        fallback.list(TeamsScreen.CONVERSATIONS, TeamsRoutes.CHAT);
                if (onScreen.found()) {
                    listed = TeamsScreenFallback.conversations(onScreen.items()).stream()
                            .filter(conversation -> !conversation.id().startsWith("ecran:")).toList();
                }
                if (listed.isEmpty()) {
                    // Le réseau n'a rien servi ET l'écran n'a rien donné (liste jamais chargée, ou vide) : la
                    // source n'est pas « réseau ». Best-effort — la couverture le dit, la collecte ne plante pas.
                    coverage.discoverySource = "aucune";
                } else {
                    coverage.discoverySource = TeamsTools.SOURCE_SCREEN;
                }
                // §4.7 : la vue d'avant la découverte est remise (la synchro ne laisse pas l'onglet ailleurs).
                try {
                    nav.restore(beforeDiscovery);
                } catch (BrowserLinkException e) {
                    // l'onglet a quitté les domaines Microsoft entre-temps : rien à remettre de plus
                }
                // Diagnostic F-132 : la navigation/attente vers la vue Chat, et ce qu'on a fini par lister.
                String state = chat.listPresent() ? "reached"
                        : chat.reached() ? "timeout (liste jamais chargée)" : "non atteinte";
                journal.accept(new PageActions.GestureRecord("radar/chat_view",
                        MicrosoftDomains.hostOf(TeamsRoutes.CHAT), "liste des conversations",
                        state + " ; listed=" + listed.size() + " ; polls=" + chat.polls()));
            }
            coverage.discovery(!listed.isEmpty(), listed.stream().anyMatch(c -> c.lastActivityAt() != null
                    && !c.lastActivityAt().isAfter(floor)), listed.size());
            TeamsParticipant self = book.self();
            for (TeamsConversation conversation : listed) {
                Instant threadFloor = cursors.getOrDefault(conversation.id(), floor);
                if (conversation.lastActivityAt() != null && !conversation.lastActivityAt().isAfter(threadFloor)) {
                    continue; // rien de nouveau depuis le curseur
                }
                coverage.active++;
                if (ignored.contains(conversation.id())) {
                    coverage.ignored++;
                    continue;
                }
                Set<String> threads = null;
                if (conversation.kind() == TeamsConversationKind.CHANNEL && !readChannels.contains(conversation.id())) {
                    threads = userThreads(book, conversation.id(), self, wide);
                    if (threads.isEmpty()) {
                        coverage.unreadChannel(conversation);
                        continue;
                    }
                }
                planned.add(new Planned(conversation, threadFloor, threads));
            }
        }
        if (planned.size() > MAX_CONVERSATIONS) {
            coverage.deferred = planned.size() - MAX_CONVERSATIONS;
            planned = planned.subList(0, MAX_CONVERSATIONS);
        }

        // -------------------------------------------------------------------- lecture
        for (int index = 0; index < planned.size(); index++) {
            if (!context.progress("conversations", index, planned.size())) {
                return coverage.stopped();
            }
            Planned plan = planned.get(index);
            List<RadarExchanges.Line> lines;
            String status;
            String detail;
            synchronized (lock) {
                TeamsReadWindow window = new TeamsReadWindow(plan.floor(), now.plus(Duration.ofHours(1)), null, null,
                        TeamsReadWindow.DEFAULT_MAX_MESSAGES, false, false);
                String shownBefore = gestures.shownConversationId();
                TeamsHarvester.Harvest harvest = harvester.readConversation(plan.conversation().id(), window);
                boolean onScreen = false;
                if (harvest.messages().isEmpty()) {
                    // F-89 / SF-89-06 : fil servi depuis le cache — lu à l'écran, vue remise.
                    TeamsHarvester.Harvest shown = new TeamsScreenFallback(link, sleeper, journal)
                            .thread(gestures, harvest, shownBefore, window);
                    if (shown != null && !shown.messages().isEmpty()) {
                        harvest = shown;
                        onScreen = true;
                        coverage.readOnScreen++;
                    }
                }
                TeamsParticipant self = book.self();
                lines = new ArrayList<>();
                for (TeamsMessage message : harvest.messages()) {
                    if (message.deleted() || message.text().isBlank() || message.sentAt() == null
                            || !message.sentAt().isAfter(plan.floor())) {
                        continue;
                    }
                    if (plan.threads() != null && !plan.threads().contains(message.id())
                            && !plan.threads().contains(message.parentId())) {
                        continue;
                    }
                    lines.add(line(plan.conversation().id(), message, self));
                }
                boolean reached = harvest.gaps().stream().noneMatch(g -> g.kind() == TeamsGapKind.CONVERSATION_NOT_REACHED);
                // Complet : la page a remonté jusqu'au plancher (ou au début du fil), sans plafond ni trou.
                // La fenêtre de la récolte ne suffit pas à le dire : elle ne compte que les messages NOUVEAUX.
                Instant oldestSeen = book.oldestObservedIn(plan.conversation().id());
                boolean reachedFloor = harvest.window().reachedStartOfConversation()
                        || (oldestSeen != null && !oldestSeen.isAfter(plan.floor()));
                boolean complete = reachedFloor && harvest.gaps().stream().noneMatch(g ->
                        g.kind() == TeamsGapKind.CAP_REACHED || g.kind() == TeamsGapKind.SCROLL_EXHAUSTED
                                || g.kind() == TeamsGapKind.PAGINATION_STOPPED);
                status = !reached && lines.isEmpty() ? "FAILED" : complete ? "READ" : "PARTIAL";
                detail = !reached ? "fil non atteint dans la fenêtre Teams"
                        : complete ? "" : "lecture incomplète : " + harvest.window().describe();
                if (onScreen) {
                    detail = detail.isEmpty() ? "lu à l'écran" : detail + " (lu à l'écran)";
                }
            }
            List<RadarExchanges.Chunk> chunks = RadarExchanges.chunks(mapper, "TEAMS_MESSAGE", plan.conversation().id(),
                    plan.conversation().label(), plan.conversation().webUrl(), lines);
            for (RadarExchanges.Chunk chunk : chunks) {
                SubmitResult result = submit(context, chunk, plan.conversation().id(),
                        plan.conversation().kind() == TeamsConversationKind.CHANNEL ? "CHANNEL" : "CONVERSATION");
                if (result == SubmitResult.STOPPED) {
                    return coverage.stopped();
                }
                if (result == SubmitResult.FAILED) {
                    status = "FAILED";
                    detail = "un lot n'a pas pu remonter : ce fil sera relu à la prochaine synchro";
                    break;
                }
                coverage.batches++;
                coverage.messages += chunk.messages();
            }
            coverage.thread(plan.conversation().id(), plan.conversation().label(),
                    plan.conversation().kind() == TeamsConversationKind.CHANNEL ? "CHANNEL" : "CONVERSATION", status, detail);
        }

        // -------------------------------------------------------------------- réunions
        if (!context.progress("meetings", 0, 0)) {
            return coverage.stopped();
        }
        return meetings(context, coverage, link, book, harvester, gestures, floor, cursors, wide, now);
    }

    private Outcome meetings(RadarSyncContext context, Coverage coverage, BrowserLink link, TeamsLedger book,
            TeamsHarvester harvester, PageGestures gestures, Instant floor, Map<String, Instant> cursors,
            TeamsReadWindow wide, Instant now) {
        List<TeamsMeeting> due = new ArrayList<>();
        String before;
        PageActions actions = new PageActions(link, sleeper, journal);
        synchronized (lock) {
            before = actions.currentUrl();
            try {
                actions.navigate(TeamsRoutes.CALENDAR);
                harvester.harvestInPlace(wide);
                coverage.navigation = "OK";
            } catch (BrowserLinkException e) {
                coverage.navigation = "REFUSED";
                coverage.partial = true;
            }
            coverage.calendarServed = !book.meetings().isEmpty();
            for (TeamsMeeting meeting : book.meetings()) {
                Instant end = meeting.endedAt() != null ? meeting.endedAt() : meeting.startedAt();
                Instant meetingFloor = cursors.getOrDefault(meeting.id(), floor);
                if (end != null && end.isAfter(meetingFloor) && end.isBefore(now)) {
                    due.add(meeting);
                }
            }
        }
        coverage.meetingsSeen = due.size();
        if (due.size() > MAX_MEETINGS) {
            coverage.deferred += due.size() - MAX_MEETINGS;
            due = due.subList(0, MAX_MEETINGS);
        }
        try {
            for (int index = 0; index < due.size(); index++) {
                if (!context.progress("meetings", index, due.size())) {
                    return coverage.stopped();
                }
                TeamsMeeting meeting = due.get(index);
                List<RadarExchanges.Line> lines = new ArrayList<>();
                String status;
                boolean transcriptOnScreen = false;
                Boolean downloadBlocked = null;
                synchronized (lock) {
                    long mark = book.cueMark();
                    int deniedBefore = link.observer().denied(TeamsPayloadKind.MEETING_TRANSCRIPT);
                    List<TeamsTranscriptCue> cues = new ArrayList<>(book.transcriptOf(meeting.id()));
                    if (cues.isEmpty() && !meeting.conversationId().isEmpty()) {
                        // Le fil de la réunion — jamais son lien de participation.
                        if (gestures.show(meeting.conversationId())) {
                            harvester.harvestInPlace(wide);
                        }
                        cues.addAll(book.cuesSince(mark));
                    }
                    if (cues.isEmpty() && link.observer().denied(TeamsPayloadKind.MEETING_TRANSCRIPT) == deniedBefore) {
                        // F-89 / SF-89-06 : aucune réplique par le réseau — le panneau Transcription, lu à l'écran.
                        TeamsScreenFallback.ScreenTranscript shown =
                                new TeamsScreenFallback(link, sleeper, journal).transcript(meeting);
                        if (!shown.cues().isEmpty()) {
                            cues.addAll(shown.cues());
                            transcriptOnScreen = true;
                            downloadBlocked = shown.downloadBlocked();
                        }
                    }
                    // Une réunion déjà transcrite ne remonte que ses répliques nouvelles ; sinon, toutes.
                    Instant cueFloor = cursors.get(meeting.id());
                    TeamsParticipant self = book.self();
                    for (TeamsTranscriptCue cue : cues) {
                        if (cue.isReadable() && (cueFloor == null || cue.at().isAfter(cueFloor))) {
                            lines.add(new RadarExchanges.Line(meeting.id() + "/" + cue.at().toEpochMilli() + "/"
                                    + Integer.toHexString((cue.speakerId() + cue.text()).hashCode()), cue.at(),
                                    cue.speakerId(), cue.speakerDisplayName(),
                                    self != null && !cue.speakerId().isEmpty() && cue.speakerId().equals(self.id()),
                                    cue.text(), null));
                        }
                    }
                    status = !lines.isEmpty() ? "TRANSCRIBED"
                            : link.observer().denied(TeamsPayloadKind.MEETING_TRANSCRIPT) > deniedBefore ? "DENIED"
                            : "NO_TRANSCRIPT";
                }
                String detail = switch (status) {
                    case "TRANSCRIBED" -> !transcriptOnScreen ? ""
                            : Boolean.TRUE.equals(downloadBlocked)
                                    ? "transcription lue à l'écran ; téléchargement bloqué : analysée, jamais conservée en entier"
                                    : "transcription lue à l'écran";
                    case "DENIED" -> "accès à la transcription refusé";
                    case "NO_TRANSCRIPT" -> meeting.transcriptAvailable() ? "transcription annoncée mais non servie"
                            : "aucune transcription annoncée (désactivée ou non produite)";
                    default -> "";
                };
                if (!lines.isEmpty()) {
                    lines.sort((a, b) -> a.occurredAt().compareTo(b.occurredAt()));
                    if (transcriptOnScreen) {
                        coverage.transcribedOnScreen++;
                    }
                    if (Boolean.TRUE.equals(downloadBlocked)) {
                        coverage.downloadBlocked++;
                    }
                    for (RadarExchanges.Chunk chunk : RadarExchanges.chunks(mapper, "TEAMS_MEETING", meeting.id(),
                            meeting.subject(), null, lines)) {
                        if (Boolean.TRUE.equals(downloadBlocked)) {
                            // Règle de conformité (cadrage F-87 §9 bis) : le lot le DIT, la gateway l'analyse et
                            // n'en garde que des extraits courts — jamais la transcription entière.
                            chunk.batch().path("exchanges").forEach(exchange ->
                                    ((ObjectNode) exchange).put("downloadBlocked", true));
                        }
                        SubmitResult result = submit(context, chunk, meeting.id(), "MEETING");
                        if (result == SubmitResult.STOPPED) {
                            return coverage.stopped();
                        }
                        if (result == SubmitResult.FAILED) {
                            status = "FAILED";
                            detail = "un lot n'a pas pu remonter : cette réunion sera relue à la prochaine synchro";
                            break;
                        }
                        coverage.batches++;
                        coverage.messages += chunk.messages();
                    }
                }
                coverage.meeting(meeting, status, detail);
            }
        } finally {
            synchronized (lock) {
                try {
                    // La vue de l'utilisateur est remise (§4.7 de F-108) — seulement vers une adresse autorisée.
                    actions.restore(before);
                } catch (BrowserLinkException e) {
                    // rien à remettre de plus : l'onglet a quitté les domaines Microsoft entre-temps
                }
            }
        }
        return coverage.done();
    }

    // ------------------------------------------------------------------------ outils

    private enum SubmitResult { ACCEPTED, FAILED, STOPPED }

    private SubmitResult submit(RadarSyncContext context, RadarExchanges.Chunk chunk, String ref, String kind) {
        ObjectNode body = mapper.createObjectNode();
        body.set("batch", chunk.batch());
        ObjectNode cursor = body.putArray("cursors").addObject();
        cursor.put("ref", ref);
        cursor.put("kind", kind);
        cursor.put("at", chunk.newest().toString());
        try {
            JsonNode answer = context.submit(body);
            if (context.stopped() || "STOPPED".equals(answer.path("status").asText(""))) {
                return SubmitResult.STOPPED;
            }
            return SubmitResult.ACCEPTED;
        } catch (IOException e) {
            return SubmitResult.FAILED;
        }
    }

    /** Les fils d'un canal où l'utilisateur a écrit, répondu ou été mentionné. */
    private static Set<String> userThreads(TeamsLedger book, String channelId, TeamsParticipant self,
            TeamsReadWindow window) {
        Set<String> threads = new HashSet<>();
        for (TeamsMentionEvent event : book.mentionsIn(window)) {
            if (channelId.equals(event.conversationId())) {
                threads.add(event.messageId());
            }
        }
        if (self != null) {
            for (TeamsMessage message : book.messagesOf(channelId, null)) {
                if (message.author() != null && self.id().equals(message.author().id())) {
                    threads.add(message.parentId().isEmpty() ? message.id() : message.parentId());
                }
                if (message.mentions(self)) {
                    threads.add(message.parentId().isEmpty() ? message.id() : message.parentId());
                }
            }
        }
        threads.remove("");
        return threads;
    }

    private static RadarExchanges.Line line(String conversationId, TeamsMessage message, TeamsParticipant self) {
        TeamsParticipant author = message.author();
        boolean fromMe = author != null && (author.self() || (self != null && self.id().equals(author.id())));
        String key = author == null ? "" : author.email() != null ? author.email() : author.id();
        return new RadarExchanges.Line(conversationId + "/" + message.id(), message.sentAt(), key,
                author == null ? "" : author.displayName(), fromMe, message.text(), message.webUrl());
    }

    private static Map<String, Instant> cursors(JsonNode input) {
        Map<String, Instant> cursors = new HashMap<>();
        JsonNode array = input == null ? null : input.path("cursors");
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                try {
                    String ref = node.path("ref").asText("");
                    if (!ref.isBlank()) {
                        cursors.put(ref, Instant.parse(node.path("at").asText("")));
                    }
                } catch (RuntimeException ignored) {
                    // un curseur illisible : le fil repart du plancher de la synchro, rien n'est perdu
                }
            }
        }
        return cursors;
    }

    private static Set<String> refs(JsonNode input, String field) {
        Set<String> refs = new HashSet<>();
        JsonNode array = input == null ? null : input.path(field);
        if (array != null && array.isArray()) {
            array.forEach(node -> {
                if (node.isTextual() && !node.asText().isBlank()) {
                    refs.add(node.asText().strip());
                }
            });
        }
        return refs;
    }

    /** Le plus ancien plancher : celui de la synchro, ou le curseur d'un fil lu il y a plus longtemps. */
    private static Instant oldest(Instant floor, Map<String, Instant> cursors) {
        Instant oldest = floor;
        for (Instant cursor : cursors.values()) {
            if (cursor.isBefore(oldest)) {
                oldest = cursor;
            }
        }
        return oldest;
    }

    static String failureCode(String linkCode) {
        if (BrowserLinkException.NOT_SIGNED_IN.equals(linkCode) || BrowserLinkException.SIGN_IN_REFUSED.equals(linkCode)) {
            return "SESSION_EXPIRED";
        }
        if (BrowserLinkException.TEAMS_NOT_OPEN.equals(linkCode)) {
            return "TEAMS_NOT_OPEN";
        }
        if (BrowserLinkException.LINK_LOST.equals(linkCode)) {
            return "LINK_LOST";
        }
        return "BROWSER_NOT_DETECTED";
    }

    private static String sentenceOf(String linkCode) {
        return switch (failureCode(linkCode)) {
            case "SESSION_EXPIRED" -> "Session Microsoft expirée : rien n'a été synchronisé. Rouvrez Teams dans Chrome "
                    + "et reconnectez-vous, puis relancez la synchro.";
            case "TEAMS_NOT_OPEN" -> "Teams n'était pas ouvert dans le navigateur relié : rien n'a été synchronisé. "
                    + "Ouvrez Teams dans Chrome, puis relancez la synchro.";
            case "LINK_LOST" -> "La liaison avec le navigateur a été perdue en cours de synchro (veille, fenêtre "
                    + "fermée). Elle reprendra où elle en était à la prochaine synchro.";
            default -> "Navigateur non détecté sur ce poste : rien n'a été synchronisé. Lancez Chrome avec le port "
                    + "de débogage du volet Teams, puis relancez la synchro.";
        };
    }

    private record Planned(TeamsConversation conversation, Instant floor, Set<String> threads) {
    }

    /** La couverture d'une synchro : ce qui a été lu, et surtout ce qui ne l'a pas été. */
    private final class Coverage {

        private final RadarAssignment assignment;
        private final Instant to;
        private final ArrayNode threads = mapper.createArrayNode();
        boolean discoveryServed;
        boolean discoveryComplete;
        int listed;
        int active;
        int ignored;
        int deferred;
        int read;
        int partialThreads;
        int failedThreads;
        int unreadChannels;
        int meetingsSeen;
        int transcribed;
        int noTranscript;
        int denied;
        int failedMeetings;
        int messages;
        int batches;
        boolean calendarServed;
        String navigation = "NOT_TRIED";
        boolean partial;
        /** F-89 / SF-89-06 : d'où vient la liste des fils, et ce qui a été lu à l'écran. */
        String discoverySource = "reseau";
        int readOnScreen;
        int transcribedOnScreen;
        int downloadBlocked;

        Coverage(RadarAssignment assignment, Instant to) {
            this.assignment = assignment;
            this.to = to;
        }

        void discovery(boolean served, boolean complete, int count) {
            discoveryServed = served;
            discoveryComplete = complete;
            listed = count;
        }

        void unreadChannel(TeamsConversation conversation) {
            unreadChannels++;
            list(conversation.id(), conversation.label(), "CHANNEL", "UNREAD_CHANNEL",
                    "canal actif : aucun fil où vous avez écrit, répondu ou été mentionné");
        }

        void thread(String ref, String label, String kind, String status, String detail) {
            switch (status) {
                case "READ" -> read++;
                case "PARTIAL" -> partialThreads++;
                default -> failedThreads++;
            }
            if (!"READ".equals(status)) {
                list(ref, label, kind, status, detail);
            }
        }

        void meeting(TeamsMeeting meeting, String status, String detail) {
            switch (status) {
                case "TRANSCRIBED" -> transcribed++;
                case "DENIED" -> denied++;
                case "NO_TRANSCRIPT" -> noTranscript++;
                default -> failedMeetings++;
            }
            if (!"TRANSCRIBED".equals(status)) {
                list(meeting.id(), meeting.subject(), "MEETING", status, detail);
            }
        }

        private void list(String ref, String label, String kind, String status, String detail) {
            if (threads.size() >= MAX_LISTED_THREADS) {
                return;
            }
            ObjectNode node = threads.addObject();
            node.put("ref", ref.length() <= RadarExchanges.MAX_REF_CHARS ? ref : ref.substring(0, RadarExchanges.MAX_REF_CHARS));
            String name = label == null ? "" : label.strip();
            node.put("label", name.length() <= MAX_LABEL_CHARS ? name : name.substring(0, MAX_LABEL_CHARS) + "…");
            node.put("kind", kind);
            node.put("status", status);
            if (detail != null && !detail.isBlank()) {
                node.put("detail", detail);
            }
        }

        Outcome stopped() {
            return new Outcome("PARTIAL", json(null));
        }

        Outcome failed(String code, String sentence, String remedy) {
            ObjectNode failure = mapper.createObjectNode();
            failure.put("code", code);
            failure.put("sentence", sentence);
            if (remedy != null && !remedy.isBlank()) {
                failure.put("remedy", remedy.length() <= 500 ? remedy : remedy.substring(0, 500));
            }
            return new Outcome("FAILED", json(failure));
        }

        Outcome done() {
            boolean incomplete = partial || partialThreads > 0 || failedThreads > 0 || failedMeetings > 0 || deferred > 0
                    || !discoveryServed || !discoveryComplete;
            if (batches == 0 && (failedThreads > 0 || failedMeetings > 0) && read == 0 && transcribed == 0) {
                ObjectNode failure = mapper.createObjectNode();
                failure.put("code", "NOTHING_UPLOADED");
                failure.put("sentence", "Aucun lot n'a pu remonter : la synchro sera reprise à la prochaine occasion.");
                return new Outcome("FAILED", json(failure));
            }
            return new Outcome(incomplete ? "PARTIAL" : "SUCCEEDED", json(null));
        }

        private ObjectNode json(ObjectNode failure) {
            ObjectNode root = mapper.createObjectNode();
            root.put("version", 1);
            ObjectNode window = root.putObject("window");
            window.put("from", assignment.windowFrom().toString());
            window.put("to", to.toString());
            window.put("firstSync", assignment.firstSync());
            ObjectNode discovery = root.putObject("discovery");
            discovery.put("served", discoveryServed);
            discovery.put("complete", discoveryComplete);
            discovery.put("listed", listed);
            discovery.put("source", discoverySource);
            ObjectNode conversations = root.putObject("conversations");
            conversations.put("active", active);
            conversations.put("read", read);
            conversations.put("partial", partialThreads);
            conversations.put("failed", failedThreads);
            conversations.put("ignored", ignored);
            conversations.put("deferred", deferred);
            conversations.put("readOnScreen", readOnScreen);
            root.putObject("channels").put("unreadActive", unreadChannels);
            ObjectNode meetings = root.putObject("meetings");
            meetings.put("navigation", navigation);
            meetings.put("calendarServed", calendarServed);
            meetings.put("seen", meetingsSeen);
            meetings.put("transcribed", transcribed);
            meetings.put("noTranscript", noTranscript);
            meetings.put("denied", denied);
            meetings.put("failed", failedMeetings);
            meetings.put("transcribedOnScreen", transcribedOnScreen);
            meetings.put("downloadBlocked", downloadBlocked);
            root.put("messages", messages);
            root.put("batches", batches);
            root.set("threads", threads);
            if (failure != null) {
                root.set("failure", failure);
            }
            return root;
        }
    }
}
