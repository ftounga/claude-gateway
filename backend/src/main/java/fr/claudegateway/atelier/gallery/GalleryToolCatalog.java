package fr.claudegateway.atelier.gallery;

import java.util.List;
import java.util.Map;

import fr.claudegateway.agent.AgentTool;

/**
 * <b>La galerie d'images</b> (F-278 / SF-278-01) : l'outil {@value #TOOL} déclaré au modèle sur un terminal servi
 * par un poste (hors terminal Teams). Il lit les images par le runner existant ({@code read_file_bytes}) : aucune
 * mise à jour du poste.
 *
 * <p><b>Coût</b> : définition courte et FIXE (préfixe caché) ; les images ne reviennent jamais au modèle — il reçoit
 * un texte borné ({@link GalleryService}).</p>
 */
public final class GalleryToolCatalog {

    public static final String TOOL = "galerie_images";
    /** Images par appel. */
    public static final int MAX_IMAGES = 30;

    /** Message rendu hors poste (terminal hébergé). */
    public static final String HOSTED_MESSAGE = "La galerie d'images exige un poste connecté : ce terminal est "
            + "hébergé. Cite les fichiers en clair.";

    private static final AgentTool DEFINITION = new AgentTool(TOOL,
            "Affiche dans le fil une galerie d'images du poste (captures d'écran, photos) que l'utilisateur fait "
                    + "défiler d'un coup d'œil : nom, date et ta légende sous chaque image. Utilise-le dès que "
                    + "l'utilisateur veut voir des images au lieu de leurs noms (par ex. les captures liées aux "
                    + "demandes en attente). images : chemins dans l'ordre voulu, avec une légende courte ; ou "
                    + "dossier : ses images les plus récentes. Au plus " + MAX_IMAGES + " par appel. PNG, JPEG, "
                    + "GIF, BMP. Tu ne vois pas les images : ne les décris pas, ne les relis pas.",
            Map.of("type", "object",
                    "properties", Map.of(
                            "titre", Map.of("type", "string"),
                            "images", Map.of("type", "array", "maxItems", MAX_IMAGES,
                                    "items", Map.of("type", "object",
                                            "properties", Map.of(
                                                    "chemin", Map.of("type", "string"),
                                                    "legende", Map.of("type", "string"),
                                                    "date", Map.of("type", "string")),
                                            "required", List.of("chemin"))),
                            "dossier", Map.of("type", "string"),
                            "nombre", Map.of("type", "integer")),
                    "required", List.of("titre")));

    private GalleryToolCatalog() {
    }

    public static boolean isGalleryTool(String name) {
        return TOOL.equals(name);
    }

    /** La définition, identique d'un tour à l'autre. */
    public static AgentTool tool() {
        return DEFINITION;
    }
}
