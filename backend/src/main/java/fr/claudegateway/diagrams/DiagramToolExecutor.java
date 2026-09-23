package fr.claudegateway.diagrams;

import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.atelier.ProjectFileDeposit;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.diagrams.DiagramRenderer.Format;

/**
 * <b>Exécute {@code render_diagram}</b> (F-142 / SF-142-06) : rend le diagramme <b>côté gateway</b>,
 * puis le dépose là où vit le projet — poste ou hébergé — par le chemin <b>déjà existant</b> des
 * images décoratives ({@link ProjectFileDeposit}).
 *
 * <p><b>Trois issues, trois phrases différentes</b>, parce qu'elles n'appellent pas la même suite :
 * un <b>code invalide</b> se corrige (on rend la raison du moteur) ; un <b>service muet</b> n'a rien à
 * voir avec le code (on propose le rendu en page, qui ne dépend de rien depuis SF-142-05) ; un
 * <b>dépôt en échec</b> signifie que l'image existe mais n'est pas arrivée.</p>
 */
@Component
public class DiagramToolExecutor {

    /** Nom de fichier par défaut quand l'agent n'en propose pas. */
    static final String DEFAULT_BASE = "diagramme";
    /** Longueur maximale du nom de fichier retenu. */
    static final int MAX_NAME = 60;

    private final DiagramRenderer renderer;
    private final ProjectFileDeposit deposit;

    public DiagramToolExecutor(DiagramRenderer renderer, ProjectFileDeposit deposit) {
        this.renderer = renderer;
        this.deposit = deposit;
    }

    /** Le résultat rendu à la boucle : un texte, et s'il est une erreur. */
    public record Outcome(String content, boolean error) {

        static Outcome error(String message) {
            return new Outcome(message, true);
        }
    }

    public Outcome execute(UUID userId, Workspace workspace, String callId, JsonNode input) {
        // F-142 / SF-142-07 : deux moteurs, une seule suite. « cloud » dessine avec les icônes
        // OFFICIELLES à partir d'une DESCRIPTION ; « mermaid » (défaut) rend du code Mermaid.
        boolean cloud = "cloud".equalsIgnoreCase(text(input, "engine"));
        JsonNode spec = input == null ? null : input.path("spec");
        String code = text(input, "code");
        if (cloud && (spec == null || !spec.isObject())) {
            return Outcome.error("Pour engine=cloud, donne « spec » : les nœuds (id, type, label), les "
                    + "groupes et les liens. Le service dessine à partir de cette description.");
        }
        if (!cloud && code.isEmpty()) {
            return Outcome.error("code est requis : le diagramme en Mermaid "
                    + "(ou engine=cloud avec « spec » pour les icônes officielles).");
        }
        Format format = cloud ? Format.PNG : Format.of(text(input, "format"));
        Integer width = input != null && input.path("width").isInt() ? input.path("width").asInt() : null;

        DiagramRenderer.Rendered rendered;
        try {
            rendered = cloud ? renderer.renderCloud(spec) : renderer.render(code, format, width);
        } catch (DiagramRejectedException e) {
            // Le code (ou la description) est en cause : la raison du moteur permet de le corriger.
            return Outcome.error("Diagramme non rendu : " + e.getMessage()
                    + (cloud ? " Corrige la description ; ne fabrique pas d'image."
                             : " Corrige le code ; ne fabrique pas d'image."));
        } catch (DiagramRendererUnavailableException e) {
            // Le code n'y est pour rien : on propose le repli qui, lui, ne dépend de rien.
            return Outcome.error("Le rendu de diagrammes est indisponible (" + e.getMessage()
                    + "). Tu peux livrer le diagramme dans une PAGE (bloc <pre class=\"mermaid\">, rendu "
                    + "par le navigateur, sans rien installer), et dire que l'image n'a pas pu être "
                    + "produite. Ne fabrique pas d'image.");
        } catch (RuntimeException e) {
            return Outcome.error("Rendu de diagramme en échec : " + e.getClass().getSimpleName() + ".");
        }

        String name = fileName(text(input, "filename"), format);
        String deposited = deposit.deposit(userId, workspace, callId, name, rendered.bytes(),
                format.contentType(), DiagramToolCatalog.RENDER);
        if (deposited == null) {
            return Outcome.error("Diagramme rendu (" + rendered.bytes().length + " octets), mais son dépôt "
                    + "dans le projet a échoué : réessaie, ou livre le diagramme dans une page.");
        }
        return new Outcome("Diagramme rendu par la gateway et déposé dans le projet sous « " + deposited
                + " ». Insère ce chemin : add_picture pour une slide, <img src=\"" + deposited + "\"> pour "
                + "une page, image pour un document. Rien n'a été installé sur la machine.", false);
    }

    /** Titre lisible pour l'étape et le journal : la première ligne du diagramme, jamais tout le code. */
    public static String auditTarget(JsonNode input) {
        String code = text(input, "code");
        if (code.isEmpty() && input != null && input.path("spec").isObject()) {
            String title = input.path("spec").path("title").asText("");
            return title.isBlank() ? "schéma cloud" : title;
        }
        if (code.isEmpty()) {
            return null;
        }
        String first = code.lines().findFirst().orElse("").strip();
        return first.length() > 80 ? first.substring(0, 80) : first;
    }

    /**
     * Le nom du fichier déposé : <b>nettoyé</b>, jamais un chemin. Un nom venu du modèle ne doit pas
     * pouvoir désigner un autre dossier que celui du projet.
     */
    static String fileName(String raw, Format format) {
        String base = raw == null ? "" : raw.strip();
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        String lower = base.toLowerCase(Locale.ROOT);
        for (Format candidate : Format.values()) {
            if (lower.endsWith(candidate.extension())) {
                base = base.substring(0, base.length() - candidate.extension().length());
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (char c : base.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                sb.append(c);
            } else if (c == ' ' || c == '.') {
                sb.append('-');
            }
            if (sb.length() >= MAX_NAME) {
                break;
            }
        }
        String cleaned = sb.toString().replaceAll("-+", "-").replaceAll("^-|-$", "");
        return (cleaned.isEmpty() ? DEFAULT_BASE + "-" + UUID.randomUUID().toString().substring(0, 8) : cleaned)
                + format.extension();
    }

    private static String text(JsonNode input, String field) {
        if (input == null) {
            return "";
        }
        JsonNode node = input.path(field);
        return node.isTextual() ? node.asText().strip() : "";
    }
}
