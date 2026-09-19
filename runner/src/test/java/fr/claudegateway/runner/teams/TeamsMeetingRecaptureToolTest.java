package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.OperatingSystem;
import fr.claudegateway.runner.ToolOutcome;

/**
 * <b>Robustesse re-capture</b> (F-128 / SF-128-09) : le bug reproduit en prod CAGIP le 2026-09-19 — la
 * 1ère « Rejoindre & capturer » marche, la 2ᵉ échoue « injoignable » parce que le Chrome managé est
 * tombé après la capture et que {@code teams_meeting_join} concluait sans jamais tenter de le relancer.
 *
 * <p>Le Chrome managé, la sonde de port et la liaison sont simulés : on éprouve la <b>décision</b> du
 * join — (re)garantir avant d'échouer, retenter, ne nommer « injoignable » qu'après un échec réel —
 * sans navigateur (le comportement navigateur réel reste « À VALIDER SUR CALL RÉEL »).</p>
 */
class TeamsMeetingRecaptureToolTest {

    private static final String URL = "https://teams.microsoft.com/l/meetup-join/19%3ameeting_abc";

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> said = new ArrayList<>();

    /** Vrai quand le port de débogage du Chrome managé répond ; piloté par le test. */
    private final AtomicBoolean chromeUp = new AtomicBoolean(true);
    /** Nombre de (re)lancements réels du Chrome managé. */
    private int launches;

    private ToolOutcome join(TeamsTools tools) throws IOException {
        return tools.execute(TeamsTools.MEETING_JOIN, mapper.readTree("{\"url\":\"" + URL + "\"}"));
    }

    /**
     * Une liaison qui réussit tant que {@code chromeUp} est vrai (elle sert alors la prochaine connexion
     * de la file), et qui échoue comme une attache sur un port muet dès qu'il est faux.
     */
    private TeamsSession sessionOver(Iterator<FakeCdpConnection> browsers) {
        return new TeamsSession(9222, TeamsAdapters.current(), said::add, (port, adapter, say) -> {
            if (!chromeUp.get()) {
                throw new BrowserLinkException(BrowserLinkException.BROWSER_NOT_DETECTED,
                        BrowserLaunchAdvice.forSystem(OperatingSystem.LINUX, port));
            }
            FakeCdpConnection conn = browsers.next();
            return BrowserLink.attach(port, adapter,
                    url -> url.endsWith("/json/version")
                            ? "{\"Browser\":\"Chrome/140.0.0.0\"}"
                            : "[{\"id\":\"1\",\"type\":\"page\",\"url\":\"https://teams.microsoft.com/v2/\","
                                    + "\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/d/1\"}]",
                    wsUrl -> conn, say);
        });
    }

    /**
     * Un Chrome managé simulé : le port répond selon {@code chromeUp}, et un (re)lancement rend le port
     * joignable (comme un vrai lancement du navigateur) en comptant l'appel.
     */
    private ManagedChrome managedChrome(Optional<Path> executable, Path profile) {
        ProcessSession session = (command, workingDir) -> {
            launches++;
            chromeUp.set(true);
            return new ManagedChromeTest.FakeHandle();
        };
        return new ManagedChrome(executable, profile, 9222, session, port -> chromeUp.get(),
                millis -> { }, null);
    }

    @Test
    @DisplayName("le bug : un 2ᵉ join récupère un Chrome managé tombé après la capture, puis réussit")
    void secondJoinRecoversFallenChrome(@TempDir Path profile) throws Exception {
        FakeCdpConnection first = new FakeCdpConnection();
        FakeCdpConnection second = new FakeCdpConnection();
        Iterator<FakeCdpConnection> browsers = List.of(first, second).iterator();
        TeamsTools tools = new TeamsTools(sessionOver(browsers), millis -> { })
                .withManagedChrome(managedChrome(Optional.of(Path.of("/opt/chrome")), profile));

        // 1ère « Rejoindre & capturer » : le Chrome managé est joignable → succès (comme en prod).
        ToolOutcome one = join(tools);
        assertTrue(one.ok(), "la 1ère jonction doit réussir");
        assertTrue(first.navigations().contains(URL));
        assertEquals(0, launches, "aucun lancement quand le Chrome répond déjà");

        // La capture s'est terminée : la réunion se ferme, l'onglet unique se referme, le process sort
        // → le port CDP ne répond plus et la liaison est perdue.
        chromeUp.set(false);
        first.close();

        // 2ᵉ join sur la même réunion : l'attache échoue d'abord, mais le join (re)garantit le Chrome
        // managé (relance idempotente) puis RETENTE — au lieu de conclure « injoignable » tout de suite.
        ToolOutcome two = join(tools);

        assertTrue(two.ok(), "le 2ᵉ join doit réussir après récupération du Chrome managé");
        JsonNode json = mapper.readTree(two.content());
        assertTrue(json.path("joined").asBoolean(), "joined doit être vrai après récupération");
        assertEquals(1, launches, "le Chrome managé est relancé UNE seule fois (pas de double lancement)");
        assertTrue(second.navigations().contains(URL),
                "la navigation est retentée sur la liaison rétablie");
    }

    @Test
    @DisplayName("Chrome managé toujours vivant : le 2ᵉ join ne relance rien (récupération idempotente)")
    void secondJoinDoesNotRelaunchWhenAlive(@TempDir Path profile) throws Exception {
        FakeCdpConnection browser = new FakeCdpConnection();
        // La même liaison sert les deux joins : elle reste ouverte, donc jamais ré-attachée.
        Iterator<FakeCdpConnection> browsers = List.of(browser).iterator();
        TeamsTools tools = new TeamsTools(sessionOver(browsers), millis -> { })
                .withManagedChrome(managedChrome(Optional.of(Path.of("/opt/chrome")), profile));

        assertTrue(join(tools).ok());
        assertTrue(join(tools).ok(), "un 2ᵉ join sur un Chrome vivant réussit sans récupération");
        assertEquals(0, launches, "un Chrome vivant n'est jamais relancé par le join");
    }

    @Test
    @DisplayName("récupération réellement impossible (aucun navigateur) : « injoignable » nommé APRÈS tentative")
    void namesUnreachableOnlyAfterFailedRecovery(@TempDir Path profile) throws Exception {
        chromeUp.set(false);
        FakeCdpConnection unused = new FakeCdpConnection();
        Iterator<FakeCdpConnection> browsers = List.of(unused).iterator();
        // Aucun exécutable résolu → ensureRunning rend NO_BROWSER : la récupération échoue vraiment.
        TeamsTools tools = new TeamsTools(sessionOver(browsers), millis -> { })
                .withManagedChrome(managedChrome(Optional.empty(), profile));

        ToolOutcome outcome = join(tools);

        assertFalse(outcome.ok());
        assertEquals("browser_unreachable", outcome.errorCode());
        assertEquals(0, launches, "aucun lancement possible sans navigateur");
    }

    @Test
    @DisplayName("aucun Chrome managé branché (repli) : « injoignable » comme avant (non-régression)")
    void namesUnreachableWithoutGuarantor() throws Exception {
        chromeUp.set(false);
        FakeCdpConnection unused = new FakeCdpConnection();
        Iterator<FakeCdpConnection> browsers = List.of(unused).iterator();
        // Pas de withManagedChrome : le join garde son comportement d'avant SF-128-09.
        TeamsTools tools = new TeamsTools(sessionOver(browsers), millis -> { });

        ToolOutcome outcome = join(tools);

        assertFalse(outcome.ok());
        assertEquals("browser_unreachable", outcome.errorCode());
    }
}
