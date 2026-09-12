package fr.claudegateway.runner.teams;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>La liaison</b> (F-87 / SF-87-02) : se rattacher au navigateur déjà authentifié du poste,
 * observer ce que la page Teams reçoit, et faire défiler — le seul geste demandé au DOM.
 *
 * <p><b>On se rattache, on ne lance pas.</b> Un navigateur lancé par nous ne porterait pas la
 * session de l'utilisateur : il serait inutile. Quand rien n'écoute, on <b>dit comment</b> le lancer
 * — avec la ligne de commande exacte (voir {@link BrowserLaunchAdvice}).</p>
 *
 * <p><b>Un seul onglet.</b> La socket est ouverte sur l'onglet Teams, et sur aucun autre.</p>
 */
public final class BrowserLink implements AutoCloseable {

    /** Délai de la découverte : un navigateur présent répond instantanément. */
    public static final long DISCOVERY_TIMEOUT_MS = 2_000L;

    /** Gestes de défilement au plus par lecture : au-delà, on déclare un manque plutôt que boucler. */
    public static final int MAX_SCROLL_GESTURES = 40;

    /** Attente après un défilement, le temps que la page demande la page suivante. */
    public static final long SCROLL_SETTLE_MS = 600L;

    /**
     * Le <b>seul</b> code exécuté dans la page de tout le volet. Volontairement générique : il
     * cherche le conteneur défilable le plus haut et le remonte d'un écran. Aucun sélecteur de
     * Teams, aucune classe CSS — une refonte visuelle ne le casse pas.
     */
    private static final String SCROLL_UP = "(() => {"
            + " const all = Array.from(document.querySelectorAll('*'));"
            + " let best = document.scrollingElement; let height = 0;"
            + " for (const el of all) {"
            + "   if (el.scrollHeight > el.clientHeight + 100 && el.clientHeight > 200"
            + "       && el.scrollHeight > height) { best = el; height = el.scrollHeight; }"
            + " }"
            + " if (!best) { return false; }"
            + " const before = best.scrollTop;"
            + " best.scrollTop = Math.max(0, before - best.clientHeight);"
            + " return best.scrollTop < before;"
            + "})()";

    private final CdpConnection connection;
    private final NetworkObserver observer;
    private final String browser;
    private final String teamsTabUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    private BrowserLink(CdpConnection connection, NetworkObserver observer, String browser,
            String teamsTabUrl) {
        this.connection = connection;
        this.observer = observer;
        this.browser = browser;
        this.teamsTabUrl = teamsTabUrl;
    }

    /** Se rattache au navigateur du poste. Lève un {@link BrowserLinkException} qui dit quoi faire. */
    public static BrowserLink attach(int port, TeamsAdapter adapter, Consumer<String> say) {
        return attach(port, adapter, BrowserLink::httpGet, WebSocketCdpConnection::open, say);
    }

    /**
     * Même rattachement, avec la découverte et l'ouverture de socket injectées : c'est ainsi qu'on
     * l'éprouve sans navigateur, contre un faux.
     */
    static BrowserLink attach(int port, TeamsAdapter adapter, Function<String, String> httpGet,
            Function<String, CdpConnection> opener, Consumer<String> say) {
        String listJson;
        String versionJson;
        try {
            versionJson = httpGet.apply(BrowserPort.discoveryUrl(BrowserPort.LOOPBACK, port,
                    "/json/version"));
            listJson = httpGet.apply(BrowserPort.discoveryUrl(BrowserPort.LOOPBACK, port,
                    "/json/list"));
        } catch (BrowserLinkException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BrowserLinkException(BrowserLinkException.BROWSER_NOT_DETECTED,
                    BrowserLaunchAdvice.forSystem(fr.claudegateway.runner.OperatingSystem.current(),
                            port), e);
        }

        List<BrowserTargets.Target> targets = BrowserTargets.parse(listJson);
        Optional<BrowserTargets.Target> teams = BrowserTargets.teamsTab(targets);
        if (teams.isEmpty()) {
            throw new BrowserLinkException(
                    BrowserTargets.looksLikeSignIn(targets) ? BrowserLinkException.NOT_SIGNED_IN
                            : BrowserLinkException.TEAMS_NOT_OPEN,
                    BrowserTargets.looksLikeSignIn(targets) ? BrowserLaunchAdvice.signIn()
                            : BrowserLaunchAdvice.openTeamsTab());
        }

        CdpConnection connection = opener.apply(teams.get().webSocketDebuggerUrl());
        NetworkObserver observer = new NetworkObserver(connection, adapter);
        observer.start();
        String browser = BrowserTargets.browserName(versionJson);
        if (say != null) {
            say.accept("Relié au navigateur du poste" + (browser.isEmpty() ? "" : " (" + browser + ")")
                    + " — onglet Teams observé. Aucun cookie, aucun jeton ne remonte : "
                    + "le runner observe ce que cette fenêtre reçoit déjà.");
        }
        return new BrowserLink(connection, observer, browser, teams.get().url());
    }

    /** Le navigateur tel qu'il se déclare : écrit dans le diagnostic, jamais deviné. */
    public String browser() {
        return browser;
    }

    /** L'adresse de l'onglet relié, <b>sans</b> sa chaîne de requête. */
    public String teamsTabUrl() {
        return teamsTabUrl;
    }

    public NetworkObserver observer() {
        return observer;
    }

    public CdpConnection connection() {
        return connection;
    }

    public boolean isOpen() {
        return connection.isOpen();
    }

    /**
     * Fait défiler la page vers le haut, au plus {@code gestures} fois, en s'arrêtant dès que la
     * page ne bouge plus. <b>Le seul contact avec le DOM de tout le volet.</b>
     *
     * @return le nombre de gestes réellement effectués
     */
    public int scrollUp(int gestures, Sleeper sleeper) {
        int asked = Math.max(0, Math.min(gestures, MAX_SCROLL_GESTURES));
        int done = 0;
        for (int index = 0; index < asked; index++) {
            ObjectNode params = mapper.createObjectNode();
            params.put("expression", SCROLL_UP);
            params.put("returnByValue", true);
            boolean moved = connection.send(CdpCommands.EVALUATE, params)
                    .path("result").path("value").asBoolean(false);
            done++;
            if (!moved) {
                break; // la page ne remonte plus : insister ne ferait que perdre du temps
            }
            if (sleeper != null) {
                sleeper.sleep(SCROLL_SETTLE_MS);
            }
        }
        return done;
    }

    @Override
    public void close() {
        connection.close();
    }

    /** Attente entre deux gestes, injectée pour que les tests ne dorment pas. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis);
    }

    /** L'attente réelle. */
    public static Sleeper realSleeper() {
        return millis -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    private static String httpGet(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(DISCOVERY_TIMEOUT_MS))
                    // La boucle locale ne passe par aucun proxy : sur un poste d'entreprise, le
                    // proxy déclaré avalerait la requête et la liaison échouerait sans raison.
                    .proxy(java.net.ProxySelector.of(null))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(DISCOVERY_TIMEOUT_MS))
                    .GET()
                    .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Découverte interrompue.", e);
        } catch (Exception e) {
            throw new IllegalStateException("Navigateur injoignable.", e);
        }
    }
}
