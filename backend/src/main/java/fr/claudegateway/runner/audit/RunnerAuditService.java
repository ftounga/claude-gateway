package fr.claudegateway.runner.audit;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerConnection;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerRegistry;

/**
 * Journal d'audit des actions du runner (F-38 / SF-38-08, décision D11). Écrit une ligne par appel
 * d'outil terminé et par appel refusé avant émission, et relit le journal pour son propriétaire.
 *
 * <p><b>Non bloquant par construction</b> (contrat de messages §9) : l'écriture se fait hors de
 * toute transaction de la boucle tool-use — volontairement non transactionnelle — et toute erreur
 * est absorbée ici. Un journal indisponible ne doit pas empêcher de travailler ; l'inverse serait
 * pire que l'absence de trace.</p>
 *
 * <p><b>Isolation</b> : {@code user_id} vient toujours du propriétaire du workspace (vérifié en
 * amont par {@code requireOwned}), {@code token_id} du registre local — jamais d'un champ de
 * message reçu du runner.</p>
 */
@Service
public class RunnerAuditService {

    /** Outil fictif de la ligne agrégée des lectures d'amorçage de la consigne système. */
    public static final String TOOL_BOOTSTRAP = "bootstrap";
    /** Outil fictif du geste de coupe-circuit. */
    public static final String TOOL_KILL_SWITCH = "kill_switch";
    /** Nombre de lignes rendues par défaut. */
    public static final int DEFAULT_LIMIT = 50;
    /** Plafond de lecture : un journal se consulte, il ne se déverse pas. */
    public static final int MAX_LIMIT = 200;

    private static final Logger log = LoggerFactory.getLogger(RunnerAuditService.class);

    private static final int MAX_CALL_ID_CHARS = 64;
    private static final int MAX_TOOL_CHARS = 32;
    private static final int MAX_TARGET_CHARS = 1_000;
    private static final int MAX_ERROR_CODE_CHARS = 32;

    private final RunnerAuditRepository repository;
    private final RunnerRegistry registry;
    private final WorkspaceService workspaceService;

    public RunnerAuditService(RunnerAuditRepository repository, RunnerRegistry registry,
            WorkspaceService workspaceService) {
        this.repository = repository;
        this.registry = registry;
        this.workspaceService = workspaceService;
    }

    /**
     * Journalise l'issue d'un appel routé vers le runner. L'issue est déduite du résultat lui-même
     * (contrat §4) : ni l'appelant ni le runner ne choisissent la valeur écrite.
     */
    public void recordCall(UUID userId, UUID workspaceId, String callId, String tool, String target,
            RunnerCallResult result) {
        record(userId, workspaceId, callId, tool, target, outcomeOf(result),
                result.ok() ? null : result.errorCode(), result.exitCode(), result.durationMs(),
                result.bytes());
    }

    /** Journalise un appel <b>refusé avant émission</b> (validation d'action, D7). */
    public void recordDenied(UUID userId, UUID workspaceId, String callId, String tool, String target,
            RunnerAuditOutcome outcome) {
        record(userId, workspaceId, callId, tool, target, outcome, "denied", null, 0L, null);
    }

    /**
     * Journalise en <b>une seule ligne</b> les lectures d'amorçage de la consigne système
     * ({@code CLAUDE.md} + skills), relues à chaque message. Les tracer une par une noierait le
     * journal sous des dizaines de lignes que l'utilisateur n'a pas demandées, et masquerait ce
     * qu'il cherche : ce que le modèle, lui, a décidé de lire.
     *
     * @param reads  nombre de fichiers effectivement lus
     * @param chars  total des caractères lus
     */
    public void recordBootstrap(UUID userId, UUID workspaceId, String callId, int reads, long chars) {
        if (reads <= 0) {
            return; // Rien n'a été lu : une ligne vide n'apprendrait rien.
        }
        record(userId, workspaceId, callId, TOOL_BOOTSTRAP,
                "consigne système (" + reads + " lecture(s))", RunnerAuditOutcome.OK, null, null, 0L,
                chars);
    }

    /** Journalise un coupe-circuit (F-38 / SF-38-08). */
    public void recordKillSwitch(UUID userId, UUID workspaceId, int revokedTokens) {
        record(userId, workspaceId, UUID.randomUUID().toString(), TOOL_KILL_SWITCH,
                "coupe-circuit (" + revokedTokens + " jeton(s) révoqué(s))", RunnerAuditOutcome.OK,
                null, null, 0L, null);
    }

    /** Dernières lignes du journal d'un workspace <b>possédé</b>, du plus récent au plus ancien. */
    @Transactional(readOnly = true)
    public List<RunnerAudit> list(UUID userId, UUID workspaceId, Integer limit) {
        workspaceService.requireOwned(userId, workspaceId); // 404 si non possédé — isolation d'abord
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        return repository.findByUserIdAndWorkspaceIdOrderByCreatedAtDesc(userId, workspaceId,
                PageRequest.of(0, size));
    }

    // ------------------------------------------------------------------ interne

    private void record(UUID userId, UUID workspaceId, String callId, String tool, String target,
            RunnerAuditOutcome outcome, String errorCode, Integer exitCode, long durationMs,
            Long bytes) {
        try {
            repository.save(RunnerAudit.builder()
                    .userId(userId)
                    .workspaceId(workspaceId)
                    .tokenId(registry.findLocal(workspaceId).map(RunnerConnection::tokenId).orElse(null))
                    .callId(shorten(callId, MAX_CALL_ID_CHARS))
                    .tool(shorten(tool, MAX_TOOL_CHARS))
                    .target(shorten(target, MAX_TARGET_CHARS))
                    .outcome(outcome.name())
                    .errorCode(shorten(errorCode, MAX_ERROR_CODE_CHARS))
                    .exitCode(exitCode)
                    .durationMs(durationMs)
                    .bytes(bytes)
                    .build());
        } catch (RuntimeException ex) {
            // Le tour continue : une trace manquante est un défaut, un tour interrompu est une panne.
            log.warn("Écriture du journal d'audit runner impossible (workspace={}, outil={})",
                    workspaceId, tool);
        }
    }

    /** Issue journalisée d'un résultat d'appel (contrat §4 : codes de délai et d'annulation à part). */
    static RunnerAuditOutcome outcomeOf(RunnerCallResult result) {
        if (result.ok()) {
            return RunnerAuditOutcome.OK;
        }
        String code = result.errorCode() == null ? "" : result.errorCode();
        return switch (code) {
            case "timeout", RunnerErrorCodes.RUNNER_TIMEOUT -> RunnerAuditOutcome.TIMEOUT;
            case "cancelled" -> RunnerAuditOutcome.CANCELLED;
            case "denied" -> RunnerAuditOutcome.DENIED;
            default -> RunnerAuditOutcome.ERROR;
        };
    }

    private static String shorten(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
