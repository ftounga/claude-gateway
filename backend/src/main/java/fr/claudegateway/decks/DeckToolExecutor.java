package fr.claudegateway.decks;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.ProjectFileDeposit;
import fr.claudegateway.atelier.ProjectFileRead;
import fr.claudegateway.atelier.Workspace;

/**
 * <b>Exécute {@code build_presentation}</b> (F-129 / SF-129-05) : lit les images <b>dans le projet</b>,
 * fait construire le {@code .pptx} par la gateway, et le dépose là où vit le projet.
 *
 * <p><b>Les chemins d'images viennent du modèle</b> : ils sont donc validés ici — relatifs, sans
 * remontée de dossier. Le reste (lecture, dépôt) emprunte les composants partagés, ceux-là mêmes qui
 * servent aux diagrammes et aux images décoratives.</p>
 */
@Component
public class DeckToolExecutor {

    /** Borne de lecture d'une image insérée. */
    static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    /** Nombre maximal d'images d'un deck. */
    static final int MAX_IMAGES = 20;
    /** Au-delà, l'aperçu n'est pas demandé : déposer 30 images sur un poste prend déjà du temps. */
    static final int MAX_PREVIEW_SLIDES = 30;
    static final String DEFAULT_NAME = "presentation";
    static final int MAX_NAME = 60;

    private final DeckBuilder builder;
    private final ProjectFileRead reader;
    private final ProjectFileDeposit deposit;
    private final ObjectMapper mapper;

    public DeckToolExecutor(DeckBuilder builder, ProjectFileRead reader, ProjectFileDeposit deposit,
            ObjectMapper mapper) {
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

    public Outcome execute(UUID userId, Workspace workspace, String callId, JsonNode input) {
        JsonNode spec = input == null ? null : input.path("spec");
        if (spec == null || !spec.isObject() || !spec.path("slides").isArray()
                || spec.path("slides").isEmpty()) {
            return Outcome.error("spec est requis : {title, slides:[{type, title, …}]} — au moins une slide.");
        }
        ObjectNode payload = spec.deepCopy();

        // L'aperçu (F-129 / SF-129-06) : demandé PAR DÉFAUT, parce qu'une présentation qu'on ne
        // peut pas lire dans l'application est exactement le défaut qu'on ferme ici. L'agent peut
        // le refuser (« preview »: false), et un deck trop long ne le reçoit pas.
        boolean preview = !input.path("preview").isBoolean() || input.path("preview").asBoolean();
        if (spec.path("preview").isBoolean() && !spec.path("preview").asBoolean()) {
            preview = false;
        }
        if (spec.path("slides").size() > MAX_PREVIEW_SLIDES) {
            preview = false;
        }
        payload.put("preview", preview);

        // Les images : lues DANS LE PROJET, sous l'isolation du tour. Le modèle donne un chemin, pas
        // un contenu — et ce chemin est validé avant toute lecture.
        JsonNode images = input.path("images");
        if (images.isObject() && !images.isEmpty()) {
            if (images.size() > MAX_IMAGES) {
                return Outcome.error("Trop d'images (" + images.size() + ", maximum " + MAX_IMAGES + ").");
            }
            ObjectNode encoded = payload.putObject("images");
            Iterator<Map.Entry<String, JsonNode>> fields = images.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String path = entry.getValue().asText("").strip();
                String refused = refusePath(path);
                if (refused != null) {
                    return Outcome.error(refused);
                }
                ProjectFileRead.Read read = reader.read(userId, workspace, callId, path, MAX_IMAGE_BYTES,
                        DeckToolCatalog.BUILD);
                if (read.failed()) {
                    return Outcome.error("Image « " + path + " » introuvable ou illisible dans le projet : "
                            + read.error() + " Un deck avec une image manquante est pire qu'un deck sans "
                            + "image — produis-la d'abord (render_diagram), ou retire la slide.");
                }
                encoded.put(entry.getKey(), Base64.getEncoder().encodeToString(read.content()));
            }
        }

        DeckBuilder.Deck deck;
        try {
            deck = builder.build(payload);
        } catch (DeckRejectedException e) {
            return Outcome.error("Présentation non construite : " + e.getMessage()
                    + " Corrige la description.");
        } catch (DeckBuilderUnavailableException e) {
            return Outcome.error("La construction de présentations est indisponible (" + e.getMessage()
                    + "). Si python-pptx est DÉJÀ présent sur ce poste, tu peux produire le fichier "
                    + "toi-même ; ne l'installe pas, et dis-le si tu ne peux pas produire le deck.");
        } catch (RuntimeException e) {
            return Outcome.error("Construction en échec : " + e.getClass().getSimpleName() + ".");
        }

        String name = fileName(text(input, "filename"), spec.path("title").asText(""));
        String deposited = deposit.deposit(userId, workspace, callId, name, deck.bytes(),
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                DeckToolCatalog.BUILD);
        if (deposited == null) {
            return Outcome.error("Présentation construite (" + deck.bytes().length + " octets), mais son "
                    + "dépôt dans le projet a échoué : réessaie.");
        }

        // Les images de l'aperçu suivent EXACTEMENT le chemin du fichier : même dépôt, même
        // isolation. Aucun chemin ne vient du modèle — les noms sont dérivés de celui du deck.
        List<String> shots = depositPreview(userId, workspace, callId, deposited, deck.slides());

        StringBuilder message = new StringBuilder("Présentation construite par la gateway et déposée "
                + "dans le projet sous « " + deposited + " » ("
                + spec.path("slides").size() + " slides).");
        if (!shots.isEmpty()) {
            message.append(" Aperçu rendu par la gateway : ").append(shots.size())
                    .append(" images déposées (").append(String.join(", ", shots))
                    .append("). Publie avec presentation_publish en donnant le .pptx ET ces images "
                            + "dans « slides », DANS CET ORDRE : la présentation sera alors lisible "
                            + "entièrement dans l'application.");
            if (shots.size() < deck.slides().size()) {
                message.append(" ").append(deck.slides().size() - shots.size())
                        .append(" image(s) n'ont pas pu être déposées : l'aperçu s'arrête là.");
            }
        } else {
            message.append(" Aperçu non produit")
                    .append(preview ? (deck.previewError().isBlank() ? "" : " (" + deck.previewError() + ")")
                            : " (non demandé)")
                    .append(" : publie-le avec presentation_publish sans « slides » — il reste "
                            + "téléchargeable, et la visionneuse affichera son repli.");
        }
        message.append(" Rien n'a été installé sur la machine.");
        return new Outcome(message.toString(), false);
    }

    /**
     * Dépose les images d'aperçu et rend leurs noms, <b>dans l'ordre</b>. Un dépôt en échec arrête
     * la série : un aperçu partiel mais ordonné se lit, un aperçu troué induit en erreur.
     */
    private List<String> depositPreview(UUID userId, Workspace workspace, String callId,
            String deckName, List<byte[]> slides) {
        List<String> names = new ArrayList<>();
        String base = deckName.toLowerCase(Locale.ROOT).endsWith(".pptx")
                ? deckName.substring(0, deckName.length() - 5)
                : deckName;
        int index = 0;
        for (byte[] image : slides) {
            index++;
            String name = String.format("%s-slide-%02d.png", base, index);
            String written = deposit.deposit(userId, workspace, callId, name, image, "image/png",
                    DeckToolCatalog.BUILD);
            if (written == null) {
                break;
            }
            names.add(written);
        }
        return names;
    }

    /** Titre lisible pour l'étape et le journal. */
    public static String auditTarget(JsonNode input) {
        if (input == null) {
            return null;
        }
        String title = input.path("spec").path("title").asText("");
        return title.isBlank() ? "présentation" : title;
    }

    /** Un chemin du modèle : relatif, sans remontée. Sinon, refus <b>dit</b>. */
    static String refusePath(String path) {
        if (path.isEmpty()) {
            return "Chemin d'image vide.";
        }
        if (path.startsWith("/") || path.startsWith("\\") || path.matches("^[A-Za-z]:.*")) {
            return "Chemin d'image absolu refusé (« " + path + " ») : donne un chemin du projet.";
        }
        if (path.contains("..")) {
            return "Chemin d'image hors du projet refusé (« " + path + " »).";
        }
        return null;
    }

    /** Le nom du fichier déposé : nettoyé, jamais un chemin. */
    static String fileName(String raw, String title) {
        String base = raw == null || raw.isBlank() ? title : raw;
        base = base == null ? "" : base.strip();
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        if (base.toLowerCase(Locale.ROOT).endsWith(".pptx")) {
            base = base.substring(0, base.length() - 5);
        }
        StringBuilder sb = new StringBuilder();
        for (char c : base.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                sb.append(c);
            } else if (c == ' ' || c == '.' || c == '\'') {
                sb.append('-');
            }
            if (sb.length() >= MAX_NAME) {
                break;
            }
        }
        String cleaned = sb.toString().replaceAll("-+", "-").replaceAll("^-|-$", "");
        return (cleaned.isEmpty() ? DEFAULT_NAME : cleaned) + ".pptx";
    }

    private static String text(JsonNode input, String field) {
        if (input == null) {
            return "";
        }
        JsonNode node = input.path(field);
        return node.isTextual() ? node.asText().strip() : "";
    }
}
