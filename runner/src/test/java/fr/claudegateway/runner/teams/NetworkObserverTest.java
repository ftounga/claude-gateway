package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-87 / SF-87-02 — <b>on observe le réseau, pas le DOM</b>.
 *
 * <p>Ce que ces tests tiennent : ce qui est intéressant est retenu, le bruit de la page est écarté
 * <b>sans bruit</b>, et un corps qu'on n'a pas pu lire est <b>déclaré</b> au lieu de disparaître.</p>
 */
class NetworkObserverTest {

    private static final String MESSAGES_URL =
            "https://teams.microsoft.com/api/chatsvc/emea/v1/users/ME/conversations/19:x/messages";

    private final FakeCdpConnection browser = new FakeCdpConnection();
    private final TeamsAdapter adapter = TeamsAdapters.current();
    private final NetworkObserver observer = new NetworkObserver(browser, adapter);

    @Test
    @DisplayName("F-100 : une réponse refusée (403) est comptée par nature, et son corps n'est jamais demandé")
    void denied_response_is_counted_without_body() {
        observer.start();
        browser.emitResponse("r403", "https://teams.microsoft.com/api/mt/emea/beta/meetings/M1/transcripts/T1",
                "{\"error\":\"Forbidden\"}", 403);

        assertTrue(observer.collect().isEmpty());
        assertEquals(1, observer.denied(TeamsPayloadKind.MEETING_TRANSCRIPT));
        assertEquals(0, observer.denied(TeamsPayloadKind.CONVERSATION_MESSAGES));
        assertFalse(browser.sentCommands().contains(CdpCommands.GET_RESPONSE_BODY));
    }

    @Test
    @DisplayName("F-108 §4.8 : l'auto-attach ne retient QUE les cadres des domaines Microsoft")
    void auto_attach_is_filtered_on_domains() {
        observer.start();
        observer.observeFrames();

        browser.emitAttached("https://teams.microsoft.com/v2/worker.js");
        browser.emitAttached("https://contoso.sharepoint.com/embedded");
        browser.emitAttached("https://ads.example.com/iframe");
        browser.emitAttached("https://login.microsoftonline.com/frame");

        List<String> frames = observer.attachedFrames();
        assertEquals(2, frames.size(), frames.toString());
        assertTrue(frames.stream().anyMatch(url -> url.contains("teams.microsoft.com")));
        assertTrue(frames.stream().anyMatch(url -> url.contains("sharepoint.com")));
        assertFalse(frames.stream().anyMatch(url -> url.contains("example.com")),
                "un cadre hors liste n'est jamais attaché");
        assertFalse(frames.stream().anyMatch(url -> url.contains("login.")),
                "une page d'identification n'est jamais attachée");
    }

    @Test
    @DisplayName("Une réponse de conversation est retenue, avec son corps")
    void keeps_a_conversation_response() {
        observer.start();
        browser.emitResponse("req-1", MESSAGES_URL, "{\"messages\":[]}");

        List<ObservedResponse> observed = observer.collect();

        assertEquals(1, observed.size());
        assertEquals(TeamsPayloadKind.CONVERSATION_MESSAGES, observed.get(0).kind());
        assertTrue(observed.get(0).hasBody());
        assertTrue(observer.gaps().isEmpty());
    }

    @Test
    @DisplayName("Le bruit de la page est écarté SANS être compté comme un manque")
    void page_noise_is_dropped_silently() {
        observer.start();
        browser.emitResponse("req-1", "https://statics.teams.cdn.office.net/app.js", "…");
        browser.emitResponse("req-2", "https://example.com/api/autre-chose", "{}");

        assertTrue(observer.collect().isEmpty());
        assertTrue(observer.gaps().isEmpty(),
                "compter chaque feuille de style comme un manque ferait crier la sonde de santé");
    }

    @Test
    @DisplayName("Un corps que le navigateur a purgé devient un manque, jamais un silence")
    void a_purged_body_becomes_a_gap() {
        observer.start();
        browser.purge("req-1");
        browser.emitResponse("req-1", MESSAGES_URL, null);

        List<ObservedResponse> observed = observer.collect();

        assertEquals(1, observed.size());
        assertFalse(observed.get(0).hasBody());
        assertEquals(1, observer.gaps().size());
        assertEquals(TeamsGapKind.BODY_UNAVAILABLE, observer.gaps().get(0).kind());
        assertTrue(observer.gaps().get(0).describe().contains("corps de réponse indisponible"));
    }

    @Test
    @DisplayName("Un corps vide est déclaré, il ne passe pas pour une conversation vide")
    void an_empty_body_is_declared() {
        observer.start();
        browser.emitResponse("req-1", MESSAGES_URL, "");

        observer.collect();

        assertEquals(1, observer.gaps().size());
        assertEquals(TeamsGapKind.BODY_UNAVAILABLE, observer.gaps().get(0).kind());
    }

    @Test
    @DisplayName("La file se vide à chaque récolte : une réponse n'est jamais lue deux fois")
    void collecting_empties_the_queue() {
        observer.start();
        browser.emitResponse("req-1", MESSAGES_URL, "{\"messages\":[]}");

        assertEquals(1, observer.collect().size());
        assertTrue(observer.collect().isEmpty());
    }

    @Test
    @DisplayName("L'observation n'émet que des commandes autorisées")
    void only_allowed_commands_are_sent() {
        observer.start();
        browser.emitResponse("req-1", MESSAGES_URL, "{\"messages\":[]}");
        observer.collect();

        browser.sentCommands().forEach(method -> assertTrue(CdpCommands.isAllowed(method), method));
        assertTrue(browser.sentCommands().contains(CdpCommands.NETWORK_ENABLE));
        assertTrue(browser.sentCommands().contains(CdpCommands.GET_RESPONSE_BODY));
    }
}
