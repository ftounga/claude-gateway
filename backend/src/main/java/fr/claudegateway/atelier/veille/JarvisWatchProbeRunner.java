package fr.claudegateway.atelier.veille;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
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

    /** Relit un script de sonde sur le poste ; vide si illisible. */
    public Optional<String> readScript(UUID userId, Workspace workspace, String path) {
        RunnerTarget target = targetOf(workspace);
        String callId = "veille-" + UUID.randomUUID();
        try {
            RunnerCallResult result = gateway.readFile(target, callId, path);
            record(userId, target, callId, "read_file", path, result);
            return result.ok() ? Optional.ofNullable(result.content()) : Optional.empty();
        } catch (RuntimeException e) {
            log.debug("Script de sonde illisible ({})", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Lance la sonde de cette veille, telle que validée. */
    public Outcome run(JarvisWatch watch, Workspace workspace) {
        long started = System.currentTimeMillis();
        if (watch.getProbeScriptPath() != null) {
            Optional<String> script = readScript(watch.getUserId(), workspace, watch.getProbeScriptPath());
            if (script.isEmpty()) {
                return Outcome.failure(ERR_SCRIPT_MISSING, "Le script de sonde est introuvable sur le poste.",
                        elapsed(started));
            }
            if (!sha256(script.get()).equals(watch.getProbeScriptHash())) {
                return Outcome.failure(ERR_SCRIPT_CHANGED, "Le script de sonde a changé depuis sa validation : "
                        + "elle doit être reproposée et retestée.", elapsed(started));
            }
        }
        RunnerTarget target = targetOf(workspace);
        String callId = "veille-" + UUID.randomUUID();
        RunnerCallResult result;
        try {
            result = gateway.bash(target, callId, watch.getProbeCommand(), null, PROBE_TIMEOUT_MS, null);
        } catch (RuntimeException e) {
            log.warn("Sonde de veille non lancée ({})", e.getClass().getSimpleName());
            return Outcome.failure(ERR_TRANSPORT, "La sonde n'a pas pu être lancée sur le poste.", elapsed(started));
        }
        record(watch.getUserId(), target, callId, "bash", watch.getProbeCommand(), result);
        if (result == null || !result.ok()) {
            String code = result != null && "runner_unavailable".equals(result.errorCode()) ? ERR_OFFLINE : ERR_TRANSPORT;
            String message = result != null && result.errorMessage() != null ? result.errorMessage()
                    : "La sonde n'a pas pu être lancée sur le poste.";
            return Outcome.failure(code, message, elapsed(started));
        }
        if (result.exitCode() != null && result.exitCode() != 0) {
            return Outcome.failure(ERR_EXIT, "La sonde s'est terminée en erreur (code " + result.exitCode()
                    + ") : jeton expiré, réseau, ou plateforme indisponible.", elapsed(started));
        }
        if (result.streamTruncated() || result.truncated()) {
            return Outcome.failure(ERR_OUTPUT, "La sortie de la sonde est trop longue : resserre le périmètre ou "
                    + "raccourcis les champs.", elapsed(started));
        }
        String output = result.streamed() != null && !result.streamed().isBlank() ? result.streamed()
                : result.content();
        JarvisWatchItems.Parsed parsed = JarvisWatchItems.parse(output);
        if (!parsed.ok()) {
            return Outcome.failure(ERR_OUTPUT, parsed.error(), elapsed(started));
        }
        return new Outcome(parsed.items(), null, null, elapsed(started));
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

    private void record(UUID userId, RunnerTarget target, String callId, String tool, String what,
            RunnerCallResult result) {
        if (audit == null || result == null) {
            return;
        }
        try {
            audit.recordCall(userId, target, callId, AUDIT_TOOL + ":" + tool, what, result);
        } catch (RuntimeException e) {
            log.debug("Journal du runner : sonde non inscrite ({})", e.getClass().getSimpleName());
        }
    }

    private static long elapsed(long started) {
        return Math.max(0L, System.currentTimeMillis() - started);
    }
}
