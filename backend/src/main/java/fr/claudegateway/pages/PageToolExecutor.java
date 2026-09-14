package fr.claudegateway.pages;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * <b>Exécute {@code page_publish}</b> (F-109 / SF-109-02, étendu par SF-109-06) : lit ce qu'il faut
 * <b>là où vit le projet</b>, range la page au lieu du terminal, et rend à l'agent ce qui a été rangé.
 *
 * <p>La garde et l'accord de l'utilisateur sont posés <b>avant</b>, par la boucle : cet exécuteur ne
 * décide de rien, il fait. Toute erreur est un <b>résultat d'outil en erreur</b> — le tour continue, et
 * l'agent reçoit une phrase qui dit quoi corriger.</p>
 *
 * <h2>Deux sources, une seule décision : la cible du terminal (SF-109-06)</h2>
 *
 * <p>Un projet <b>sur un poste</b> (cible {@code RUNNER}) : les fichiers sont lus par le runner et
 * <b>tracés</b> dans son journal — le texte par {@code read_file}, les images en binaire par
 * {@code read_file_bytes} (par tranches). Un projet <b>hébergé</b> (cible {@code SANDBOX}, bac à sable
 * Managed Agents) : les mêmes lectures passent par le <b>stockage objet</b> du projet
 * ({@link WorkspaceService}), sous l'isolation {@code user_id}. L'agent ne donne qu'un {@code path} ;
 * c'est la gateway qui sait où il vit.</p>
 *
 * <h2>Images de la machine (SF-109-06)</h2>
 *
 * <p>Une pièce jointe {@code png/jpg/jpeg/gif/webp} est lue <b>en binaire</b> et rangée telle quelle ;
 * elle est servie avec la <b>même politique de sécurité</b> qu'une page (origine opaque, {@code nosniff},
 * CSP {@code sandbox}) par les routes de lecture existantes. Le {@code svg} reste lu comme du texte (D1).</p>
 */
@Component
public class PageToolExecutor {

    /** Ce que le runner sait relire comme du texte sans le corrompre (D3, SF-109-02). Le {@code svg} en est (D1). */
    private static final Set<String> TEXT_ATTACHMENTS = Set.of("css", "js", "mjs", "json", "svg", "csv", "txt", "md");

    /** Les images de la machine, lues en binaire (SF-109-06). Le type servi est déduit de l'extension. */
    private static final Set<String> IMAGE_ATTACHMENTS = Set.of("png", "jpg", "jpeg", "gif", "webp");

    /** Tranche binaire lue par appel : ≈ 480 Kio une fois en Base64, sous la borne de 512 Kio du contrat. */
    static final int CHUNK_BYTES = 360 * 1024;

    private final PageService pageService;
    private final RunnerToolGateway runnerToolGateway;
    private final RunnerAuditService runnerAuditService;
    private final WorkspaceService workspaceService;
    private final PageLimits pageLimits;

    public PageToolExecutor(PageService pageService, RunnerToolGateway runnerToolGateway,
            RunnerAuditService runnerAuditService, WorkspaceService workspaceService, PageLimits pageLimits) {
        this.pageService = pageService;
        this.runnerToolGateway = runnerToolGateway;
        this.runnerAuditService = runnerAuditService;
        this.workspaceService = workspaceService;
        this.pageLimits = pageLimits;
    }

    /**
     * Publie la page décrite par l'appel.
     *
     * @param userId    propriétaire du terminal (celui du tour)
     * @param workspace terminal du tour, déjà vérifié comme possédé
     * @param callId    identifiant de corrélation de l'appel
     * @param input     paramètres de l'outil
     * @return l'issue : contenu pour l'agent, erreur éventuelle, page rangée
     */
    public Outcome execute(UUID userId, Workspace workspace, String callId, JsonNode input) {
        String title = text(input, "title");
        String html = input == null || !input.hasNonNull("html") ? null : input.get("html").asText();
        String path = text(input, "path");
        boolean hasHtml = html != null && !html.isEmpty();
        boolean hasPath = !path.isEmpty();
        if (hasHtml == hasPath) {
            return Outcome.error("Donne exactement un de html (le document) ou path (un fichier .html de la machine).");
        }

        UUID pageId = null;
        String rawPageId = text(input, "page_id");
        if (!rawPageId.isEmpty()) {
            try {
                pageId = UUID.fromString(rawPageId);
            } catch (IllegalArgumentException e) {
                return Outcome.error(unknownPage());
            }
        }

        long cap = pageLimits.maxPageBytes();
        if (hasPath) {
            Read read = readText(userId, workspace, callId, path);
            if (read.error() != null) {
                return Outcome.error(read.error());
            }
            html = new String(read.content(), StandardCharsets.UTF_8);
        }

        Map<String, byte[]> attachments = new LinkedHashMap<>();
        JsonNode list = input == null ? null : input.get("attachments");
        if (list != null && list.isArray()) {
            int index = 0;
            for (JsonNode attachment : list) {
                index++;
                String name = text(attachment, "name");
                String attachmentPath = text(attachment, "path");
                String ext = extension(name);
                boolean isText = TEXT_ATTACHMENTS.contains(ext);
                boolean isImage = IMAGE_ATTACHMENTS.contains(ext);
                if (!PageAttachments.isValidName(name) || (!isText && !isImage)) {
                    return Outcome.error("Pièce jointe refusée : « " + name + " ». Depuis la machine, un nom plat "
                            + "et une extension parmi les fichiers texte (css, js, mjs, json, svg, csv, txt, md) ou "
                            + "les images (png, jpg, jpeg, gif, webp).");
                }
                if (attachments.containsKey(name)) {
                    return Outcome.error("Deux pièces jointes portent le nom « " + name + " ».");
                }
                if (attachmentPath.isEmpty()) {
                    return Outcome.error("La pièce jointe « " + name + " » n'a pas de path.");
                }
                String readId = callId + "#" + index;
                Read read = isImage
                        ? readBytes(userId, workspace, readId, name, attachmentPath, cap)
                        : readText(userId, workspace, readId, attachmentPath);
                if (read.error() != null) {
                    return Outcome.error(read.error());
                }
                attachments.put(name, read.content());
            }
        }

        PagePlace place = new PagePlace(userId, PageToolCatalog.spaceOf(workspace), workspace.getHostId(),
                workspace.getId());
        try {
            PageService.PublishedPage published = pageService.publish(place, pageId, title,
                    text(input, "description"), html, attachments);
            return new Outcome("Page publiée : « " + published.page().getTitle() + " » — version "
                    + published.version().getVersion() + ". page_id : " + published.page().getId()
                    + ". L'utilisateur la voit dans l'application ; pour la modifier, republie avec ce page_id.",
                    false, published);
        } catch (PageNotFoundException e) {
            return Outcome.error(unknownPage());
        } catch (PageRejectedException e) {
            return Outcome.error(e.getMessage());
        }
    }

    /** Titre lisible d'un appel, pour l'étape et le journal : jamais le contenu. */
    public static String auditTarget(JsonNode input) {
        String title = text(input, "title");
        return title.isEmpty() ? null : title;
    }

    // ------------------------------------------------------------------ lecture texte (html + pièces texte)

    /** Le contenu texte d'un fichier, lu sur le poste (RUNNER) ou dans le stockage (SANDBOX). */
    private Read readText(UUID userId, Workspace workspace, String callId, String path) {
        if (workspace.isRunnerTarget()) {
            RunnerTarget target = RunnerTargets.of(workspace);
            RunnerCallResult result = runnerToolGateway.readFile(target, callId, path);
            runnerAuditService.recordCall(userId, target, callId, PageToolCatalog.PUBLISH, path, result);
            if (!result.ok()) {
                return Read.error(unreadable(path, result));
            }
            if (result.truncated()) {
                return Read.error("Fichier trop volumineux pour être lu depuis la machine (" + path
                        + ", 512 Kio au plus) : passe le document en html, allège-le, ou joins-le en data:.");
            }
            return Read.ok((result.content() == null ? "" : result.content()).getBytes(StandardCharsets.UTF_8));
        }
        try {
            String content = workspaceService.readFile(userId, workspace.getId(), path);
            return Read.ok((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return Read.error("Fichier illisible dans le projet hébergé (" + path + ") : " + reason(e));
        }
    }

    // ------------------------------------------------------------------ lecture binaire (images)

    /** Les octets d'une image, lus sur le poste (RUNNER, par tranches) ou dans le stockage (SANDBOX). */
    private Read readBytes(UUID userId, Workspace workspace, String callId, String name, String path, long cap) {
        if (workspace.isRunnerTarget()) {
            return readBytesFromRunner(userId, RunnerTargets.of(workspace), callId, name, path, cap);
        }
        try {
            byte[] content = workspaceService.readFileBytes(userId, workspace.getId(), path);
            if (content.length > cap) {
                return Read.error(tooLargeImage(name, cap));
            }
            return Read.ok(content);
        } catch (RuntimeException e) {
            return Read.error("Image illisible dans le projet hébergé (" + path + ") : " + reason(e));
        }
    }

    /**
     * Lit une image du poste par tranches ({@code read_file_bytes}) : {@code content} porte les octets en
     * Base64, {@code bytes} la taille totale, {@code truncated} vaut vrai s'il en reste. Même patron que
     * F-110 / SF-110-03. La lecture est tracée une fois, sur le {@code callId} de la pièce.
     */
    private Read readBytesFromRunner(UUID userId, RunnerTarget target, String callId, String name, String path,
            long cap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Long size = null;
        RunnerCallResult last = null;
        int chunk = 0;
        while (true) {
            RunnerCallResult result = runnerToolGateway.readFileBytes(target, callId + "." + chunk, path,
                    out.size(), CHUNK_BYTES);
            last = result;
            if (!result.ok()) {
                runnerAuditService.recordCall(userId, target, callId, PageToolCatalog.PUBLISH, path, result);
                if (chunk == 0 && RunnerErrorCodes.UNSUPPORTED_TOOL.equals(result.errorCode())) {
                    return Read.error("Le runner de ce poste est trop ancien pour joindre l'image « " + name
                            + " » : mets-le à jour depuis la Forge, ou embarque l'image en data: dans la page.");
                }
                return Read.error(unreadable(path, result));
            }
            byte[] part;
            try {
                part = Base64.getDecoder().decode(result.content() == null ? "" : result.content());
            } catch (IllegalArgumentException e) {
                runnerAuditService.recordCall(userId, target, callId, PageToolCatalog.PUBLISH, path, result);
                return Read.error("Image illisible sur la machine (" + path + ") : réponse du runner invalide.");
            }
            if (result.bytes() == null || result.bytes() < 0) {
                runnerAuditService.recordCall(userId, target, callId, PageToolCatalog.PUBLISH, path, result);
                return Read.error("Image illisible sur la machine (" + path + ") : réponse du runner invalide.");
            }
            long announced = result.bytes();
            if (size == null) {
                size = announced;
                if (size > cap) {
                    runnerAuditService.recordCall(userId, target, callId, PageToolCatalog.PUBLISH, path, result);
                    return Read.error(tooLargeImage(name, cap));
                }
            } else if (announced != size) {
                runnerAuditService.recordCall(userId, target, callId, PageToolCatalog.PUBLISH, path, result);
                return Read.error("L'image « " + name + " » a changé pendant la lecture : réessaie quand elle ne "
                        + "bouge plus.");
            }
            out.write(part, 0, part.length);
            if (out.size() > cap) {
                runnerAuditService.recordCall(userId, target, callId, PageToolCatalog.PUBLISH, path, result);
                return Read.error(tooLargeImage(name, cap));
            }
            if (!result.truncated() || part.length == 0 || out.size() >= size) {
                break;
            }
            chunk++;
        }
        runnerAuditService.recordCall(userId, target, callId, PageToolCatalog.PUBLISH, path, last);
        if (size != null && out.size() != size) {
            return Read.error("L'image « " + name + " » a changé pendant la lecture : réessaie quand elle ne "
                    + "bouge plus.");
        }
        return Read.ok(out.toByteArray());
    }

    // ------------------------------------------------------------------ messages

    private String tooLargeImage(String name, long cap) {
        return "Image trop volumineuse : « " + name + " » dépasse " + (cap / (1024 * 1024)) + " Mo. Allège-la, ou "
                + "retire-la.";
    }

    private static String unreadable(String path, RunnerCallResult result) {
        String reason = result.errorMessage() == null || result.errorMessage().isBlank()
                ? "lecture impossible" : result.errorMessage();
        return "Fichier illisible sur la machine (" + path + ") : " + reason;
    }

    private static String reason(RuntimeException e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? "lecture impossible" : e.getMessage();
    }

    private static String unknownPage() {
        return "page_id inconnu : aucune page de ce compte ne porte cet identifiant. Omets page_id pour publier "
                + "une nouvelle page.";
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
     * @param published la page rangée, ou {@code null}
     */
    public record Outcome(String content, boolean error, PageService.PublishedPage published) {

        static Outcome error(String message) {
            return new Outcome(message, true, null);
        }
    }
}
