package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-88 / SF-88-01 — <b>le morceau difficile</b> : lire un fil sur une fenêtre de temps.
 *
 * <p>Ce qui est éprouvé ici est le <b>recollement</b> : la liste est virtualisée, les pages se
 * chevauchent, la page peut bouger sans rien rapporter, et elle peut s'arrêter avant la période
 * demandée. Les trois situations doivent être <b>nommées</b> — jamais tues, jamais devinées.</p>
 */
class TeamsHarvesterTest {

    private static final Instant AUGUST = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant OCTOBER = Instant.parse("2026-10-01T00:00:00Z");

    private static TeamsReadWindow window(int cap) {
        return new TeamsReadWindow(AUGUST, OCTOBER, null, null, cap, false, false);
    }

    @Test
    @DisplayName("Deux pages qui se chevauchent : aucun doublon, et la version la plus récemment"
            + " observée gagne")
    void overlapping_pages_are_merged_without_duplicates() {
        PaperTeams teams = new PaperTeams()
                .already("r1", PaperTeams.MESSAGES_URL, "conversation-messages.json")
                .onScroll("r2", PaperTeams.MESSAGES_URL, "conversation-messages-page2.json");
        TeamsLedger ledger = teams.ledger();

        TeamsHarvester.Harvest harvest =
                teams.harvester(ledger).readConversation(PaperTeams.THREAD, window(500));

        Set<String> ids = new HashSet<>();
        harvest.messages().forEach(message -> assertTrue(ids.add(message.id()),
                "message rendu deux fois : " + message.id()));
        assertEquals(5, harvest.messages().size(), harvest.reading().summary("messages"));
        TeamsMessage edited = harvest.messages().stream()
                .filter(message -> message.id().equals("1757662323000")).findFirst().orElseThrow();
        assertTrue(edited.text().contains("version corrigee"),
                "la version la plus récemment observée doit gagner : " + edited.text());
    }

    @Test
    @DisplayName("Les messages sortent triés du plus ancien au plus récent, quel que soit l'ordre"
            + " d'arrivée")
    void messages_come_out_in_chronological_order() {
        PaperTeams teams = new PaperTeams()
                .already("r1", PaperTeams.MESSAGES_URL, "conversation-messages-page2.json")
                .onScroll("r2", PaperTeams.MESSAGES_URL, "conversation-messages.json");

        List<TeamsMessage> messages =
                teams.harvester(teams.ledger()).readConversation(PaperTeams.THREAD, window(500))
                        .messages();

        for (int index = 1; index < messages.size(); index++) {
            assertFalse(messages.get(index).sentAt().isBefore(messages.get(index - 1).sentAt()),
                    "ordre chronologique rompu à l'indice " + index);
        }
    }

    @Test
    @DisplayName("Teams n'annonce plus rien avant : la lecture se dit COMPLÈTE, sans manque")
    void a_page_that_announces_nothing_before_is_a_complete_reading() {
        PaperTeams teams = new PaperTeams()
                .already("r1", PaperTeams.MESSAGES_URL, "conversation-messages.json")
                .onScroll("r2", PaperTeams.MESSAGES_URL, "conversation-messages-page2.json");

        TeamsHarvester.Harvest harvest =
                teams.harvester(teams.ledger()).readConversation(PaperTeams.THREAD, window(500));

        assertTrue(harvest.window().reachedStartOfConversation());
        assertTrue(harvest.window().fullyCovered(), harvest.reading().summary("messages"));
        assertTrue(harvest.gaps().stream()
                .noneMatch(gap -> gap.kind() == TeamsGapKind.SCROLL_EXHAUSTED));
    }

    @Test
    @DisplayName("La page ne remonte plus alors que Teams annonce des messages avant : DÉFILEMENT"
            + " ÉPUISÉ, et la lecture n'est PAS complète")
    void a_scroll_that_stops_too_early_is_named() {
        PaperTeams teams = new PaperTeams()
                .already("r1", PaperTeams.MESSAGES_URL, "conversation-messages.json");

        TeamsHarvester.Harvest harvest =
                teams.harvester(teams.ledger()).readConversation(PaperTeams.THREAD, window(500));

        assertTrue(harvest.gaps().stream()
                .anyMatch(gap -> gap.kind() == TeamsGapKind.SCROLL_EXHAUSTED),
                harvest.gaps().toString());
        assertFalse(harvest.window().fullyCovered());
        assertFalse(harvest.window().reachedStartOfConversation());
    }

    @Test
    @DisplayName("La page défile sans plus rien rapporter : PAGINATION INTERROMPUE — un trou"
            + " possible est nommé")
    void a_scroll_that_brings_nothing_back_is_named() {
        PaperTeams teams = new PaperTeams()
                .already("r1", PaperTeams.MESSAGES_URL, "conversation-messages.json");
        teams.browser.scrollsForever();

        TeamsHarvester.Harvest harvest =
                teams.harvester(teams.ledger()).readConversation(PaperTeams.THREAD, window(500));

        assertTrue(harvest.gaps().stream()
                .anyMatch(gap -> gap.kind() == TeamsGapKind.PAGINATION_STOPPED),
                harvest.gaps().toString());
        assertTrue(teams.browser.scrolls() <= PageGestures.MAX_SCROLL_GESTURES,
                "on ne doit pas tourner quarante fois dans le vide");
    }

    @Test
    @DisplayName("Plafond atteint : il mord du côté ancien, il est nommé, et la phrase de D4"
            + " explique comment remonter plus loin")
    void the_cap_bites_on_the_old_side_and_says_so() {
        PaperTeams teams = new PaperTeams()
                .already("r1", PaperTeams.MESSAGES_URL, "conversation-messages.json");

        TeamsHarvester.Harvest harvest =
                teams.harvester(teams.ledger()).readConversation(PaperTeams.THREAD, window(2));

        assertEquals(2, harvest.messages().size());
        assertEquals("1757662400000", harvest.messages().get(0).id(),
                "le plafond doit couper les plus ANCIENS, pas les plus récents");
        assertTrue(harvest.window().capReached());
        assertFalse(harvest.window().fullyCovered());
        String summary = harvest.reading().summary("messages");
        assertTrue(summary.contains("Plafond de 2 atteint"), summary);
        assertTrue(summary.contains("demandez une période explicite"), summary);
    }

    @Test
    @DisplayName("Une réponse amputée : les messages valides sortent, les manques sont nommés,"
            + " aucun message à moitié lu")
    void a_partial_page_never_yields_half_read_messages() {
        PaperTeams teams = new PaperTeams()
                .already("r1", PaperTeams.MESSAGES_URL, "conversation-messages-partial.json");

        TeamsHarvester.Harvest harvest =
                teams.harvester(teams.ledger()).readConversation(PaperTeams.THREAD, window(500));

        harvest.messages().forEach(message -> assertTrue(message.isReadable(),
                "un message rendu doit être complet : " + message.id()));
        assertTrue(harvest.gaps().stream()
                .anyMatch(gap -> gap.kind() == TeamsGapKind.MISSING_FIELD
                        || gap.kind() == TeamsGapKind.UNKNOWN_MESSAGE_KIND),
                harvest.gaps().toString());
    }

    @Test
    @DisplayName("Une réponse que l'adaptateur ne reconnaît pas : AUCUN message, et on dit pourquoi")
    void an_unrecognized_page_yields_nothing_and_says_why() {
        PaperTeams teams = new PaperTeams()
                .already("r1", PaperTeams.MESSAGES_URL, "conversation-messages-unknown.json");

        TeamsHarvester.Harvest harvest =
                teams.harvester(teams.ledger()).readConversation(PaperTeams.THREAD, window(500));

        assertTrue(harvest.messages().isEmpty());
        assertTrue(harvest.gaps().stream()
                .anyMatch(gap -> gap.kind() == TeamsGapKind.UNRECOGNIZED_PAYLOAD),
                harvest.gaps().toString());
        assertEquals(TeamsHealthVerdict.NONE, harvest.health().verdict());
    }

    @Test
    @DisplayName("Le fil demandé n'est pas affiché : il est ouvert, puis CELUI DE L'UTILISATEUR EST"
            + " REMIS — et c'est dit")
    void showing_another_thread_puts_the_user_view_back() {
        PaperTeams teams = new PaperTeams();
        teams.browser.showing(PaperTeams.OTHER_THREAD);
        teams.browser.reachable(PaperTeams.THREAD);
        teams.browser.reachable(PaperTeams.OTHER_THREAD);
        teams.already("r1", PaperTeams.MESSAGES_URL, "conversation-messages.json");

        TeamsHarvester.Harvest harvest =
                teams.harvester(teams.ledger()).readConversation(PaperTeams.THREAD, window(500));

        assertEquals(PaperTeams.THREAD, harvest.conversationId());
        assertFalse(harvest.messages().isEmpty());
        assertTrue(harvest.viewport().contains("ouvert ce fil"), harvest.viewport());
        assertTrue(harvest.viewport().contains("a été remis"), harvest.viewport());
        assertTrue(teams.browser.route().contains(PaperTeams.OTHER_THREAD),
                "la vue de l'utilisateur doit être remise : " + teams.browser.route());
    }

    @Test
    @DisplayName("Un fil qu'on ne sait pas atteindre : ZÉRO message ET un manque nommé — jamais une"
            + " liste vide silencieuse")
    void an_unreachable_thread_is_a_named_gap_not_an_empty_list() {
        PaperTeams teams = new PaperTeams();
        teams.browser.showing(PaperTeams.OTHER_THREAD);

        TeamsHarvester.Harvest harvest =
                teams.harvester(teams.ledger()).readConversation(PaperTeams.THREAD, window(500));

        assertTrue(harvest.messages().isEmpty());
        assertTrue(harvest.gaps().stream()
                .anyMatch(gap -> gap.kind() == TeamsGapKind.CONVERSATION_NOT_REACHED),
                harvest.gaps().toString());
    }

    @Test
    @DisplayName("Aucun fil nommé et aucun fil affiché : un manque, pas une devinette")
    void no_thread_named_and_none_shown_is_a_gap() {
        PaperTeams teams = new PaperTeams();
        teams.browser.showing("");

        TeamsHarvester.Harvest harvest =
                teams.harvester(teams.ledger()).readConversation("", window(500));

        assertTrue(harvest.messages().isEmpty());
        assertEquals(TeamsGapKind.CONVERSATION_NOT_REACHED, harvest.gaps().get(0).kind());
    }

    @Test
    @DisplayName("Le fil affiché est lu quand la demande n'en nomme aucun")
    void the_shown_thread_is_read_by_default() {
        PaperTeams teams = new PaperTeams()
                .already("r1", PaperTeams.MESSAGES_URL, "conversation-messages.json");

        TeamsHarvester.Harvest harvest =
                teams.harvester(teams.ledger()).readConversation("", window(500));

        assertEquals(PaperTeams.THREAD, harvest.conversationId());
        assertFalse(harvest.messages().isEmpty());
    }

    @Test
    @DisplayName("La fenêtre rendue est celle RÉELLEMENT lue, jamais celle demandée")
    void the_window_carried_is_the_one_actually_read() {
        PaperTeams teams = new PaperTeams()
                .already("r1", PaperTeams.MESSAGES_URL, "conversation-messages.json");

        TeamsReadWindow read =
                teams.harvester(teams.ledger()).readConversation(PaperTeams.THREAD, window(500))
                        .window();

        assertEquals(AUGUST, read.requestedFrom());
        assertNotNull(read.actualFrom());
        assertEquals(Instant.parse("2026-09-05T09:12:03.412Z"), read.actualFrom());
        assertEquals(Instant.parse("2026-09-12T07:05:10Z"), read.actualTo());
    }

    @Test
    @DisplayName("L'utilisateur relié est appris du profil observé, et « m'a-t-on mentionné »"
            + " devient tranchable")
    void the_linked_user_is_learned_from_the_observed_profile() {
        PaperTeams teams = new PaperTeams()
                .already("r0", "https://teams.microsoft.com/api/mt/emea/beta/users/"
                        + TeamsSamples.SELF + "/profile", "profile.json")
                .already("r1", PaperTeams.MESSAGES_URL, "conversation-messages.json");
        TeamsLedger ledger = teams.ledger();

        TeamsHarvester.Harvest harvest =
                teams.harvester(ledger).readConversation(PaperTeams.THREAD, window(500));

        assertNotNull(ledger.self(), "le profil observé doit identifier l'utilisateur relié");
        assertEquals(TeamsSamples.SELF, ledger.self().id());
        assertTrue(harvest.messages().stream()
                .anyMatch(message -> message.mentions(ledger.self())),
                "la mention de l'échantillon vise l'utilisateur relié");
    }
}
