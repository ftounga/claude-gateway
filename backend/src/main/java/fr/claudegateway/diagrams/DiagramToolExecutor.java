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
        String engine = text(input, "engine");
        boolean cloud = "cloud".equalsIgnoreCase(engine);
        // F-142 / SF-142-13 : le troisième moteur ne rend pas une image mais DEUX artefacts — la
        // source réouvrable dans draw.io, et son aperçu.
        boolean editable = "drawio".equalsIgnoreCase(engine);
        JsonNode spec = input == null ? null : input.path("spec");
        String code = text(input, "code");
        if ((cloud || editable) && (spec == null || !spec.isObject())) {
            return Outcome.error("Pour engine=" + (editable ? "drawio" : "cloud")
                    + ", donne « spec » : les nœuds (id, label" + (editable ? "" : ", type")
                    + "), les groupes et les liens. Le service dessine à partir de cette description.");
        }
        if (editable) {
            return executeEditable(userId, workspace, callId, input, spec);
        }
        if (!cloud && code.isEmpty()) {
            return Outcome.error("code est requis : le diagramme en Mermaid "
                    + "(ou engine=cloud avec « spec » pour les icônes officielles).");
        }
        // F-142 / SF-142-18 : le moteur « cloud » rend désormais du SVG — vectoriel, il reste NET quand
        // une page l'affiche en width:100%, là où le PNG rapetissé rendait les libellés illisibles. On
        // ne force donc plus PNG pour cloud ; Mermaid garde son format demandé (png par défaut, svg au choix).
        Format format = cloud ? Format.SVG : Format.of(text(input, "format"));
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
        String warning = rendered.hasUnknownTypes()
                // F-142 / SF-142-09 : dit, et pas seulement dessiné. L'utilisateur doit savoir quels
                // composants n'ont pas d'icône officielle — c'est à lui de juger si c'est acceptable.
                ? " ATTENTION : ces composants n'ont pas d'icône officielle et sont dessinés en boîte "
                        + "neutre — " + rendered.unknownTypes() + ". DIS-LE à l'utilisateur ; ne remplace "
                        + "jamais un composant par une icône approchante."
                : "";
        // F-142 / SF-142-17 : une note sur le schéma lui-même (sa densité). L'image EXISTE — ce n'est
        // pas une erreur ; c'est une invitation à le scinder, que l'agent doit pouvoir relayer.
        String notice = rendered.hasNotice() ? " NOTE DU RENDU : " + rendered.notice() : "";
        return new Outcome("Diagramme rendu par la gateway et déposé dans le projet sous « " + deposited
                + " ». Insère ce chemin : add_picture pour une slide, <img src=\"" + deposited + "\"> pour "
                + "une page, image pour un document. Rien n'a été installé sur la machine."
                + warning + notice, false);
    }

    /**
     * Le moteur <b>draw.io</b> (F-142 / SF-142-13) : <b>deux</b> fichiers déposés, <b>deux</b> chemins
     * rendus.
     *
     * <p><b>L'ordre n'est pas indifférent</b> : le {@code .drawio} part le premier, parce que c'est lui
     * qui a de la valeur. Un aperçu manquant se dit et n'empêche rien ; un {@code .drawio} manquant, si.</p>
     */
    private Outcome executeEditable(UUID userId, Workspace workspace, String callId, JsonNode input,
            JsonNode spec) {
        DiagramRenderer.Editable rendered;
        try {
            rendered = renderer.renderEditable(spec);
        } catch (DiagramRejectedException e) {
            return Outcome.error("Schéma non rendu : " + e.getMessage()
                    + " Corrige la description ; ne fabrique pas de fichier.");
        } catch (DiagramRendererUnavailableException e) {
            return Outcome.error("Le rendu de diagrammes est indisponible (" + e.getMessage()
                    + "). Tu peux livrer le diagramme dans une PAGE (bloc <pre class=\"mermaid\">, rendu "
                    + "par le navigateur, sans rien installer), et dire que le fichier draw.io n'a pas pu "
                    + "être produit. Ne fabrique pas de fichier.");
        } catch (RuntimeException e) {
            return Outcome.error("Rendu de diagramme en échec : " + e.getClass().getSimpleName() + ".");
        }

        String base = text(input, "filename");
        String source = fileName(base, Format.DRAWIO);
        String deposited = deposit.deposit(userId, workspace, callId, source, rendered.drawio(),
                Format.DRAWIO.contentType(), DiagramToolCatalog.RENDER);
        if (deposited == null) {
            return Outcome.error("Schéma rendu (" + rendered.drawio().length + " octets), mais son dépôt "
                    + "dans le projet a échoué : réessaie, ou livre le diagramme dans une page.");
        }
        String preview = null;
        if (rendered.hasPreview()) {
            preview = deposit.deposit(userId, workspace, callId + ".png",
                    fileName(source, Format.PNG), rendered.png(), Format.PNG.contentType(),
                    DiagramToolCatalog.RENDER);
        }
        StringBuilder message = new StringBuilder();
        message.append("Schéma rendu par la gateway. Fichier RÉOUVRABLE déposé sous « ").append(deposited)
                .append(" » : l'utilisateur peut l'ouvrir et le MODIFIER dans draw.io / diagrams.net "
                        + "(ou VS Code, ou Confluence). DIS-LE-LUI — sans cela il ne le saura pas.");
        if (preview != null) {
            message.append(" Aperçu PNG déposé sous « ").append(preview)
                    .append(" » : insère ce chemin dans le livrable (add_picture pour une slide, "
                            + "<img src=\"").append(preview).append("\"> pour une page).");
        } else {
            // L'aperçu est perdu, pas le schéma. Le dire évite que l'agent invente une image.
            message.append(" AUCUN aperçu PNG n'a pu être produit (")
                    .append(rendered.previewError() == null || rendered.previewError().isBlank()
                            ? "dépôt de l'aperçu en échec" : rendered.previewError())
                    .append(") : livre le fichier draw.io, dis que l'image manque, et ne fabrique pas "
                            + "d'image de remplacement.");
        }
        message.append(" Rien n'a été installé sur la machine.");
        return new Outcome(message.toString(), false);
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
