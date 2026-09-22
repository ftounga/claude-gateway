package fr.claudegateway.presentations;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerTargets;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * <b>Exécute {@code presentation_publish}</b> (F-129 / SF-129-02) : lit le {@code .pptx} <b>là où vit le
 * projet</b>, le range comme un artefact, et rend à l'agent ce qui a été rangé. Patron
 * {@code PageToolExecutor} (F-109 / SF-109-06).
 *
 * <p>La garde et l'accord de l'utilisateur sont posés <b>avant</b>, par la boucle : cet exécuteur ne
 * décide de rien. Toute erreur est un <b>résultat d'outil en erreur</b> — le tour continue.</p>
 *
 * <p>Un projet <b>sur un poste</b> (cible {@code RUNNER}) : le fichier est lu par le runner en binaire,
 * par tranches ({@code read_file_bytes}), et tracé. Un projet <b>hébergé</b> (cible {@code SANDBOX}) :
 * lu par le stockage objet du projet ({@link WorkspaceService}), sous l'isolation {@code user_id}.</p>
 */
@Component
public class PresentationToolExecutor {

    /** Tranche binaire lue par appel : ≈ 480 Kio en Base64, sous la borne de 512 Kio du contrat. */
    static final int CHUNK_BYTES = 360 * 1024;

    private final PresentationService presentationService;
    private final RunnerToolGateway runnerToolGateway;
    private final RunnerAuditService runnerAuditService;
    private final WorkspaceService workspaceService;
    private final PresentationLimits limits;

    public PresentationToolExecutor(PresentationService presentationService,
            RunnerToolGateway runnerToolGateway, RunnerAuditService runnerAuditService,
            WorkspaceService workspaceService, PresentationLimits limits) {
        this.presentationService = presentationService;
        this.runnerToolGateway = runnerToolGateway;
        this.runnerAuditService = runnerAuditService;
        this.workspaceService = workspaceService;
        this.limits = limits;
    }

    /**
     * Capture la présentation décrite par l'appel.
     *
     * @param userId    propriétaire du terminal (celui du tour)
     * @param workspace terminal du tour, déjà vérifié comme possédé
     * @param callId    identifiant de corrélation de l'appel
     * @param input     paramètres de l'outil
     */
    public Outcome execute(UUID userId, Workspace workspace, String callId, JsonNode input) {
        String title = text(input, "title");
        String path = text(input, "path");
        if (title.isEmpty()) {
            return Outcome.error("title est requis.");
        }
        if (path.isEmpty()) {
            return Outcome.error("path est requis : le chemin du fichier .pptx produit sur la machine.");
        }
        if (!extension(path).equals("pptx")) {
            return Outcome.error("Le fichier « " + path + " » n'est pas un .pptx : produis-le d'abord "
                    + "avec la skill pptx, puis donne son chemin.");
        }

        UUID presentationId = null;
        String rawId = text(input, "presentation_id");
        if (!rawId.isEmpty()) {
            try {
                presentationId = UUID.fromString(rawId);
            } catch (IllegalArgumentException e) {
                return Outcome.error(unknownPresentation());
            }
        }

        Read read = readBytes(userId, workspace, callId, path, limits.maxPptxBytes());
        if (read.error() != null) {
            return Outcome.error(read.error());
        }

        PresentationPlace place = new PresentationPlace(userId,
                PresentationToolCatalog.spaceOf(workspace), workspace.getHostId(), workspace.getId());
        try {
            Presentation saved = presentationService.publish(place, presentationId, title,
                    text(input, "description"), read.content());
            return new Outcome("Présentation capturée : « " + saved.getTitle() + " ». presentation_id : "
                    + saved.getId() + ". L'utilisateur la voit dans l'application et peut la télécharger ; "
                    + "pour la remplacer, rappelle ce presentation_id.", false, saved);
        } catch (PresentationNotFoundException e) {
            return Outcome.error(unknownPresentation());
        } catch (PresentationRejectedException e) {
            return Outcome.error(e.getMessage());
        }
    }

    /** Titre lisible d'un appel, pour l'étape et le journal : jamais le contenu. */
    public static String auditTarget(JsonNode input) {
        String title = text(input, "title");
        return title.isEmpty() ? null : title;
    }

    /** Les octets d'un fichier, lus sur le poste (RUNNER, par tranches) ou dans le stockage (SANDBOX). */
    private Read readBytes(UUID userId, Workspace workspace, String callId, String path, long cap) {
        if (workspace.isRunnerTarget()) {
            return readBytesFromRunner(userId, RunnerTargets.of(workspace), callId, path, cap);
        }
        try {
            byte[] content = workspaceService.readFileBytes(userId, workspace.getId(), path);
            if (content.length > cap) {
                return Read.error(tooLarge(cap));
            }
            return Read.ok(content);
        } catch (RuntimeException e) {
            return Read.error("Fichier illisible dans le projet hébergé (" + path + ") : " + reason(e));
        }
    }

    /**
     * Lit le fichier du poste par tranches ({@code read_file_bytes}) : {@code content} porte les octets en
     * Base64, {@code bytes} la taille totale, {@code truncated} vaut vrai s'il en reste. Même patron que
     * {@code PageToolExecutor}. La lecture est tracée une fois.
     */
    private Read readBytesFromRunner(UUID userId, RunnerTarget target, String callId, String path, long cap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Long size = null;
        RunnerCallResult last = null;
        int chunk = 0;
        while (true) {
            RunnerCallResult result = runnerToolGateway.readFileBytes(target, callId + "." + chunk, path,
                    out.size(), CHUNK_BYTES);
            last = result;
            if (!result.ok()) {
                runnerAuditService.recordCall(userId, target, callId, PresentationToolCatalog.PUBLISH, path, result);
                if (chunk == 0 && RunnerErrorCodes.UNSUPPORTED_TOOL.equals(result.errorCode())) {
                    return Read.error("Le runner de ce poste est trop ancien pour lire le .pptx : "
                            + "mets-le à jour depuis la Forge, ou produis la présentation depuis le sandbox.");
                }
                return Read.error(unreadable(path, result));
            }
            byte[] part;
            try {
                part = Base64.getDecoder().decode(result.content() == null ? "" : result.content());
            } catch (IllegalArgumentException e) {
                runnerAuditService.recordCall(userId, target, callId, PresentationToolCatalog.PUBLISH, path, result);
                return Read.error("Fichier illisible sur la machine (" + path + ") : réponse du runner invalide.");
            }
            if (result.bytes() == null || result.bytes() < 0) {
                runnerAuditService.recordCall(userId, target, callId, PresentationToolCatalog.PUBLISH, path, result);
                return Read.error("Fichier illisible sur la machine (" + path + ") : réponse du runner invalide.");
            }
            long announced = result.bytes();
            if (size == null) {
                size = announced;
                if (size > cap) {
                    runnerAuditService.recordCall(userId, target, callId, PresentationToolCatalog.PUBLISH, path, result);
                    return Read.error(tooLarge(cap));
                }
            } else if (announced != size) {
                runnerAuditService.recordCall(userId, target, callId, PresentationToolCatalog.PUBLISH, path, result);
                return Read.error("Le fichier a changé pendant la lecture : réessaie quand il ne bouge plus.");
            }
            out.write(part, 0, part.length);
            if (out.size() > cap) {
                runnerAuditService.recordCall(userId, target, callId, PresentationToolCatalog.PUBLISH, path, result);
                return Read.error(tooLarge(cap));
            }
            if (!result.truncated() || part.length == 0 || out.size() >= size) {
                break;
            }
            chunk++;
        }
        runnerAuditService.recordCall(userId, target, callId, PresentationToolCatalog.PUBLISH, path, last);
        if (size != null && out.size() != size) {
            return Read.error("Le fichier a changé pendant la lecture : réessaie quand il ne bouge plus.");
        }
        return Read.ok(out.toByteArray());
    }

    private String tooLarge(long cap) {
        return "Le .pptx dépasse " + (cap / (1024 * 1024)) + " Mo. Allège-le (moins d'images, images "
                + "compressées) ou découpe la présentation.";
    }

    private static String unreadable(String path, RunnerCallResult result) {
        String reason = result.errorMessage() == null || result.errorMessage().isBlank()
                ? "lecture impossible" : result.errorMessage();
        return "Fichier illisible sur la machine (" + path + ") : " + reason;
    }

    private static String reason(RuntimeException e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? "lecture impossible" : e.getMessage();
    }

    private static String unknownPresentation() {
        return "presentation_id inconnu : aucune présentation de ce compte ne porte cet identifiant. "
                + "Omets presentation_id pour en capturer une nouvelle.";
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode input, String field) {
        if (input == null || !input.hasNonNull(field)) {
            return "";
        }
        return input.get(field).asText("").strip();
    }

    /** Le résultat d'une lecture : des octets, ou un motif d'erreur. */
    private record Read(byte[] content, String error) {

        static Read ok(byte[] content) {
            return new Read(content, null);
        }

        static Read error(String message) {
            return new Read(null, message);
        }
    }

    /**
     * L'issue d'un appel.
     *
     * @param content   ce que l'agent reçoit
     * @param error     vrai si l'appel a échoué
     * @param published la présentation rangée, ou {@code null}
     */
    public record Outcome(String content, boolean error, Presentation published) {

        static Outcome error(String message) {
            return new Outcome(message, true, null);
        }
    }
}
