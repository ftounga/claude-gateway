package fr.claudegateway.runner;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration du runner (F-38 / SF-38-03), résolue à partir des arguments CLI et de
 * l'environnement. Priorité : <b>argument CLI &gt; variable d'environnement</b>.
 *
 * <p>Arguments : {@code --gateway <url>}, {@code --workspace <racine>}, {@code --code <code>},
 * {@code --label <libellé>}, {@code --heartbeat-interval <secondes>}. Équivalents d'environnement :
 * {@code CLAUDE_RUNNER_GATEWAY}, {@code CLAUDE_RUNNER_WORKSPACE}, {@code CLAUDE_RUNNER_CODE},
 * {@code CLAUDE_RUNNER_LABEL}, {@code CLAUDE_RUNNER_HEARTBEAT_INTERVAL}.</p>
 *
 * <p>Cette classe ne fait aucune I/O réseau : elle valide le format et l'existence du workspace, et
 * dérive l'URI WSS. Elle est intégralement testable unitairement.</p>
 */
public final class RunnerConfig {

    private final String gatewayBaseUrl;
    private final Path workspaceRoot;
    private final String pairingCode;
    private final String label;
    private final Duration heartbeatInterval;

    private RunnerConfig(String gatewayBaseUrl, Path workspaceRoot, String pairingCode,
            String label, Duration heartbeatInterval) {
        this.gatewayBaseUrl = gatewayBaseUrl;
        this.workspaceRoot = workspaceRoot;
        this.pairingCode = pairingCode;
        this.label = label;
        this.heartbeatInterval = heartbeatInterval;
    }

    /**
     * Analyse et valide la configuration. En cas de paramètre invalide ou manquant, lève une
     * {@link ConfigException} portant le code de sortie {@code 2} (erreur d'usage).
     */
    public static RunnerConfig resolve(String[] args, Map<String, String> env) {
        Map<String, String> cli = parseArgs(args);

        String gateway = pick(cli, "gateway", env, "CLAUDE_RUNNER_GATEWAY");
        String workspace = pick(cli, "workspace", env, "CLAUDE_RUNNER_WORKSPACE");
        String code = pick(cli, "code", env, "CLAUDE_RUNNER_CODE");
        String label = pick(cli, "label", env, "CLAUDE_RUNNER_LABEL");
        String heartbeat = pick(cli, "heartbeat-interval", env, "CLAUDE_RUNNER_HEARTBEAT_INTERVAL");

        if (gateway == null) {
            throw new ConfigException("--gateway est requis (URL de la gateway, ex: https://host/api)");
        }
        String normalizedGateway = normalizeGateway(gateway);

        if (workspace == null) {
            throw new ConfigException("--workspace est requis (racine du projet à exposer)");
        }
        Path root = Path.of(workspace).toAbsolutePath().normalize();
        if (!Files.exists(root)) {
            throw new ConfigException("--workspace n'existe pas : " + root);
        }
        if (!Files.isDirectory(root)) {
            throw new ConfigException("--workspace n'est pas un dossier : " + root);
        }

        String normalizedCode = (code == null || code.isBlank()) ? null : code.trim().toUpperCase();

        String normalizedLabel = label == null ? null : label.trim();
        if (normalizedLabel != null && normalizedLabel.length() > 100) {
            throw new ConfigException("--label dépasse 100 caractères");
        }

        Duration hb = Duration.ofSeconds(30);
        if (heartbeat != null && !heartbeat.isBlank()) {
            long seconds;
            try {
                seconds = Long.parseLong(heartbeat.trim());
            } catch (NumberFormatException e) {
                throw new ConfigException("--heartbeat-interval doit être un entier de secondes");
            }
            if (seconds <= 0) {
                throw new ConfigException("--heartbeat-interval doit être strictement positif");
            }
            hb = Duration.ofSeconds(seconds);
        }

        return new RunnerConfig(normalizedGateway, root, normalizedCode, normalizedLabel, hb);
    }

    /** URL absolue de l'endpoint d'appairage, {@code {gateway}/runner/pair}. */
    public String pairUrl() {
        return gatewayBaseUrl + "/runner/pair";
    }

    /**
     * URI du canal WebSocket, dérivée de la gateway : schéma {@code https→wss} / {@code http→ws},
     * chemin {@code /runner/ws}, jeton porté en query param {@code token}.
     */
    public URI webSocketUri(String token) {
        URI base = URI.create(gatewayBaseUrl);
        String wsScheme = switch (base.getScheme()) {
            case "https" -> "wss";
            case "http" -> "ws";
            default -> throw new ConfigException("Schéma de gateway non supporté : " + base.getScheme());
        };
        String authority = base.getRawAuthority();
        String path = base.getRawPath() == null ? "" : base.getRawPath();
        // Le jeton runner est du Base64URL (SF-38-01) : sûr en query param sans ré-encodage.
        return URI.create(wsScheme + "://" + authority + path + "/runner/ws?token=" + token);
    }

    public String gatewayBaseUrl() {
        return gatewayBaseUrl;
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    /** Code d'appairage, ou {@code null} si absent (auquel cas un jeton stocké est requis). */
    public String pairingCode() {
        return pairingCode;
    }

    public String label() {
        return label;
    }

    public Duration heartbeatInterval() {
        return heartbeatInterval;
    }

    private static String normalizeGateway(String raw) {
        String g = raw.trim();
        while (g.endsWith("/")) {
            g = g.substring(0, g.length() - 1);
        }
        URI uri;
        try {
            uri = URI.create(g);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("--gateway n'est pas une URL valide : " + raw);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new ConfigException("--gateway doit être une URL absolue (http(s)://host[/path]) : " + raw);
        }
        if (!uri.getScheme().equals("http") && !uri.getScheme().equals("https")) {
            throw new ConfigException("--gateway doit utiliser http ou https : " + raw);
        }
        return g;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        if (args == null) {
            return map;
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null || !arg.startsWith("--")) {
                throw new ConfigException("Argument inattendu : " + arg);
            }
            String key;
            String value;
            int eq = arg.indexOf('=');
            if (eq >= 0) {
                key = arg.substring(2, eq);
                value = arg.substring(eq + 1);
            } else {
                key = arg.substring(2);
                if (i + 1 >= args.length) {
                    throw new ConfigException("Valeur manquante pour --" + key);
                }
                value = args[++i];
            }
            map.put(key, value);
        }
        return map;
    }

    private static String pick(Map<String, String> cli, String cliKey, Map<String, String> env, String envKey) {
        String fromCli = cli.get(cliKey);
        if (fromCli != null && !fromCli.isBlank()) {
            return fromCli;
        }
        String fromEnv = env == null ? null : env.get(envKey);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return null;
    }

    /** Erreur de configuration : usage invalide, code de sortie {@code 2}. */
    public static final class ConfigException extends RuntimeException {
        public ConfigException(String message) {
            super(message);
        }
    }
}
