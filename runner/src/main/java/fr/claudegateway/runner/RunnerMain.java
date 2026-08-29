package fr.claudegateway.runner;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

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
                    + "--code <code-appairage> [--label <libellé>] [--heartbeat-interval <s>]");
            return 2;
        }

        console.info("Runner claude-gateway (F-38).");
        console.info("Gateway   : " + config.gatewayBaseUrl());
        console.info("Workspace : " + config.workspaceRoot());

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

        RunnerConnection connection = new RunnerConnection(httpClient, config, console);
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            connection.stop();
            try {
                stopped.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "runner-shutdown"));

        console.info("Appuyez sur Ctrl-C pour arrêter le runner.");
        try {
            connection.run(token);
            return 0;
        } catch (RunnerConnection.AuthRejectedException e) {
            console.warn(e.getMessage() + " — jeton effacé.");
            tokenStore.clear();
            if (config.pairingCode() != null) {
                console.info("Réappairage avec le code fourni…");
                try {
                    String fresh = pairAndStore(config, tokenStore, httpClient);
                    connection.run(fresh);
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
