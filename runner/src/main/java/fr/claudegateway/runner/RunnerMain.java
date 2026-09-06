package fr.claudegateway.runner;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Point d'entrée du runner (F-38 / SF-38-03). Orchestration : résolution de la configuration, du
 * proxy et du truststore (via la JVM), appairage ou réutilisation du jeton stocké, ouverture de la
 * connexion sortante WSS, heartbeat, et arrêt propre au {@code Ctrl-C}.
 *
 * <p>Codes de sortie : {@code 0} arrêt normal, {@code 2} usage/config invalide, {@code 3} appairage
 * refusé/erreur HTTP, {@code 4} jeton refusé sans code de réappairage disponible, {@code 1} erreur
 * inattendue.</p>
 */
public final class RunnerMain {

    private final Console console;

    RunnerMain(Console console) {
        this.console = console;
    }

    public static void main(String[] args) {
        System.exit(new RunnerMain(new Console()).execute(args, System.getenv()));
    }

    int execute(String[] args, java.util.Map<String, String> env) {
        RunnerConfig config;
        try {
            config = RunnerConfig.resolve(args, env);
        } catch (RunnerConfig.ConfigException e) {
            console.error(e.getMessage());
            console.info("Usage : java -jar claude-runner.jar --gateway <url> --workspace <racine> "
                    + "--code <code-appairage> [--label <libellé>] [--heartbeat-interval <s>] "
                    + "[--no-bash] [--transport auto|websocket|polling]");
            return 2;
        }

        console.info("Runner claude-gateway (F-38).");
        console.info("Gateway   : " + config.gatewayBaseUrl());
        console.info("Workspace : " + config.workspaceRoot());
        // Le mode est dit dans les DEUX sens (F-38 / SF-38-19, D4) : le défaut d'avant venait de ce
        // qu'un runner restreint ne se signalait pas — on le découvrait au premier refus.
        console.info(config.allowBash()
                ? "Commandes : autorisées (chacune demande votre autorisation à l'écran)"
                : "Commandes : refusées (--no-bash) — seuls les outils fichiers sont disponibles");

        ProxyResolver proxyResolver = ProxyResolver.fromEnv(env);
        if (proxyResolver.hasProxy()) {
            console.info("Proxy d'entreprise détecté (HTTPS_PROXY/HTTP_PROXY).");
        }
        HttpClient httpClient = buildHttpClient(proxyResolver);

        Path home = Path.of(System.getProperty("user.home", "."));
        TokenStore tokenStore = new TokenStore(config.workspaceRoot(), home);

        String token;
        try {
            token = obtainToken(config, tokenStore, httpClient);
        } catch (RunnerConfig.ConfigException e) {
            console.error(e.getMessage());
            return 2;
        } catch (PairingClient.PairingException e) {
            console.error(e.getMessage());
            return 3;
        }

        // Repli de transport (SF-38-09) : le WebSocket d'abord, le long-polling HTTP si le reseau le
        // tue. Une session est portee par l'un OU l'autre, jamais les deux.
        TransportFallbackPolicy fallbackPolicy = new TransportFallbackPolicy(config.transport());
        RunnerConnection connection = new RunnerConnection(httpClient, config, console, fallbackPolicy);
        AtomicReference<PollingConnection> polling = new AtomicReference<>();
        AtomicBoolean shuttingDown = new AtomicBoolean(false);
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shuttingDown.set(true);
            connection.stop();
            PollingConnection active = polling.get();
            if (active != null) {
                active.stop();
            }
            try {
                stopped.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "runner-shutdown"));

        console.info("Appuyez sur Ctrl-C pour arrêter le runner.");
        try {
            runSession(token, config, console, httpClient, connection, fallbackPolicy, polling,
                    shuttingDown);
            return 0;
        } catch (RunnerConnection.AuthRejectedException e) {
            console.warn(e.getMessage() + " — jeton effacé.");
            tokenStore.clear();
            if (config.pairingCode() != null) {
                console.info("Réappairage avec le code fourni…");
                try {
                    String fresh = pairAndStore(config, tokenStore, httpClient);
                    runSession(fresh, config, console, httpClient, connection, fallbackPolicy, polling,
                            shuttingDown);
                    return 0;
                } catch (PairingClient.PairingException pe) {
                    console.error(pe.getMessage());
                    return 3;
                } catch (RunnerConnection.AuthRejectedException pe) {
                    console.error("Jeton fraîchement appairé également refusé : " + pe.getMessage());
                    return 4;
                }
            }
            console.error("Aucun code d'appairage fourni pour réappairer. Relancez avec --code.");
            return 4;
        } catch (RuntimeException e) {
            console.error("Erreur inattendue : " + e.getMessage());
            return 1;
        } finally {
            stopped.countDown();
        }
    }

    /**
     * Deroule la session sur le transport retenu (F-38 / SF-38-09) : WebSocket, puis repli
     * long-polling si la boucle WS a renonce faute de tenir sur ce reseau. Un {@code Ctrl-C} pendant
     * la phase WebSocket n'enchaine PAS sur le repli : on s'arrete, c'est ce qui a ete demande.
     */
    private void runSession(String token, RunnerConfig config, Console console, HttpClient httpClient,
            RunnerConnection connection, TransportFallbackPolicy fallbackPolicy,
            AtomicReference<PollingConnection> polling, AtomicBoolean shuttingDown) {
        if (!fallbackPolicy.startsWithPolling()) {
            connection.run(token);
            if (!connection.fellBackToPolling()) {
                return;
            }
        } else {
            console.info("Transport imposé : long-polling HTTP (--transport polling).");
        }
        if (shuttingDown.get()) {
            return;
        }
        PollingConnection fallback =
                new PollingConnection(new HttpPollingClient(httpClient, config, token), config, console);
        polling.set(fallback);
        if (shuttingDown.get()) {
            // Arret demande pendant le montage : ne pas ouvrir une boucle que personne n'arretera.
            return;
        }
        fallback.run();
    }

    private String obtainToken(RunnerConfig config, TokenStore tokenStore, HttpClient httpClient) {
        Optional<StoredToken> stored = tokenStore.load();
        if (stored.isPresent()) {
            console.info("Jeton runner réutilisé (" + tokenStore.tokenFile() + ").");
            return stored.get().token();
        }
        if (config.pairingCode() == null) {
            throw new RunnerConfig.ConfigException(
                    "Aucun jeton stocké et aucun --code fourni : impossible de s'appairer.");
        }
        return pairAndStore(config, tokenStore, httpClient);
    }

    private String pairAndStore(RunnerConfig config, TokenStore tokenStore, HttpClient httpClient) {
        console.info("Appairage auprès de " + config.pairUrl() + "…");
        PairingClient client = new PairingClient(httpClient);
        StoredToken token = client.pair(config.pairUrl(), config.pairingCode(), config.label());
        tokenStore.save(token);
        console.info("Appairage réussi — jeton stocké dans " + tokenStore.tokenFile() + ".");
        return token.token();
    }

    private static HttpClient buildHttpClient(ProxyResolver proxyResolver) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(20));
        // Proxy : variables d'environnement d'entreprise si présentes, sinon sélecteur JVM par défaut
        // (propriétés système http(s).proxyHost). Le truststore reste géré par la JVM.
        builder.proxy(proxyResolver.hasProxy() ? proxyResolver : ProxySelector.getDefault());
        return builder.build();
    }
}
