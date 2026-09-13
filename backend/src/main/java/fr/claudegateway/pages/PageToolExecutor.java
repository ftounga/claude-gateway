package fr.claudegateway.pages;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerTargets;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * <b>Exécute {@code page_publish}</b> (F-109 / SF-109-02) : lit ce qu'il faut sur le poste, range la page
 * au lieu du terminal, et rend à l'agent ce qui a été rangé.
 *
 * <p>La garde et l'accord de l'utilisateur sont posés <b>avant</b>, par la boucle : cet exécuteur ne
 * décide de rien, il fait. Toute erreur est un <b>résultat d'outil en erreur</b> — le tour continue, et
 * l'agent reçoit une phrase qui dit quoi corriger.</p>
 *
 * <p>Les lectures du poste passent par le runner et sont <b>tracées</b> dans son journal : une page
 * publiée depuis un fichier de la machine est une lecture de la machine, et le journal doit la dire.</p>
 */
@Component
public class PageToolExecutor {

    /** Ce que le runner sait relire sans le corrompre : du texte (D3). */
    private static final Set<String> TEXT_ATTACHMENTS = Set.of("css", "js", "mjs", "json", "svg", "csv", "txt", "md");

    private final PageService pageService;
    private final RunnerToolGateway runnerToolGateway;
    private final RunnerAuditService runnerAuditService;

    public PageToolExecutor(PageService pageService, RunnerToolGateway runnerToolGateway,
            RunnerAuditService runnerAuditService) {
        this.pageService = pageService;
        this.runnerToolGateway = runnerToolGateway;
        this.runnerAuditService = runnerAuditService;
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

        RunnerTarget target = RunnerTargets.of(workspace);
        if (hasPath) {
            Read read = readFromMachine(userId, target, callId, path);
            if (read.error() != null) {
                return Outcome.error(read.error());
            }
            html = read.content();
        }

        Map<String, byte[]> attachments = new LinkedHashMap<>();
        JsonNode list = input == null ? null : input.get("attachments");
        if (list != null && list.isArray()) {
            int index = 0;
            for (JsonNode attachment : list) {
                index++;
                String name = text(attachment, "name");
                String attachmentPath = text(attachment, "path");
                if (!PageAttachments.isValidName(name) || !TEXT_ATTACHMENTS.contains(extension(name))) {
                    return Outcome.error("Pièce jointe refusée : « " + name + " ». Depuis la machine, seuls des "
                            + "fichiers texte au nom plat (css, js, mjs, json, svg, csv, txt, md) ; les images vont "
                            + "en data: dans la page.");
                }
                if (attachments.containsKey(name)) {
                    return Outcome.error("Deux pièces jointes portent le nom « " + name + " ».");
                }
                if (attachmentPath.isEmpty()) {
                    return Outcome.error("La pièce jointe « " + name + " » n'a pas de path.");
                }
                Read read = readFromMachine(userId, target, callId + "#" + index, attachmentPath);
                if (read.error() != null) {
                    return Outcome.error(read.error());
                }
                attachments.put(name, read.content().getBytes(StandardCharsets.UTF_8));
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

    private Read readFromMachine(UUID userId, RunnerTarget target, String callId, String path) {
        RunnerCallResult result = runnerToolGateway.readFile(target, callId, path);
        runnerAuditService.recordCall(userId, target, callId, PageToolCatalog.PUBLISH, path, result);
        if (!result.ok()) {
            String reason = result.errorMessage() == null || result.errorMessage().isBlank()
                    ? "lecture impossible" : result.errorMessage();
            return new Read(null, "Fichier illisible sur la machine (" + path + ") : " + reason);
        }
        if (result.truncated()) {
            return new Read(null, "Fichier trop volumineux pour être lu depuis la machine (" + path
                    + ", 512 Kio au plus) : passe le document en html, ou allège-le.");
        }
        return new Read(result.content() == null ? "" : result.content(), null);
    }

    private static String unknownPage() {
        return "page_id inconnu : aucune page de ce compte ne porte cet identifiant. Omets page_id pour publier "
                + "une nouvelle page.";
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static String text(JsonNode input, String field) {
        if (input == null || !input.hasNonNull(field)) {
            return "";
        }
        return input.get(field).asText("").strip();
    }

    private record Read(String content, String error) {
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
