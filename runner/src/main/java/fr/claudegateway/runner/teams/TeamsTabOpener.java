package fr.claudegateway.runner.teams;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import fr.claudegateway.runner.diag.RunnerDiag;

/**
 * <b>Le Chrome managé garde un onglet Teams ouvert</b> (F-122 / SF-122-07).
 *
 * <p>Le Chrome managé ouvre {@code teams.microsoft.com} <b>au lancement</b> (SF-122-01, la ligne de
 * commande porte l'URL). Mais si l'utilisateur <b>ferme</b> cet onglet (en laissant Chrome ouvert),
 * rien ne le rouvrait : la sonde de la Vigie échouait en {@code TEAMS_NOT_OPEN}, « Teams connecté »
 * restait {@code UNKNOWN} et le Radar ne pouvait plus observer. Cette garde <b>maintient</b> l'onglet :
 * à chaque relevé, si aucun onglet Teams (ni page d'identification Teams) n'est présent, elle le
 * <b>rouvre</b>.</p>
 *
 * <h2>Pourquoi l'endpoint de débogage, pas une commande CDP</h2>
 * <p>Quand il n'y a plus d'onglet Teams, il n'existe aucune cible à laquelle envoyer
 * {@code Page.navigate}. On ouvre donc un onglet par l'endpoint HTTP de débogage
 * ({@code PUT /json/new?<url>}) — sur la boucle locale, <b>sans proxy</b>, comme la découverte
 * existante ({@link BrowserLink#httpGet}). On ne touche jamais aux cookies ni aux jetons.</p>
 *
 * <h2>Idempotent et best-effort strict</h2>
 * <p>Si un onglet Teams est déjà là, on ne fait rien (jamais de spam d'onglets). Si une page
 * d'identification Teams est là, on ne fait rien non plus : c'est le relogin (SF-122-03,
 * {@code reveal()}/{@code remask()}) qui prend le relais, pas un nouvel onglet. Toute erreur est
 * <b>avalée</b> (jamais fatale pour la boucle Vigie ni le heartbeat) et retentée au relevé suivant.</p>
 */
public final class TeamsTabOpener implements VigieLoop.TabGuard {

    /** Timeout de la découverte et de l'ouverture : la boucle locale répond instantanément. */
    static final long HTTP_TIMEOUT_MS = 2_000L;

    /** Comment lister les cibles du navigateur ({@code GET /json/list}). Injecté pour l'éprouver. */
    @FunctionalInterface
    interface Lister {
        String list();
    }

    /** Comment ouvrir un onglet ({@code PUT /json/new?<url>}). Injecté pour l'éprouver sans navigateur. */
    @FunctionalInterface
    interface Opener {
        void open(String url);
    }

    private final Lister lister;
    private final Opener opener;
    private final Consumer<String> say;

    TeamsTabOpener(Lister lister, Opener opener, Consumer<String> say) {
        this.lister = lister;
        this.opener = opener;
        this.say = say;
    }

    /** La garde réelle branchée sur le port de débogage du Chrome managé (boucle locale, sans proxy). */
    public static TeamsTabOpener real(int port, Consumer<String> say) {
        return new TeamsTabOpener(
                () -> BrowserLink.httpGet(BrowserPort.discoveryUrl(BrowserPort.LOOPBACK, port,
                        "/json/list")),
                url -> openTab(port, url),
                say);
    }

    /**
     * S'assure qu'un onglet Teams est ouvert ; le rouvre sinon. Best-effort strict : ne lève jamais.
     */
    @Override
    public void ensureTeamsTab() {
        try {
            List<BrowserTargets.Target> targets = BrowserTargets.parse(lister.list());
            if (BrowserTargets.teamsTab(targets).isPresent()
                    || BrowserTargets.looksLikeSignIn(targets)) {
                // Un onglet Teams (ou une page d'identification Teams) est déjà là : rien à faire.
                // Une identification est le domaine du relogin (SF-122-03), pas d'un onglet de plus.
                return;
            }
            opener.open(BrowserLaunchAdvice.TEAMS_URL);
            // Diagnostic F-132 : on a rouvert l'onglet Teams — un événement, jamais un contenu.
            RunnerDiag.info("chrome", "teams_tab", null,
                    Map.of("result", "reopened", "url", "teams.microsoft.com"));
            if (say != null) {
                say.accept("Vigie : l'onglet Teams avait été fermé — rouvert dans le Chrome managé "
                        + "pour que l'observation reprenne.");
            }
        } catch (RuntimeException e) {
            // Port muet, réponse illisible, ouverture refusée : jamais fatal, retenté au prochain relevé.
            RunnerDiag.warn("chrome", "teams_tab", null,
                    Map.of("result", "error", "reason", "unreachable"));
        }
    }

    /** Ouvre un onglet via l'endpoint de débogage ({@code PUT /json/new?<url>}), boucle locale sans proxy. */
    private static void openTab(int port, String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(HTTP_TIMEOUT_MS))
                    .proxy(java.net.ProxySelector.of(null))
                    .build();
            String target = BrowserPort.discoveryUrl(BrowserPort.LOOPBACK, port, "/json/new?" + url);
            HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofMillis(HTTP_TIMEOUT_MS))
                    // Chrome moderne exige PUT sur /json/new ; le corps vide suffit.
                    .method("PUT", HttpRequest.BodyPublishers.ofString("", StandardCharsets.UTF_8))
                    .build();
            client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ouverture d'onglet interrompue.", e);
        } catch (Exception e) {
            throw new IllegalStateException("Ouverture d'onglet impossible.", e);
        }
    }
}
