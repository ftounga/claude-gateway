package fr.claudegateway.images;

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
 * <b>Exécute {@code generate_image}</b> (F-142 / SF-142-04) : la gateway relaie le fournisseur d'images,
 * range le PNG, puis le <b>dépose là où vit le projet</b> pour que l'agent l'insère en page (F-109) ou en
 * slide (F-129).
 *
 * <p>La garde et l'accord de l'utilisateur sont posés <b>avant</b>, par la boucle : cet exécuteur ne
 * décide de rien, il fait. Toute erreur est un <b>résultat d'outil en erreur</b> — le tour continue, et
 * l'agent reçoit une phrase qui dit quoi corriger.</p>
 *
 * <h2>Dépôt dans le projet</h2>
 * <p>Un projet <b>hébergé</b> (cible {@code SANDBOX}) : le PNG est écrit dans le stockage objet du projet
 * ({@link WorkspaceService#depositHostedFile}) sous {@code entrees/<nom>}. Un projet <b>sur un poste</b>
 * (cible {@code RUNNER}) : le PNG est poussé par le runner en binaire, par tranches
 * ({@link RunnerToolGateway#writeFileBytes}, F-115), et tracé. L'agent référence le chemin rendu.</p>
 */
@Component
public class ImageToolExecutor {

    /** Tranche binaire écrite par appel : ≈ 480 Kio en Base64, sous la borne de 512 Kio du contrat. */
    static final int CHUNK_BYTES = 360 * 1024;

    private final ImageGenerationService imageService;
    private final WorkspaceService workspaceService;
    private final RunnerToolGateway runnerToolGateway;
    private final RunnerAuditService runnerAuditService;

    public ImageToolExecutor(ImageGenerationService imageService, WorkspaceService workspaceService,
            RunnerToolGateway runnerToolGateway, RunnerAuditService runnerAuditService) {
        this.imageService = imageService;
        this.workspaceService = workspaceService;
        this.runnerToolGateway = runnerToolGateway;
        this.runnerAuditService = runnerAuditService;
    }

    /**
     * Génère une image décorative et la dépose dans le projet.
     *
     * @param userId    propriétaire du terminal (celui du tour)
     * @param workspace terminal du tour, déjà vérifié comme possédé
     * @param callId    identifiant de corrélation de l'appel
     * @param input     paramètres de l'outil
     */
    public Outcome execute(UUID userId, Workspace workspace, String callId, JsonNode input) {
        String prompt = text(input, "prompt");
        if (prompt.isEmpty()) {
            return Outcome.error("prompt est requis : la description de l'image décorative à générer.");
        }
        ImageSize size = ImageSize.fromRequest(text(input, "size"));

        ImageGenerationService.Generated generated;
        try {
            ImagePlace place = new ImagePlace(userId, ImageToolCatalog.spaceOf(workspace),
                    workspace.getHostId(), workspace.getId());
            generated = imageService.generate(place, prompt, size);
        } catch (ImageProviderUnavailableException e) {
            return Outcome.error("Génération d'images non configurée sur cette instance : impossible de "
                    + "générer une image. Réponds sans image, ou insère un visuel existant.");
        } catch (ImageQuotaExceededException | ImageRejectedException | ImageProviderException e) {
            return Outcome.error(e.getMessage());
        } catch (RuntimeException e) {
            return Outcome.error("Génération d'image en échec : " + reason(e));
        }

        String name = fileName(text(input, "filename"), generated.image().getId());
        String deposited = deposit(userId, workspace, callId, name, generated.bytes());
        if (deposited == null) {
            return Outcome.error("Image générée et rangée (image_id : " + generated.image().getId()
                    + "), mais son dépôt dans le projet a échoué : réessaie, ou insère un visuel existant.");
        }
        return new Outcome("Image décorative générée et déposée dans le projet sous « " + deposited
                + " » (image_id : " + generated.image().getId() + "). Réfère ce chemin en pièce jointe "
                + "d'une page (<img src=\"" + name + "\">) ou en add_picture d'une slide. Rappel : "
                + "DÉCORATIF uniquement — jamais un schéma d'architecture (diagramme-as-code pour cela).",
                false, generated.image());
    }

    /** Titre lisible d'un appel, pour l'étape et le journal : la description, jamais l'image. */
    public static String auditTarget(JsonNode input) {
        String prompt = text(input, "prompt");
        if (prompt.isEmpty()) {
            return null;
        }
        return prompt.length() > 80 ? prompt.substring(0, 80) : prompt;
    }

    // ------------------------------------------------------------------ dépôt

    /** Dépose le PNG dans le projet, hébergé ou sur poste. Rend le chemin déposé, ou {@code null}. */
    private String deposit(UUID userId, Workspace workspace, String callId, String name, byte[] bytes) {
        if (workspace.isRunnerTarget()) {
            return depositOnRunner(userId, workspace, callId, name, bytes);
        }
        try {
            return workspaceService.depositHostedFile(userId, workspace.getId(), name, bytes, "image/png");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Pousse le PNG sur le poste par tranches ({@code write_file_bytes}, F-115) : {@code offset == 0}
     * tronque/crée. La lecture/écriture est tracée une fois, sur le {@code callId} de l'appel.
     */
    private String depositOnRunner(UUID userId, Workspace workspace, String callId, String name, byte[] bytes) {
        RunnerTarget target = RunnerTargets.of(workspace);
        RunnerCallResult last = null;
        int offset = 0;
        int chunk = 0;
        while (offset < bytes.length) {
            int end = Math.min(bytes.length, offset + CHUNK_BYTES);
            String base64 = Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(bytes, offset, end));
            RunnerCallResult result = runnerToolGateway.writeFileBytes(target, callId + "." + chunk, name,
                    base64, offset);
            last = result;
            if (!result.ok()) {
                runnerAuditService.recordCall(userId, target, callId, ImageToolCatalog.GENERATE, name, result);
                if (chunk == 0 && RunnerErrorCodes.UNSUPPORTED_TOOL.equals(result.errorCode())) {
                    // Runner trop ancien : pas de dépôt binaire. Le tour continue sans image déposée.
                    return null;
                }
                return null;
            }
            offset = end;
            chunk++;
        }
        runnerAuditService.recordCall(userId, target, callId, ImageToolCatalog.GENERATE, name, last);
        return name;
    }

    // ------------------------------------------------------------------ util

    /** Un nom de fichier PNG sûr : nom plat, extension {@code .png}, dérivé de l'id si absent. */
    static String fileName(String raw, UUID imageId) {
        String base = raw == null ? "" : raw.strip();
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.toLowerCase(Locale.ROOT).lastIndexOf(".png");
        if (dot >= 0 && dot == base.length() - 4) {
            base = base.substring(0, dot);
        }
        StringBuilder sb = new StringBuilder();
        for (char c : base.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('-');
            }
        }
        String safe = sb.toString().replaceAll("-{2,}", "-");
        if (safe.length() > 60) {
            safe = safe.substring(0, 60);
        }
        if (safe.isBlank()) {
            safe = "image-" + imageId.toString().substring(0, 8);
        }
        return safe + ".png";
    }

    private static String reason(RuntimeException e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? "cause inconnue" : e.getMessage();
    }

    private static String text(JsonNode input, String field) {
        if (input == null || !input.hasNonNull(field)) {
            return "";
        }
        return input.get(field).asText("").strip();
    }

    /**
     * L'issue d'un appel.
     *
     * @param content   ce que l'agent reçoit
     * @param error     vrai si l'appel a échoué
     * @param published l'image rangée, ou {@code null}
     */
    public record Outcome(String content, boolean error, GeneratedImage published) {

        static Outcome error(String message) {
            return new Outcome(message, true, null);
        }
    }
}
