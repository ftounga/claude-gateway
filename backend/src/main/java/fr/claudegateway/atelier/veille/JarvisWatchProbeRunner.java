package fr.claudegateway.atelier.veille;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * <b>Lancer une sonde sur le poste</b> (F-230 / SF-230-01, D2/D3) : une <b>commande</b>, jamais un tour d'agent.
 * Relit d'abord le script épinglé (empreinte), lance la ligne validée, lit la liste normalisée.
 *
 * <p>Utilisé au test devant le PO (SF-230-01) et à chaque passage planifié (SF-230-02). Chaque appel est tracé
 * au journal du runner (comme les commandes de la gateway, {@code IntegriteInspection}). Aucun contenu de sortie
 * n'est journalisé.</p>
 */
@Component
public class JarvisWatchProbeRunner {

    /** Délai d'une sonde : un appel d'API et un peu de mise en forme. */
    public static final long PROBE_TIMEOUT_MS = 60_000L;

    /** Outil inscrit au journal du runner pour une sonde. */
    static final String AUDIT_TOOL = "veille_sonde";

    private static final Logger log = LoggerFactory.getLogger(JarvisWatchProbeRunner.class);

    /** L'issue d'une sonde : la liste, ou un code d'erreur fermé et un message lisible. */
    public record Outcome(List<JarvisWatchItems.Item> items, String errorCode, String errorMessage, long durationMs) {

        public boolean ok() {
            return errorCode == null;
        }

        static Outcome failure(String code, String message, long durationMs) {
            return new Outcome(List.of(), code, message, durationMs);
        }
    }

    /** Codes d'échec d'une sonde (liste fermée : jamais de texte libre en base). */
    public static final String ERR_OFFLINE = "host_offline";
    public static final String ERR_SCRIPT_CHANGED = "probe_changed";
    public static final String ERR_SCRIPT_MISSING = "probe_missing";
    public static final String ERR_EXIT = "probe_exit";
    public static final String ERR_OUTPUT = "probe_output";
    public static final String ERR_TRANSPORT = "probe_transport";
    /** F-247 / SF-247-07 — le poste a refusé ou interrompu le relevé pour laisser passer le PO. */
    public static final String ERR_YIELDED = "yielded";
    /** Délai d'une sonde de relevé (porte Z5). */
    public static final long RELEVE_TIMEOUT_MS = 20_000L;
    static final String AUDIT_TOOL_RELEVE = "releve_sonde";

    private final RunnerToolGateway gateway;
    private RunnerAuditService audit;

    public JarvisWatchProbeRunner(RunnerToolGateway gateway) {
        this.gateway = gateway;
    }

    @Autowired(required = false)
    public void setAudit(RunnerAuditService audit) {
        this.audit = audit;
    }

    /**
     * La cible d'une sonde : le <b>dossier du sujet</b> sur son poste — jamais le worktree d'un chantier, que la
     * veille survivrait.
     */
    public static RunnerTarget targetOf(Workspace workspace) {
        return new RunnerTarget(workspace.getHostId(), workspace.getId(),
                workspace.getProjectPath() == null ? "" : workspace.getProjectPath());
    }

    /**
     * F-273 / SF-273-01 — la relecture d'un script : son contenu, ou l'échec <b>tel qu'il est</b> (un runner muet
     * n'est pas un script introuvable).
     */
    public record ScriptRead(String content, String errorCode, String errorMessage) {

        public boolean ok() {
            return errorCode == null;
        }
    }

    /** Relit un script de sonde sur le poste ; vide si illisible. */
    public Optional<String> readScript(UUID userId, Workspace workspace, String path) {
        ScriptRead read = readScriptChecked(userId, workspace, path);
        return read.ok() ? Optional.ofNullable(read.content()) : Optional.empty();
    }

    /**
     * Relit un script de sonde et dit <b>pourquoi</b> elle échoue (F-273 / SF-273-01) : runner déconnecté
     * ({@link #ERR_OFFLINE}), runner muet ou illisible ({@link #ERR_TRANSPORT}), refus du runner lui-même — fichier
     * absent, chemin refusé — ({@link #ERR_SCRIPT_MISSING}, avec son message).
     */
    public ScriptRead readScriptChecked(UUID userId, Workspace workspace, String path) {
        RunnerTarget target = targetOf(workspace);
        String callId = "veille-" + UUID.randomUUID();
        RunnerCallResult result;
        try {
            result = gateway.readFile(target, callId, path);
        } catch (RuntimeException e) {
            log.debug("Script de sonde illisible ({})", e.getClass().getSimpleName());
            return new ScriptRead(null, ERR_TRANSPORT, NO_ANSWER);
        }
        record(userId, target, callId, AUDIT_TOOL, "read_file", path, result);
        if (result == null) {
            return new ScriptRead(null, ERR_TRANSPORT, NO_ANSWER);
        }
        if (result.ok()) {
            return result.content() == null
                    ? new ScriptRead(null, ERR_SCRIPT_MISSING, "Le script de sonde est vide ou illisible sur le poste.")
                    : new ScriptRead(result.content(), null, null);
        }
        String code = result.errorCode();
        if (RunnerErrorCodes.RUNNER_UNAVAILABLE.equals(code)) {
            return new ScriptRead(null, ERR_OFFLINE, "Le poste ne répond pas (runner déconnecté) : la sonde n'a pas "
                    + "pu être lancée. Elle n'est pas en cause.");
        }
        if (RunnerErrorCodes.RUNNER_TIMEOUT.equals(code) || RunnerErrorCodes.RUNNER_PROTOCOL_ERROR.equals(code)
                || RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE.equals(code)) {
            return new ScriptRead(null, ERR_TRANSPORT, "Le poste n'a pas répondu à la relecture du script de sonde "
                    + "(" + code + ") : la sonde n'est pas en cause, elle repartira au créneau suivant.");
        }
        String detail = result.errorMessage() == null || result.errorMessage().isBlank() ? ""
                : " (" + excerpt(result.errorMessage()) + ")";
        return new ScriptRead(null, ERR_SCRIPT_MISSING, "Le script de sonde est introuvable sur le poste" + detail
                + ".");
    }

    private static final String NO_ANSWER = "Le poste n'a pas répondu à la relecture du script de sonde : la sonde "
            + "n'est pas en cause.";

    /** Lance la sonde de cette veille, telle que validée. */
    public Outcome run(JarvisWatch watch, Workspace workspace) {
        return run(watch, workspace, watch.isState() ? RELEVE_TIMEOUT_MS : PROBE_TIMEOUT_MS);
    }

    /** F-247 / SF-247-09 — même sonde, délai donné (le relevé de départ n'a que quelques secondes). */
    public Outcome run(JarvisWatch watch, Workspace workspace, long timeoutMs) {
        long started = System.currentTimeMillis();
        if (watch.getProbeScriptPath() != null) {
            ScriptRead script = readScriptChecked(watch.getUserId(), workspace, watch.getProbeScriptPath());
            if (!script.ok()) {
                return Outcome.failure(script.errorCode(), script.errorMessage(), elapsed(started));
            }
            if (!sha256(script.content()).equals(watch.getProbeScriptHash())) {
                return Outcome.failure(ERR_SCRIPT_CHANGED, "Le script de sonde a changé depuis sa validation : "
                        + "elle doit être reproposée et retestée.", elapsed(started));
            }
        }
        RunnerTarget target = targetOf(workspace);
        String callId = "veille-" + UUID.randomUUID();
        RunnerCallResult result;
        try {
            result = watch.isState()
                    ? gateway.bashLow(target, callId, watch.getProbeCommand(), Math.min(timeoutMs, RELEVE_TIMEOUT_MS))
                    : gateway.bash(target, callId, watch.getProbeCommand(), null, timeoutMs, null);
        } catch (RuntimeException e) {
            log.warn("Sonde de veille non lancée ({})", e.getClass().getSimpleName());
            return Outcome.failure(ERR_TRANSPORT, "La sonde n'a pas pu être lancée sur le poste.", elapsed(started));
        }
        record(watch.getUserId(), target, callId, watch.isState() ? AUDIT_TOOL_RELEVE : AUDIT_TOOL, "bash",
                watch.getProbeCommand(), result);
        if (result != null && !result.ok() && ("busy_deferred".equals(result.errorCode())
                || "preempted".equals(result.errorCode()))) {
            return Outcome.failure(ERR_YIELDED + ":" + result.errorCode(), "Le poste travaillait : relevé remis.",
                    elapsed(started));
        }
        if (result == null || !result.ok()) {
            String code = result != null && "runner_unavailable".equals(result.errorCode()) ? ERR_OFFLINE : ERR_TRANSPORT;
            String message = result != null && result.errorMessage() != null ? result.errorMessage()
                    : "La sonde n'a pas pu être lancée sur le poste.";
            return Outcome.failure(code, message, elapsed(started));
        }
        String output = result.streamed() != null && !result.streamed().isBlank() ? result.streamed()
                : result.content();
        if (result.exitCode() != null && result.exitCode() != 0) {
            String last = lastLine(output);
            return Outcome.failure(ERR_EXIT, "La sonde s'est terminée en erreur (code " + result.exitCode() + ")"
                    + (last.isEmpty() ? " sans rien écrire : jeton expiré, réseau, ou plateforme indisponible."
                            : ". Dernière ligne : « " + last + " »."), elapsed(started));
        }
        if (result.streamTruncated() || result.truncated()) {
            return Outcome.failure(ERR_OUTPUT, "La sortie de la sonde est trop longue : resserre le périmètre ou "
                    + "raccourcis les champs.", elapsed(started));
        }
        JarvisWatchItems.Parsed parsed = JarvisWatchItems.parse(output);
        if (!parsed.ok()) {
            String head = excerpt(output);
            return Outcome.failure(ERR_OUTPUT, parsed.error() + (head.isEmpty() ? "" : " Début de la sortie : « "
                    + head + " »."), elapsed(started));
        }
        return new Outcome(parsed.items(), null, null, elapsed(started));
    }

    /** Longueur d'une erreur gardée sur la veille (colonne {@code last_error_message}). */
    public static final int MAX_ERROR = 500;

    /** F-273 / SF-273-01 — l'échec vient du poste (déconnecté, muet), pas de la sonde. */
    public static boolean isHostSide(String errorCode) {
        return ERR_OFFLINE.equals(errorCode) || ERR_TRANSPORT.equals(errorCode);
    }

    /** Un message d'erreur borné à la colonne. */
    public static String bounded(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > MAX_ERROR ? message.substring(0, MAX_ERROR - 1) + "…" : message;
    }

    /** Longueur d'un extrait de sortie montré au PO (F-273 / SF-273-01). */
    static final int EXCERPT = 200;
    private static final Pattern SECRET_PAIR = Pattern.compile(
            "(?i)\\b(token|password|passwd|secret|authorization|api[_ -]?key|cookie)([\"']?\\s*[:=]\\s*[\"']?)"
                    + "(?:bearer\\s+|basic\\s+)?[^\\s,;\"']+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer|basic)\\s+[^\\s,;\"']+");
    private static final Pattern LONG_OPAQUE = Pattern.compile("[A-Za-z0-9+/_=-]{24,}");

    /** La dernière ligne non vide d'une sortie, bornée et masquée (vide si rien). */
    static String lastLine(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        String[] lines = output.strip().split("\\R");
        return excerpt(lines[lines.length - 1]);
    }

    /**
     * Un extrait lisible d'une sortie : une ligne, {@value #EXCERPT} caractères au plus, <b>secrets masqués</b>
     * (paire « token=… », « Bearer … », toute suite opaque de 24 caractères ou plus).
     */
    static String excerpt(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String one = text.strip().replaceAll("\\s+", " ");
        one = SECRET_PAIR.matcher(one).replaceAll("$1$2***");
        one = BEARER.matcher(one).replaceAll("$1 ***");
        one = LONG_OPAQUE.matcher(one).replaceAll("***");
        return one.length() > EXCERPT ? one.substring(0, EXCERPT) + "…" : one;
    }

    /** Empreinte SHA-256 hexadécimale d'un script. */
    public static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    private void record(UUID userId, RunnerTarget target, String callId, String prefix, String tool, String what,
            RunnerCallResult result) {
        if (audit == null || result == null) {
            return;
        }
        try {
            audit.recordCall(userId, target, callId, prefix + ":" + tool, what, result);
        } catch (RuntimeException e) {
            log.debug("Journal du runner : sonde non inscrite ({})", e.getClass().getSimpleName());
        }
    }

    private static long elapsed(long started) {
        return Math.max(0L, System.currentTimeMillis() - started);
    }
}
