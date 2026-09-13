package fr.claudegateway.runner.teams;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * <b>Le repli sur l'écran, en termes du domaine</b> (F-89 / SF-89-06) : fils, listes, flux d'activité et
 * transcriptions lus à l'écran quand le réseau n'a rien servi.
 *
 * <p>Partagé par les outils de lecture de l'agent ({@link TeamsTools}) et par le Radar (vérification guidée
 * et synchro du soir) : une seule façon de lire l'écran, une seule table ({@link TeamsScreen}), les mêmes
 * gardes. Chaque geste passe par {@link PageActions} (domaine, identification) ou {@link PageGestures}, et la
 * vue est remise.</p>
 */
final class TeamsScreenFallback {

    private final BrowserLink link;
    private final BrowserLink.Sleeper sleeper;
    private final Consumer<PageActions.GestureRecord> journal;

    TeamsScreenFallback(BrowserLink link, BrowserLink.Sleeper sleeper, Consumer<PageActions.GestureRecord> journal) {
        this.link = link;
        this.sleeper = sleeper;
        this.journal = journal == null ? record -> { } : journal;
    }

    private PageActions actions() {
        return new PageActions(link, sleeper, journal);
    }

    private static String joined(String first, String second) {
        if (first == null || first.isBlank()) {
            return second == null ? "" : second;
        }
        return second == null || second.isBlank() ? first : first + " " + second;
    }

    /** L'identité d'un élément d'écran : son identifiant s'il en a un, sinon l'empreinte de ses champs. */
    static String screenKey(Map<String, String> item, String... fields) {
        String id = item.getOrDefault("id", "");
        if (!id.isEmpty()) {
            return "id:" + id;
        }
        StringBuilder print = new StringBuilder();
        for (String field : fields) {
            print.append(item.getOrDefault(field, "")).append('\u0001');
        }
        return "print:" + print;
    }

    static String screenId(String key) {
        return "ecran:" + Integer.toHexString(key.hashCode());
    }

    static java.time.Instant screenInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(raw.strip()).toInstant();
        } catch (RuntimeException first) {
            try {
                return java.time.Instant.parse(raw.strip());
            } catch (RuntimeException second) {
                return null;
            }
        }
    }

    /** Un écran de liste lu (conversations, activité), et ce qui a été fait dans la fenêtre. */
    record ScreenList(boolean found, List<Map<String, String>> items, List<TeamsGap> gaps, String viewport) {
    }

    /**
     * Lit une liste affichée ; si elle n'est pas à l'écran, ouvre sa route (gestes gardés de F-108), la
     * lit, et remet la vue.
     */
    ScreenList list(TeamsScreen.View view, String route) {
        PageActions actions = actions();
        TeamsScreenReader reader = new TeamsScreenReader(actions, sleeper);
        try {
            TeamsScreenReader.Collected here = reader.collect(view, false, 3, all -> false,
                    item -> screenKey(item, "title", "author", "time", "text"));
            if (here.found()) {
                return new ScreenList(true, here.items(), here.gaps(), "");
            }
            String before = actions.currentUrl();
            if (MicrosoftDomains.isSignIn(before) || !MicrosoftDomains.isAllowed(before)) {
                return new ScreenList(false, List.of(), here.gaps(), "");
            }
            String reached = actions.navigate(TeamsRoutes.onTabHost(route, before));
            TeamsScreenReader.Collected opened = MicrosoftDomains.isSignIn(reached)
                    ? new TeamsScreenReader.Collected(false, List.of(),
                            List.of(TeamsScreenReader.changed(view, "l'onglet a atterri sur une page d'identification")),
                            false, false)
                    : reader.collect(view, false, 3, all -> false,
                            item -> screenKey(item, "title", "author", "time", "text"));
            boolean restored = actions.restore(before);
            String viewport = "J'ai ouvert " + view.name() + " dans votre fenêtre Teams pour la lire à l'écran"
                    + (restored ? ", puis j'ai remis la vue." : " ; la vue n'a pas pu être remise.");
            return new ScreenList(opened.found(), opened.items(), opened.gaps(), viewport);
        } catch (BrowserLinkException e) {
            return new ScreenList(false, List.of(), List.of(TeamsScreenReader.changed(view,
                    "lecture d'écran refusée par la garde : " + e.getMessage())), "");
        }
    }

    static List<TeamsConversation> conversations(List<Map<String, String>> items) {
        List<TeamsConversation> out = new ArrayList<>();
        for (Map<String, String> item : items) {
            String title = item.getOrDefault("title", "");
            if (title.isEmpty()) {
                continue;
            }
            String id = TeamsRoutes.conversationIdOf(item.getOrDefault("id", ""));
            out.add(new TeamsConversation(id.isEmpty() ? screenId(screenKey(item, "title", "time")) : id,
                    TeamsConversationKind.GROUP, title, List.of(), screenInstant(item.get("time")), ""));
        }
        return out;
    }

    /** Les éléments du flux d'activité qui DISENT une mention. Les autres (réactions, réponses) sont écartés. */
    static List<TeamsMentionEvent> mentions(List<Map<String, String>> items, TeamsReadWindow window) {
        List<TeamsMentionEvent> out = new ArrayList<>();
        for (Map<String, String> item : items) {
            String said = TeamsTools.fold(item.getOrDefault("label", "") + " " + item.getOrDefault("text", ""));
            if (!said.contains("mention")) {
                continue;
            }
            java.time.Instant at = screenInstant(item.get("time"));
            String author = item.getOrDefault("author", "");
            if (at == null || author.isEmpty()) {
                continue;
            }
            if (window != null && ((window.requestedFrom() != null && at.isBefore(window.requestedFrom()))
                    || (window.requestedTo() != null && at.isAfter(window.requestedTo())))) {
                continue;
            }
            out.add(new TeamsMentionEvent(new TeamsMention("", "", TeamsMentionKind.PERSON, ""),
                    screenId(screenKey(item, "author", "time", "text")), "", item.getOrDefault("where", ""),
                    new TeamsParticipant("ecran:" + author, author, null, false), at,
                    item.getOrDefault("text", ""), ""));
        }
        out.sort((a, b) -> b.at().compareTo(a.at()));
        return out;
    }

    /**
     * Lit un fil à l'écran (F-89 / SF-89-06) : l'affiche, remonte jusqu'à couvrir la fenêtre, recolle sans
     * doublon, remet la position et le fil d'origine. {@code null} si le fil n'a pas pu être affiché.
     */
    TeamsHarvester.Harvest thread(PageGestures gestures,
            TeamsHarvester.Harvest network, String shownBefore, TeamsReadWindow window) {
        String target = network.conversationId();
        try {
            if (!target.equals(gestures.shownConversationId()) && !gestures.show(target)) {
                return null;
            }
            TeamsScreenReader reader = new TeamsScreenReader(actions(), sleeper);
            TeamsScreenReader.Collected collected = reader.collect(TeamsScreen.MESSAGES, true,
                    TeamsScreenReader.MAX_GESTURES, all -> coversFrom(all, window),
                    item -> screenKey(item, "author", "time", "text"));
            List<TeamsGap> gaps = new ArrayList<>(collected.gaps());
            List<TeamsMessage> messages = new ArrayList<>();
            int unreadable = 0;
            for (Map<String, String> item : collected.items()) {
                java.time.Instant at = screenInstant(item.get("time"));
                String author = item.getOrDefault("author", "");
                if (at == null || author.isEmpty()) {
                    unreadable++;
                    continue;
                }
                if ((window.requestedFrom() != null && at.isBefore(window.requestedFrom()))
                        || (window.requestedTo() != null && at.isAfter(window.requestedTo()))) {
                    continue;
                }
                messages.add(new TeamsMessage(screenId(screenKey(item, "author", "time", "text")), target, "",
                        new TeamsParticipant("ecran:" + author, author, null, false), at, null, false,
                        TeamsMessageKind.TEXT, item.getOrDefault("text", ""), "", List.of(), List.of(), List.of(),
                        ""));
            }
            if (unreadable > 0) {
                gaps.add(new TeamsGap(TeamsGapKind.MISSING_FIELD, TeamsScreen.MESSAGES.name(), "auteur ou heure",
                        unreadable));
            }
            messages.sort((a, b) -> a.sentAt().compareTo(b.sentAt()));
            boolean cap = collected.capReached();
            if (messages.size() > window.cap()) {
                messages = new ArrayList<>(messages.subList(messages.size() - window.cap(), messages.size()));
                cap = true;
                gaps.add(TeamsGap.of(TeamsGapKind.CAP_REACHED, target, "plafond de " + window.cap() + " messages"));
            }
            // Début atteint : la liste ne remontait plus, OU un message au plus tard au début demandé a été vu —
            // la fenêtre est alors couverte (c'est ce que la synchro du soir appelle « lu jusqu'au plancher »).
            boolean reachedStart = (collected.reachedEdge() || coversFrom(collected.items(), window)) && !cap
                    && collected.gaps().isEmpty();
            boolean restoredThread = !shownBefore.isEmpty() && !shownBefore.equals(target)
                    && gestures.restore(shownBefore);
            if (!collected.found()) {
                gaps.addAll(0, network.gaps());
                return new TeamsHarvester.Harvest(target, List.of(), gaps, network.window(), network.health(),
                        network.viewport());
            }
            String viewport = "J'ai lu ce fil à l'écran dans votre fenêtre Teams (le réseau n'a rien servi)"
                    + (restoredThread ? " ; le fil que vous aviez ouvert a été remis." : ", puis j'ai remis la vue.");
            TeamsReadWindow covered = window.covering(messages.isEmpty() ? null : messages.get(0).sentAt(),
                    messages.isEmpty() ? null : messages.get(messages.size() - 1).sentAt(), cap, reachedStart);
            return new TeamsHarvester.Harvest(target, messages, gaps, covered, network.health(), viewport);
        } catch (BrowserLinkException e) {
            List<TeamsGap> gaps = new ArrayList<>(network.gaps());
            gaps.add(TeamsScreenReader.changed(TeamsScreen.MESSAGES, "lecture d'écran refusée par la garde : "
                    + e.getMessage()));
            return new TeamsHarvester.Harvest(target, List.of(), gaps, network.window(), network.health(),
                    network.viewport());
        }
    }

    static boolean coversFrom(List<Map<String, String>> items, TeamsReadWindow window) {
        if (window == null || window.requestedFrom() == null) {
            return false;
        }
        for (Map<String, String> item : items) {
            java.time.Instant at = screenInstant(item.get("time"));
            if (at != null && !at.isAfter(window.requestedFrom())) {
                return true;
            }
        }
        return false;
    }

    /** Une transcription lue à l'écran, avec les décalages affichés et l'état du téléchargement. */
    record ScreenTranscript(List<TeamsTranscriptCue> cues, List<String> offsets, List<TeamsGap> gaps,
            Boolean downloadBlocked, String viewport) {
    }

    /**
     * Lit le panneau de transcription (F-89 / SF-89-06) : affiche le fil de la réunion s'il est connu, ouvre
     * le panneau s'il ne l'est pas, lit en descendant, note l'état du téléchargement, remet la vue.
     */
    ScreenTranscript transcript(TeamsMeeting meeting) {
        PageActions actions = actions();
        TeamsScreenReader reader = new TeamsScreenReader(actions, sleeper);
        PageGestures gestures = new PageGestures(link, sleeper);
        List<TeamsGap> gaps = new ArrayList<>();
        String shownBefore = "";
        StringBuilder viewport = new StringBuilder();
        try {
            if (meeting != null && !meeting.conversationId().isEmpty()) {
                shownBefore = gestures.shownConversationId();
                if (!meeting.conversationId().equals(shownBefore) && gestures.show(meeting.conversationId())) {
                    viewport.append("J'ai ouvert le fil de la réunion dans votre fenêtre Teams. ");
                }
            }
            Boolean blocked = reader.downloadBlocked();
            if (blocked == null && reader.openTranscript()) {
                viewport.append("J'ai ouvert le panneau Transcription pour le lire à l'écran. ");
                blocked = reader.downloadBlocked();
            }
            TeamsScreenReader.Collected collected = reader.collect(TeamsScreen.TRANSCRIPT, false,
                    TeamsScreenReader.MAX_GESTURES, all -> false,
                    item -> screenKey(item, "speaker", "offset", "text"));
            gaps.addAll(collected.gaps());
            List<TeamsTranscriptCue> cues = new ArrayList<>();
            List<String> offsets = new ArrayList<>();
            java.time.Instant start = meeting == null ? null : meeting.startedAt();
            boolean undated = false;
            for (Map<String, String> item : collected.items()) {
                String text = item.getOrDefault("text", "");
                if (text.isEmpty()) {
                    continue;
                }
                long seconds = TeamsScreen.offsetSeconds(item.get("offset"));
                java.time.Instant at = start != null && seconds >= 0 ? start.plusSeconds(seconds) : null;
                undated |= at == null;
                String speaker = item.getOrDefault("speaker", "");
                cues.add(new TeamsTranscriptCue(at, -1, speaker.isEmpty() ? "" : "ecran:" + speaker, speaker, text));
                offsets.add(item.getOrDefault("offset", ""));
            }
            if (undated && !cues.isEmpty()) {
                gaps.add(TeamsGap.of(TeamsGapKind.MISSING_FIELD, TeamsScreen.TRANSCRIPT.name(),
                        "début de réunion inconnu : les répliques portent leur décalage, sans heure"));
            }
            if (!shownBefore.isEmpty() && meeting != null && !shownBefore.equals(meeting.conversationId())
                    && gestures.restore(shownBefore)) {
                viewport.append("Le fil que vous aviez ouvert a été remis.");
            }
            return new ScreenTranscript(cues, offsets, gaps, blocked, viewport.toString().strip());
        } catch (BrowserLinkException e) {
            gaps.add(TeamsScreenReader.changed(TeamsScreen.TRANSCRIPT, "lecture d'écran refusée par la garde : "
                    + e.getMessage()));
            return new ScreenTranscript(List.of(), List.of(), gaps, null, viewport.toString().strip());
        }
    }
}
