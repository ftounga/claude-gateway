package fr.claudegateway.teams;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerTargets;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * <b>Où en est la liaison Teams de ce projet</b> (F-87 / SF-87-03).
 *
 * <p>La gateway ne se connecte à aucun navigateur et n'en connaîtra jamais l'adresse : elle
 * <b>demande</b> à la machine, par le même canal que les outils de l'agent, et traduit la réponse en
 * un état d'écran. C'est une fenêtre sur un état, pas un panneau de contrôle.</p>
 *
 * <p><b>Toutes les issues sont des états, aucune n'est une panne.</b> Pas de machine rattachée,
 * runner éteint, navigateur non lancé, Teams qui a changé : dans tous les cas l'écran reçoit un état
 * et une phrase. Un code d'erreur HTTP ferait clignoter une erreur d'application là où il n'y a
 * qu'un navigateur à lancer.</p>
 *
 * <p>Isolation {@code user_id} : le projet est relu par {@link WorkspaceService#requireOwned},
 * jamais depuis un paramètre client.</p>
 */
@Service
public class TeamsLinkService {

    private static final Logger log = LoggerFactory.getLogger(TeamsLinkService.class);

    /** Nom d'outil du journal d'audit : distinct de celui de l'agent (même règle que F-38 / SF-38-17). */
    static final String SCREEN_TEAMS_STATUS = "screen_teams_status";

    /** Les trois états, tels que l'indicateur les écrit. Liste close, partagée avec le runner. */
    public static final String LINKED = "LINKED";
    public static final String BROWSER_NOT_DETECTED = "BROWSER_NOT_DETECTED";
    public static final String TEAMS_CHANGED = "TEAMS_CHANGED";

    private final WorkspaceService workspaceService;
    private final RunnerToolGateway gateway;
    private final RunnerAuditService auditService;
    private final ObjectMapper mapper;

    public TeamsLinkService(WorkspaceService workspaceService, RunnerToolGateway gateway,
            RunnerAuditService auditService, ObjectMapper mapper) {
        this.workspaceService = workspaceService;
        this.gateway = gateway;
        this.auditService = auditService;
        this.mapper = mapper;
    }

    /**
     * État de la liaison pour ce projet.
     *
     * @throws fr.claudegateway.atelier.WorkspaceNotFoundException si le projet n'existe pas
     *         <b>ou</b> appartient à quelqu'un d'autre — indistinguables, pour ne rien révéler
     */
    @Transactional(readOnly = true)
    public TeamsLink status(UUID userId, UUID workspaceId) {
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
        if (workspace.getHostId() == null) {
            return notLinked("Aucune machine n'est rattachée à ce projet.",
                    "Rattachez un poste au projet, puis lancez le runner sur cette machine : "
                            + "c'est lui qui observe le navigateur.");
        }

        String callId = UUID.randomUUID().toString();
        RunnerTarget target = RunnerTargets.of(workspace);
        RunnerCallResult result = gateway.teamsStatus(target, callId);
        auditService.recordCall(userId, target, callId, SCREEN_TEAMS_STATUS, null, result);
        if (!result.ok()) {
            return notLinked(sentenceFor(result.errorCode()), remedyFor(result.errorCode()));
        }
        return parse(result.content());
    }

    /** Traduit la réponse du runner. Une réponse illisible est un état, pas une exception. */
    TeamsLink parse(String content) {
        try {
            JsonNode node = mapper.readTree(content == null ? "" : content);
            String state = state(node.path("state").asText(BROWSER_NOT_DETECTED));
            return new TeamsLink(state,
                    node.path("label").asText(labelFor(state)),
                    node.path("sentence").asText(""),
                    node.path("remedy").asText(""),
                    node.path("browser").asText(""),
                    node.path("health").path("verdict").asText(""),
                    node.path("health").path("recognizedFields").asInt(0),
                    node.path("health").path("expectedFields").asInt(0),
                    strings(node.path("health").path("missingFields")),
                    strings(node.path("health").path("observedApiVersions")),
                    node.path("conclusive").asBoolean(false));
        } catch (Exception e) {
            log.warn("Réponse de liaison Teams illisible : {}", e.getMessage());
            return notLinked("La machine a répondu quelque chose d'inattendu.",
                    "Mettez le runner à jour sur cette machine : la version installée ne connaît "
                            + "peut-être pas encore le volet Teams.");
        }
    }

    private static String state(String declared) {
        return switch (declared) {
            case LINKED -> LINKED;
            case TEAMS_CHANGED -> TEAMS_CHANGED;
            default -> BROWSER_NOT_DETECTED;
        };
    }

    /** Libellé de repli : le runner l'envoie déjà, mais l'écran ne doit jamais se retrouver muet. */
    static String labelFor(String state) {
        return switch (state) {
            case LINKED -> "Teams relié";
            case TEAMS_CHANGED -> "Teams a changé";
            default -> "Teams : navigateur non détecté";
        };
    }

    private static TeamsLink notLinked(String sentence, String remedy) {
        return new TeamsLink(BROWSER_NOT_DETECTED, labelFor(BROWSER_NOT_DETECTED), sentence, remedy,
                "", "", 0, 0, List.of(), List.of(), false);
    }

    private static String sentenceFor(String errorCode) {
        if (RunnerErrorCodes.RUNNER_UNAVAILABLE.equals(errorCode)) {
            return "La machine de ce projet n'est pas connectée.";
        }
        if (RunnerErrorCodes.RUNNER_TIMEOUT.equals(errorCode)) {
            return "La machine n'a pas répondu à temps.";
        }
        if (RunnerErrorCodes.UNSUPPORTED_TOOL.equals(errorCode)) {
            return "Le volet Teams n'est pas actif sur cette machine.";
        }
        return "La liaison Teams n'a pas pu être vérifiée.";
    }

    private static String remedyFor(String errorCode) {
        if (RunnerErrorCodes.RUNNER_UNAVAILABLE.equals(errorCode)) {
            return "Lancez le runner sur la machine rattachée à ce projet.";
        }
        if (RunnerErrorCodes.UNSUPPORTED_TOOL.equals(errorCode)) {
            return "Le runner a été lancé avec --no-teams, ou sa version est antérieure au volet "
                    + "Teams. Relancez-le sans ce drapeau, ou mettez-le à jour.";
        }
        return "Réessayez dans un instant ; si cela persiste, relancez le runner sur cette machine.";
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(node -> {
                if (node.isTextual() && !node.asText().isBlank()) {
                    values.add(node.asText());
                }
            });
        }
        return List.copyOf(values);
    }

    /**
     * L'état de la liaison, tel que l'écran le lit.
     *
     * @param state               {@code LINKED}, {@code BROWSER_NOT_DETECTED}, {@code TEAMS_CHANGED}
     * @param label               libellé <b>toujours</b> écrit à côté de l'indicateur
     * @param sentence            ce qui est dit à l'utilisateur
     * @param remedy              ce qu'il peut faire, ou {@code ""}
     * @param browser             navigateur observé, ou {@code ""}
     * @param healthVerdict       {@code FULL}, {@code PARTIAL}, {@code NONE}, ou {@code ""}
     * @param recognizedFields    champs attendus effectivement reconnus
     * @param expectedFields      champs attendus
     * @param missingFields       ce qui n'est plus reconnu
     * @param observedApiVersions versions d'interface observées — nommées dans un refus
     * @param conclusive          vrai si la sonde a réellement vu passer une réponse de Teams
     */
    public record TeamsLink(String state, String label, String sentence, String remedy,
            String browser, String healthVerdict, int recognizedFields, int expectedFields,
            List<String> missingFields, List<String> observedApiVersions, boolean conclusive) {
    }
}
