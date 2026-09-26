package fr.claudegateway.office;

import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.ProjectFileDeposit;
import fr.claudegateway.atelier.ProjectFileNames;
import fr.claudegateway.atelier.ProjectFileRead;
import fr.claudegateway.atelier.Workspace;

/**
 * <b>Exécute {@code build_document} et {@code build_spreadsheet}</b> (F-129 / SF-129-07) : lit les
 * images <b>dans le projet</b>, fait construire le fichier par la gateway, et le dépose là où vit
 * le projet.
 *
 * <p><b>Les chemins d'images viennent du modèle</b> : ils sont donc validés ici — relatifs, sans
 * remontée de dossier ({@link ProjectFileNames}). Le reste (lecture, dépôt) emprunte les composants
 * partagés, ceux-là mêmes qui servent aux diagrammes, aux images décoratives et au deck.</p>
 */
@Component
public class OfficeToolExecutor {

    /** Borne de lecture d'une image insérée. */
    static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    /** Nombre maximal d'images d'un document — la même que côté service. */
    static final int MAX_IMAGES = 20;
    /** Bornes de la description, tenues ici AUSSI : une borne d'un seul côté n'est plus tenue. */
    static final int MAX_BLOCKS = 300;
    static final int MAX_SHEETS = 12;

    private final OfficeBuilder builder;
    private final ProjectFileRead reader;
    private final ProjectFileDeposit deposit;
    private final ObjectMapper mapper;

    public OfficeToolExecutor(OfficeBuilder builder, ProjectFileRead reader,
            ProjectFileDeposit deposit, ObjectMapper mapper) {
        this.builder = builder;
        this.reader = reader;
        this.deposit = deposit;
        this.mapper = mapper;
    }

    /** Le résultat rendu à la boucle. */
    public record Outcome(String content, boolean error) {

        static Outcome error(String message) {
            return new Outcome(message, true);
        }
    }

    public Outcome execute(UUID userId, Workspace workspace, String callId, String tool,
            JsonNode input) {
        OfficeFormat format = OfficeFormat.ofTool(tool);
        if (format == null) {
            return Outcome.error("Outil inconnu : " + tool + ".");
        }
        JsonNode spec = input == null ? null : input.path("spec");
        JsonNode content = spec == null ? null : spec.path(format.contentField());
        if (spec == null || !spec.isObject() || content == null || !content.isArray()
                || content.isEmpty()) {
            return Outcome.error("spec est requis : {title, " + format.contentField()
                    + ":[…]} — au moins un élément.");
        }
        int max = format == OfficeFormat.DOCX ? MAX_BLOCKS : MAX_SHEETS;
        if (content.size() > max) {
            return Outcome.error("Description trop grande : " + content.size() + " « "
                    + format.contentField() + " », maximum " + max + ".");
        }
        ObjectNode payload = spec.deepCopy();

        // Les images : lues DANS LE PROJET, sous l'isolation du tour. Le modèle donne un chemin,
        // pas un contenu — et ce chemin est validé AVANT toute lecture.
        JsonNode images = input.path("images");
        if (format == OfficeFormat.DOCX && images.isObject() && !images.isEmpty()) {
            if (images.size() > MAX_IMAGES) {
                return Outcome.error("Trop d'images (" + images.size() + ", maximum " + MAX_IMAGES + ").");
            }
            ObjectNode encoded = payload.putObject("images");
            Iterator<Map.Entry<String, JsonNode>> fields = images.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String path = entry.getValue().asText("").strip();
                String refused = ProjectFileNames.refusePath(path, "d'image");
                if (refused != null) {
                    return Outcome.error(refused);
                }
                ProjectFileRead.Read read = reader.read(userId, workspace, callId, path,
                        MAX_IMAGE_BYTES, tool);
                if (read.failed()) {
                    return Outcome.error("Image « " + path + " » introuvable ou illisible dans le "
                            + "projet : " + read.error() + " Un document avec une image manquante "
                            + "est pire qu'un document sans image — produis-la d'abord "
                            + "(render_diagram), ou retire le bloc.");
                }
                encoded.put(entry.getKey(), Base64.getEncoder().encodeToString(read.content()));
            }
        }

        byte[] file;
        try {
            file = builder.build(format, payload);
        } catch (OfficeRejectedException e) {
            return Outcome.error(capitalized(format) + " non construit : " + e.getMessage()
                    + " Corrige la description.");
        } catch (OfficeBuilderUnavailableException e) {
            return Outcome.error("La construction de documents est indisponible (" + e.getMessage()
                    + "). Si python-docx / openpyxl sont DÉJÀ présents sur ce poste, tu peux "
                    + "produire le fichier toi-même ; ne les installe pas, et dis-le si tu ne peux "
                    + "pas produire le " + format.label() + ".");
        } catch (RuntimeException e) {
            return Outcome.error("Construction en échec : " + e.getClass().getSimpleName() + ".");
        }

        String name = ProjectFileNames.clean(text(input, "filename"), spec.path("title").asText(""),
                format.extension(), format.label());
        String deposited = deposit.deposit(userId, workspace, callId, name, file,
                format.contentType(), tool);
        if (deposited == null) {
            return Outcome.error(capitalized(format) + " construit (" + file.length + " octets), mais "
                    + "son dépôt dans le projet a échoué : réessaie.");
        }
        return new Outcome(capitalized(format) + " construit par la gateway et déposé dans le projet "
                + "sous « " + deposited + " » (" + content.size() + " "
                + (format == OfficeFormat.DOCX ? "blocs" : "feuilles")
                + "). Rien n'a été installé sur la machine.", false);
    }

    /** Titre lisible pour l'étape et le journal. */
    public static String auditTarget(JsonNode input) {
        if (input == null) {
            return null;
        }
        String title = input.path("spec").path("title").asText("");
        return title.isBlank() ? "document" : title;
    }

    private static String capitalized(OfficeFormat format) {
        String label = format.label();
        return Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }

    private static String text(JsonNode input, String field) {
        if (input == null) {
            return "";
        }
        JsonNode node = input.path(field);
        return node.isTextual() ? node.asText().strip() : "";
    }
}
