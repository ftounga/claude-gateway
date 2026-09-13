package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * F-89 / SF-89-08 — <b>Teams relié, rien classé</b> : le trajet d'une adresse, de la trame brute de Chrome
 * jusqu'à la classification, pour les trois origines du constat (onglet, worker, service worker).
 *
 * <p>Constat du poste client (2026-09-13) : 28 réponses observées, 0 classée ; hypothèse de l'auteur,
 * « l'URL arrive à classify sans son hôte ». Ces tests passent les trames <b>telles que Chrome les
 * écrit</b> (session aplatie, en-têtes, requête dans l'adresse) par le <b>vrai</b> routage de la socket.</p>
 */
class NetworkObserverChromeFramesTest {

    static final String WORKER_URL = "https://teams.microsoft.com/v2/worker/precompiled-web-worker-9f2c.js";
    static final String SERVICE_WORKER_URL = "https://teams.microsoft.com/v2/sw.js";
    static final String IFRAME_URL = "https://contoso-my.sharepoint.com/personal/x/_layouts/15/streamembed.aspx";

    private final ChromeCdpFrames chrome = new ChromeCdpFrames();
    private final NetworkObserver observer = new NetworkObserver(chrome, TeamsAdapters.current(), Runnable::run);

    /** Une adresse réelle par famille : hôte, chemin du tenant européen, identifiants, chaîne de requête. */
    static Stream<Arguments> families() {
        return Stream.of(
                Arguments.of(TeamsPayloadKind.CONVERSATION_MESSAGES, "https://teams.microsoft.com/api/chatsvc/emea/v1/"
                        + "users/ME/conversations/19%3Ameeting_NzY4ZTk0@thread.v2/messages"
                        + "?view=msnp24Equivalent%7CsupportsMessageProperties&pageSize=200&startTime=1"),
                Arguments.of(TeamsPayloadKind.CONVERSATION_LIST, "https://teams.microsoft.com/api/csa/emea/api/v2/"
                        + "teams/users/me/conversations?isPrefetch=false&enableMembershipSummary=true"),
                Arguments.of(TeamsPayloadKind.ACTIVITY_FEED,
                        "https://teams.microsoft.com/api/mt/emea/beta/users/me/activityfeed?pageSize=50&top=20"),
                Arguments.of(TeamsPayloadKind.SEARCH_RESULTS,
                        "https://teams.microsoft.com/api/searchservice/emea/v1/search/messages?query=MFA&size=25"),
                Arguments.of(TeamsPayloadKind.MEETING_DETAILS, "https://teams.microsoft.com/api/mt/emea/v1/"
                        + "schedulingService/meetings?startDate=2026-09-01T00%3A00%3A00Z&endDate=2026-09-30"),
                Arguments.of(TeamsPayloadKind.MEETING_TRANSCRIPT, "https://teams.microsoft.com/api/mt/emea/beta/"
                        + "meetings/MTG-0001/transcripts/T1?format=json"),
                Arguments.of(TeamsPayloadKind.PROFILE, "https://teams.microsoft.com/api/mt/emea/beta/users/"
                        + "8:orgid:6f1c2a3b-0000-4d5e-9f00-112233445566/profile?throwIfNotFound=false"),
                Arguments.of(TeamsPayloadKind.CALENDAR_EVENT, "https://teams.microsoft.com/api/mt/emea/v2.0/me/"
                        + "calendars/events/iCalUId/040000008200E00074C5B7101A82E00800000000A1B2C3D4E5F6"
                        + "?$select=subject,start,end"),
                Arguments.of(TeamsPayloadKind.MEETING_COLLAB_OBJECT, "https://teams.microsoft.com/api/mcps/eu/"
                        + "collab/readcollabobject/V2/7a1b2c3d/19%3Ameeting_NzY4@thread.v2/0?api-version=2"));
    }

    /** Les trois origines du constat : la session où la réponse arrive, et la cible qui l'émet. */
    static Stream<Arguments> familiesByOrigin() {
        return families().flatMap(family -> Stream.of(NetworkSurvey.Origin.TEAMS_TAB, NetworkSurvey.Origin.WORKER,
                        NetworkSurvey.Origin.SERVICE_WORKER)
                .map(origin -> Arguments.of(family.get()[0], family.get()[1], origin)));
    }

    @ParameterizedTest(name = "{0} depuis {2}")
    @MethodSource("familiesByOrigin")
    @DisplayName("Chaque famille, adresse complète avec requête, est classée quelle que soit son origine")
    void every_family_is_classified_from_every_origin(TeamsPayloadKind kind, String url,
            NetworkSurvey.Origin origin) {
        observer.start();
        observer.observeFrames();
        chrome.spawn(ChromeCdpFrames.TAB, "W1", "worker", WORKER_URL);
        chrome.spawn(ChromeCdpFrames.TAB, "SW1", "service_worker", SERVICE_WORKER_URL);
        String session = switch (origin) {
            case WORKER -> "W1";
            case SERVICE_WORKER -> "SW1";
            default -> ChromeCdpFrames.TAB;
        };

        assertTrue(chrome.respond(session, url, "{\"value\":[]}"), "le réseau est activé sur " + origin);

        ObservationDiagnostic seen = observer.diagnostic();
        assertEquals(Map.of(origin.name(), 1), seen.responsesByOrigin());
        assertEquals(Map.of(kind.name(), 1), seen.classifiedByKind(), "classé sous son nom : " + url);
        assertEquals(0, seen.unknownMicrosoft(), seen.topUnknownPaths().toString());
        List<ObservedResponse> kept = observer.collect();
        if (kind == TeamsPayloadKind.MEETING_COLLAB_OBJECT) {
            assertTrue(kept.isEmpty(), "nommé, jamais lu");
        } else {
            assertEquals(1, kept.size());
            assertEquals(kind, kept.get(0).kind());
            assertFalse(kept.get(0).url().contains("?"), "la requête n'entre jamais");
            assertTrue(kept.get(0).hasBody(), "corps demandé sur la session de la cible");
        }
    }

    @Test
    @DisplayName("Le constat rejoué : onglet, workers et service workers — ce qui est reconnu est classé")
    void the_field_report_replayed() {
        observer.start();
        observer.observeFrames();
        chrome.spawn(ChromeCdpFrames.TAB, "SW1", "service_worker", SERVICE_WORKER_URL)
                .spawn(ChromeCdpFrames.TAB, "SW2", "service_worker", "https://teams.microsoft.com/sw-precache.js")
                .spawn(ChromeCdpFrames.TAB, "W1", "worker", WORKER_URL)
                .spawn(ChromeCdpFrames.TAB, "W2", "worker", "https://teams.microsoft.com/v2/worker/slimcore.js");
        for (int index = 0; index < 20; index++) {
            chrome.respond("SW1", "https://statics.teams.cdn.office.net/evergreen-assets/app-" + index + ".js",
                    "Script", "application/javascript", 200, null, false);
        }
        chrome.respond("SW2", "https://teams.microsoft.com/api/mcps/eu/contents", null);
        chrome.respond("W1", "https://teams.microsoft.com/api/chatsvc/fr/v1/threads/19%3Ax@thread.v2/consumptionhorizons",
                null);
        chrome.respond(ChromeCdpFrames.TAB, "https://teams.microsoft.com/api/mt/emea/v2.0/me/calendars/events/iCalUId/"
                + "040000008200E00074C5B7101A82E008?$select=subject", "Fetch", "application/json", 200,
                "{\"subject\":\"Revue\"}", true);
        chrome.respond(ChromeCdpFrames.TAB, "https://teams.microsoft.com/api/mcps/eu/collab/readcollabobject/V2/a/b/c?x=1",
                null);

        ObservationDiagnostic seen = observer.diagnostic();
        assertEquals(Map.of("SERVICE_WORKER", 21, "WORKER", 1, "TEAMS_TAB", 2), seen.responsesByOrigin());
        assertEquals(Map.of("CALENDAR_EVENT", 1, "MEETING_COLLAB_OBJECT", 1), seen.classifiedByKind(),
                "l'hôte n'est perdu nulle part : une adresse de l'onglet servie par le service worker est classée");
        assertEquals(20, seen.ignored());
        assertEquals(2, seen.unknownMicrosoft());
    }

    @Test
    @DisplayName("Un worker né d'un worker (ou d'un cadre) est annoncé, écouté, et ce qu'il sert est classé")
    void a_nested_worker_is_observed_like_the_survey_does() {
        observer.start();
        observer.observeFrames();
        chrome.spawn(ChromeCdpFrames.TAB, "W1", "worker", WORKER_URL)
                .spawn("W1", "W1.1", "worker", "https://teams.microsoft.com/v2/worker/data-layer.js")
                .spawn(ChromeCdpFrames.TAB, "F1", "iframe", IFRAME_URL)
                .spawn("F1", "F1.1", "worker", "https://contoso-my.sharepoint.com/_layouts/15/player-worker.js");

        assertTrue(chrome.announced("W1.1"), "l'auto-attach est redemandé sur la session du worker");
        assertTrue(chrome.announced("F1.1"), "l'auto-attach est redemandé sur la session du cadre");
        assertTrue(chrome.respond("W1.1", "https://teams.microsoft.com/api/mt/emea/v1/schedulingService/meetings"
                + "?startDate=2026-09-01", "{\"value\":[]}"), "le réseau est activé sur le petit-enfant");

        assertEquals(Map.of("MEETING_DETAILS", 1), observer.diagnostic().classifiedByKind());
        assertEquals(1, observer.collect().size());
        chrome.sent().forEach(command -> assertTrue(CdpCommands.isAllowed(command.substring(command.indexOf('|') + 1))));
    }

    @Test
    @DisplayName("Asymétrie levée : le relevé et l'observation des outils voient les mêmes petits-enfants")
    void the_survey_and_the_observer_see_the_same_targets() {
        ChromeCdpFrames surveyed = new ChromeCdpFrames();
        NetworkSurvey survey = new NetworkSurvey(Runnable::run);
        survey.watchTeamsTab(surveyed);
        surveyed.spawn(ChromeCdpFrames.TAB, "W1", "worker", WORKER_URL)
                .spawn("W1", "W1.1", "worker", "https://teams.microsoft.com/v2/worker/data-layer.js");
        surveyed.respond("W1.1", "https://teams.microsoft.com/api/mt/emea/v1/schedulingService/meetings?x=1", null);

        observer.start();
        observer.observeFrames();
        chrome.spawn(ChromeCdpFrames.TAB, "W1", "worker", WORKER_URL)
                .spawn("W1", "W1.1", "worker", "https://teams.microsoft.com/v2/worker/data-layer.js");
        chrome.respond("W1.1", "https://teams.microsoft.com/api/mt/emea/v1/schedulingService/meetings?x=1", null);

        assertEquals("MEETING_DETAILS", survey.snapshot().entries().get(0).classification());
        assertEquals(Map.of("MEETING_DETAILS", 1), observer.diagnostic().classifiedByKind());
    }

    @Test
    @DisplayName("Une cible hors domaines, même petite-fille, n'est jamais écoutée (F-108 §4.8 inchangé)")
    void a_foreign_nested_target_is_never_heard() {
        observer.start();
        observer.observeFrames();
        chrome.spawn(ChromeCdpFrames.TAB, "F1", "iframe", "https://ads.example.com/frame")
                .spawn("F1", "F1.1", "worker", "https://teams.microsoft.com/v2/worker/x.js");

        assertFalse(chrome.announced("F1.1"), "aucun auto-attach n'est demandé sur une cible refusée");
        assertFalse(chrome.sent().contains("F1|" + CdpCommands.NETWORK_ENABLE));
        assertTrue(observer.diagnostic().attachedByOrigin().isEmpty());
    }
}
