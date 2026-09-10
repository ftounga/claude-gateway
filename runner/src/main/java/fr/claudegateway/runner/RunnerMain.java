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
        return execute(args, env, Path.of(System.getProperty("user.dir", ".")),
                Path.of(System.getProperty("user.home", ".")));
    }

    int execute(String[] args, java.util.Map<String, String> env, Path currentDir, Path home) {
        // Reprise (F-46 / SF-46-01) : la mémoire est cherchée AVANT la résolution, puisque c'est
        // elle qui peut fournir la racine — on ne peut donc pas partir du workspace pour la trouver.
        SessionMemory.Located memory = SessionMemory.locate(currentDir, home).orElse(null);

        RunnerConfig config;
        try {
            config = RunnerConfig.resolve(args, env, memory);
        } catch (RunnerConfig.ConfigException e) {
            console.error(e.getMessage());
            console.info("Usage : java -jar claude-runner.jar --gateway <url> --root <racine du poste> "
                    + "--code <code-appairage> [--label <libellé>] [--heartbeat-interval <s>] "
                    + "[--no-bash] [--transport auto|websocket|polling]");
            console.info("La racine du poste est le dossier sous lequel vivent vos projets "
                    + "(par exemple ~/dev) : un seul appairage y suffit pour tous.");
            console.info("Reprise : java -jar claude-runner.jar — sans argument, depuis un poste "
                    + "déjà appairé.");
            return 2;
        }

        console.info("Runner claude-gateway (F-38).");
        if (config.resumedFrom() != null) {
            console.info(ResumeMessages.resumedFrom(config.resumedFrom()));
        }
        console.info("Gateway   : " + config.gatewayBaseUrl());
        console.info("Poste     : " + config.hostRoot() + " (racine — chaque tour est confiné au "
                + "dossier du projet qu'il vise)");
        // Le mode est dit dans les DEUX sens (F-38 / SF-38-19, D4) : le défaut d'avant venait de ce
        // qu'un runner restreint ne se signalait pas — on le découvrait au premier refus. Et il est
        // dit ICI, une seule fois (SF-38-26, D1) : il vient de la configuration, il est connu avant
        // toute connexion, et il ne changera plus de la vie du processus.
        commandModeLines(config.allowBash()).forEach(console::info);

        ProxyResolver proxyResolver = ProxyResolver.fromEnv(env);

        // Déclaration de transparence (F-57 / SF-57-01) : ce que fait ce programme, sous quels
        // droits (SF-38-18), par quelle route, et ce qu'il ne cherche pas. Un bloc, pas des lignes
        // dispersées : ces informations répondent toutes à la même question — « qu'est-ce que ce
        // programme fait sur ma machine ? » — et se lisent ensemble ou pas du tout (D1).
        Privileges privileges = Privileges.detect();
        StartupDisclosure.lines(privileges, proxyResolver.route()).forEach(console::info);
        // F-55 / SF-55-03 : un NO_PROXY à la forme Windows est accepté, et c'est dit — parce que le
        // MÊME NO_PROXY sera lu par `curl` dans le terminal d'à côté, où il ne marchera pas. Ce
        // n'est pas une erreur : rien n'est cassé ici, et la ligne n'apparaît que dans ce cas (D2).
        String noProxyNotice = proxyResolver.noProxyNotice();
        if (noProxyNotice != null) {
            console.info(noProxyNotice);
        }
        if (privileges.elevated()) {
            console.error("Ce runner tourne en root : Claude agira avec les droits de "
                    + "l'administrateur sur cette machine.");
            console.info("Lancez-le plutôt avec votre compte habituel, sauf si vous savez pourquoi "
                    + "vous faites autrement (conteneur, projet appartenant à root).");
        }

        // Plus de ligne « Proxy d'entreprise détecté » ici : la route est désormais dite dans le bloc
        // de transparence ci-dessus, avec son adresse expurgée et la variable qui l'a décidée.
        HttpClient httpClient = buildHttpClient(proxyResolver);

        // Contrôle de vol (SF-38-25) : la gateway est-elle joignable depuis CE terminal ? La question
        // se pose avant l'appairage, parce que sa réponse n'a rien de métier — et qu'un échec réseau
        // survenu au milieu de l'appairage mêlait deux sujets sans rapport (D4).
        String unreachable = new NetworkPreflight(httpClient, OperatingSystem.current())
                .check(config.gatewayBaseUrl());
        if (unreachable != null) {
            console.error(unreachable);
            return 5;
        }
        console.info("Réseau    : gateway joignable");

        // Interception TLS (F-57 / SF-57-02) : APRÈS le contrôle de vol, parce que sonder une
        // gateway injoignable n'apprendrait rien. La sonde ne décide de rien — elle se tait au
        // moindre doute, et n'a le droit de casser ni le démarrage, ni le code de sortie.
        TlsProbe.forRuntime(proxyResolver).inspect(config.gatewayBaseUrl()).ifPresent(console::info);

        TokenStore tokenStore = new TokenStore(config.hostRoot(), home);

        String token;
        try {
            token = obtainToken(config, tokenStore, httpClient, home);
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
                    String fresh = pairAndStore(config, tokenStore, httpClient, home);
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
     * Ce que la console dit de l'exécution de commandes, et l'<b>unique</b> endroit qui le dit
     * (F-38 / SF-38-26, D1).
     *
     * <p>Elle ne cite que le drapeau qui <b>agit</b>. Jusqu'ici la pile d'outils annonçait un second
     * message attribuant l'état à {@code --allow-bash}, qui n'a plus d'effet depuis SF-38-19 : dans
     * la branche restreinte, l'utilisateur recevait donc une consigne de réparation qui ne répare
     * pas — relancer avec {@code --allow-bash} en gardant {@code --no-bash} ne change rien, la
     * restriction l'emporte (SF-38-19, D3).</p>
     *
     * <p>{@code --allow-bash} reste accepté sans effet et <b>sans avertissement</b> : la
     * compatibilité des lignes de commande d'hier n'est pas touchée, seule leur description l'est.</p>
     */
    static java.util.List<String> commandModeLines(boolean allowBash) {
        if (allowBash) {
            return java.util.List.of(
                    "Commandes : autorisées — chacune demande votre autorisation à l'écran.");
        }
        return java.util.List.of(
                "Commandes : refusées (--no-bash) — seuls les outils fichiers sont disponibles.",
                "Relancez sans --no-bash pour autoriser l'exécution de commandes.");
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

    private String obtainToken(RunnerConfig config, TokenStore tokenStore, HttpClient httpClient,
            Path home) {
        Optional<StoredToken> stored = tokenStore.load();
        if (stored.isPresent()) {
            console.info("Jeton runner réutilisé (" + tokenStore.tokenFile() + ").");
            return stored.get().token();
        }
        if (config.pairingCode() == null) {
            // Expiré et « jamais appairé ici » n'appellent pas le même geste : le message les
            // distingue (F-46 / SF-46-01). Et dans les deux cas il DIT ce qui manque — aucun code
            // n'est redemandé en silence, aucun appairage n'est tenté sans code (D3).
            throw new RunnerConfig.ConfigException(ResumeMessages.cannotResume(
                    tokenStore.tokenFile(), tokenStore.expiredAt().orElse(null)));
        }
        return pairAndStore(config, tokenStore, httpClient, home);
    }

    private String pairAndStore(RunnerConfig config, TokenStore tokenStore, HttpClient httpClient,
            Path home) {
        console.info("Appairage auprès de " + config.pairUrl() + "…");
        PairingClient client = new PairingClient(httpClient);
        StoredToken token = client.pair(config.pairUrl(), config.pairingCode(), config.label(),
                workspaceFolderName(config),
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT),
                Privileges.detect().elevated());
        tokenStore.save(token);
        console.info("Appairage réussi — jeton stocké dans " + tokenStore.tokenFile() + ".");
        rememberSession(config, home);
        return token.token();
    }

    /**
     * Mémorise de quoi <b>reprendre</b> (F-46 / SF-46-01) : la passerelle et la racine, écrites à
     * côté du jeton et sous le compte de l'utilisateur — jamais le jeton lui-même.
     *
     * <p>Best-effort assumé : un appairage qui vient de réussir ne doit pas échouer parce qu'un
     * dossier est en lecture seule. On le dit, et on continue (D4).</p>
     */
    private void rememberSession(RunnerConfig config, Path home) {
        SessionMemory memory = new SessionMemory(config.gatewayBaseUrl(),
                config.hostRoot().toString(), java.time.OffsetDateTime.now());
        java.util.List<Path> written = SessionMemory.remember(memory, config.hostRoot(), home);
        if (written.isEmpty()) {
            console.warn("Impossible de mémoriser la configuration de reprise : le prochain "
                    + "lancement redemandera --gateway et --workspace.");
            return;
        }
        console.info("Reprise mémorisée (" + written.get(0)
                + ") — relancez ensuite sans aucun argument.");
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

    /**
     * Nom de la racine du POSTE — le <b>dernier segment</b>, jamais le chemin absolu (SF-38-15).
     * C'est ce que la gateway affichera ; elle n'a besoin de rien de plus.
     */
    private static String workspaceFolderName(RunnerConfig config) {
        java.nio.file.Path name = config.hostRoot().getFileName();
        return name == null ? null : name.toString();
    }
}
