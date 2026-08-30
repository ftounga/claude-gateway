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
 * {@code --label <libellé>}, {@code --heartbeat-interval <secondes>},
 * {@code --transport auto|websocket|polling}. Équivalents d'environnement :
 * {@code CLAUDE_RUNNER_GATEWAY}, {@code CLAUDE_RUNNER_WORKSPACE}, {@code CLAUDE_RUNNER_CODE},
 * {@code CLAUDE_RUNNER_LABEL}, {@code CLAUDE_RUNNER_HEARTBEAT_INTERVAL},
 * {@code CLAUDE_RUNNER_TRANSPORT}.</p>
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
    private final boolean allowBash;
    private final Transport transport;

    private RunnerConfig(String gatewayBaseUrl, Path workspaceRoot, String pairingCode,
            String label, Duration heartbeatInterval, boolean allowBash, Transport transport) {
        this.gatewayBaseUrl = gatewayBaseUrl;
        this.workspaceRoot = workspaceRoot;
        this.pairingCode = pairingCode;
        this.label = label;
        this.heartbeatInterval = heartbeatInterval;
        this.allowBash = allowBash;
        this.transport = transport;
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
        String allowBash = pick(cli, "allow-bash", env, "CLAUDE_RUNNER_ALLOW_BASH");
        String transport = pick(cli, "transport", env, "CLAUDE_RUNNER_TRANSPORT");

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

        return new RunnerConfig(normalizedGateway, root, normalizedCode, normalizedLabel, hb,
                isTrue(allowBash), Transport.parse(transport));
    }

    /** URL absolue de l'endpoint d'appairage, {@code {gateway}/runner/pair}. */
    public String pairUrl() {
        return gatewayBaseUrl + "/runner/pair";
    }

    /** Long-poll du repli de transport (F-38 / SF-38-09), {@code {gateway}/runner/poll}. */
    public String pollUrl() {
        return gatewayBaseUrl + "/runner/poll";
    }

    /** Dépôt des trames sortantes du repli, {@code {gateway}/runner/send}. */
    public String sendUrl() {
        return gatewayBaseUrl + "/runner/send";
    }

    /** Arrêt propre du repli, {@code {gateway}/runner/disconnect}. */
    public String disconnectUrl() {
        return gatewayBaseUrl + "/runner/disconnect";
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

    /**
     * Exécution de commandes autorisée sur cette machine (F-38 / SF-38-07). <b>Faux par défaut</b> :
     * démarrer un runner autorise la lecture et l'écriture de fichiers, pas l'exécution de commandes
     * arbitraires. Le drapeau se pose avec {@code --allow-bash} ou {@code CLAUDE_RUNNER_ALLOW_BASH=true}.
     */
    public boolean allowBash() {
        return allowBash;
    }

    /**
     * Transport demandé (F-38 / SF-38-09). {@code AUTO} par défaut : WebSocket d'abord, repli
     * long-polling si le réseau le tue. {@code WEBSOCKET} ne se replie jamais, {@code POLLING} ne
     * tente même pas la socket (réseau déjà connu comme hostile).
     */
    public Transport transport() {
        return transport;
    }

    /** Un drapeau vaut « vrai » sur {@code true}, {@code 1}, {@code yes} ou {@code oui}. */
    private static boolean isTrue(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        return v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("oui");
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

    /**
     * Arguments qui n'attendent pas de valeur : {@code --allow-bash} seul vaut {@code true}, sans
     * avaler l'argument suivant. La forme {@code --allow-bash=false} reste acceptée.
     */
    private static final java.util.Set<String> BOOLEAN_FLAGS = java.util.Set.of("allow-bash");

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
                if (BOOLEAN_FLAGS.contains(key)) {
                    map.put(key, "true");
                    continue;
                }
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

    /**
     * Transport du canal runner (F-38 / SF-38-09). Le repli existe parce qu'un proxy d'entreprise
     * peut refuser l'{@code Upgrade} WebSocket — ou l'accepter puis couper la socket aussitôt.
     */
    public enum Transport {
        /** WebSocket d'abord, repli long-polling après des échecs répétés de transport. */
        AUTO,
        /** WebSocket uniquement : aucun repli, l'échec reste visible. */
        WEBSOCKET,
        /** Long-polling HTTP d'emblée, sans tenter la socket. */
        POLLING;

        /** Valeur par défaut ({@code AUTO}) si rien n'est fourni ; refuse toute valeur inconnue. */
        static Transport parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return AUTO;
            }
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "auto" -> AUTO;
                case "websocket", "ws" -> WEBSOCKET;
                case "polling", "http", "long-polling" -> POLLING;
                default -> throw new ConfigException(
                        "--transport doit valoir auto, websocket ou polling : " + raw);
            };
        }
    }

    /** Erreur de configuration : usage invalide, code de sortie {@code 2}. */
    public static final class ConfigException extends RuntimeException {
        public ConfigException(String message) {
            super(message);
        }
    }
}
