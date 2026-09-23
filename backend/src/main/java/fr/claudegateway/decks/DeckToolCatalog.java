package fr.claudegateway.decks;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;

/**
 * <b>L'outil qui construit une présentation</b> (F-129 / SF-129-05) : le {@code .pptx} est fabriqué
 * <b>par la gateway</b>, à partir d'une description — le poste du client n'installe rien.
 */
@Component
public class DeckToolCatalog {

    /** Le nom de l'outil. */
    public static final String BUILD = "build_presentation";

    /** Le guide de doctrine ajouté à la consigne quand l'outil est donné. */
    public static final String GUIDE = "--- Présentations construites par la gateway ---\n"
            + "Pour produire un .pptx, DÉCRIS-LE et appelle " + BUILD + " : la gateway construit le "
            + "fichier et le dépose dans le projet, puis tu le publies avec presentation_publish. "
            + "N'installe RIEN sur la machine du client (python-pptx, pip) : sur un poste d'entreprise "
            + "l'installation est bloquée, et le deck ne se produisait pas.\n"
            + "LA DESCRIPTION : {title, slides:[…]} où chaque slide porte un « type » — « title » "
            + "(titre + subtitle), « bullets » (titre + bullets[]), « text » (titre + text), « image » "
            + "(titre + image + caption), « table » (titre + rows[[…]]) — et, si utile, « notes ».\n"
            + "LES IMAGES : donne le CHEMIN d'un fichier DÉJÀ déposé dans le projet (un diagramme rendu "
            + "par render_diagram, une image décorative) dans « images » : {\"archi.png\": \"archi.png\"}. "
            + "La gateway le lit et l'insère. Une image absente est REFUSÉE — un deck avec une image "
            + "manquante est pire qu'un deck sans image.\n"
            + "GRATUIT : aucun appel fournisseur, aucun jeton.\n"
            + "SI python-pptx EST DÉJÀ présent sur le poste, l'ancienne voie (script python) reste "
            + "possible — mais ne l'installe jamais.";

    /** Vrai si ce nom d'outil est celui de la construction de deck. */
    public static boolean isDeckTool(String tool) {
        return BUILD.equals(tool);
    }

    private final DeckBuilder builder;

    public DeckToolCatalog(DeckBuilder builder) {
        this.builder = builder;
    }

    /** Catalogue <b>vide</b> : l'outil n'est jamais donné (formes historiques, tests). */
    public static DeckToolCatalog none() {
        return new DeckToolCatalog(null);
    }

    /** Vrai si l'outil est ouvert pour ce tour : il suffit qu'un constructeur soit configuré. */
    public boolean isOpenFor(UUID userId, Workspace workspace) {
        return builder != null && userId != null && workspace != null && builder.isAvailable();
    }

    /** L'outil à donner à l'agent pour ce tour, ou <b>la liste vide</b>. */
    public List<AgentTool> toolsFor(UUID userId, Workspace workspace) {
        return isOpenFor(userId, workspace) ? List.of(definition()) : List.of();
    }

    /** La définition de l'outil (schéma d'entrée). */
    static AgentTool definition() {
        return new AgentTool(BUILD,
                "CONSTRUIT une présentation .pptx à partir d'une DESCRIPTION, côté gateway, et la dépose "
                        + "dans le projet ; te rend son chemin, que tu publies ensuite avec "
                        + "presentation_publish. N'installe rien sur la machine du client. GRATUIT. "
                        + "Les images viennent de fichiers DÉJÀ déposés dans le projet (render_diagram, "
                        + "generate_image) : donne leur chemin.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "spec", Map.of("type", "object",
                                        "description", "La description : {title, slides:[{type, title, "
                                                + "subtitle|bullets|text|image|rows, caption, notes}]}. "
                                                + "Types : title, bullets, text, image, table."),
                                "images", Map.of("type", "object",
                                        "description", "Les images à insérer : {nom utilisé dans les "
                                                + "slides -> chemin du fichier dans le projet}."),
                                "filename", Map.of("type", "string",
                                        "description", "Nom du .pptx déposé ; dérivé du titre sinon.")),
                        "required", List.of("spec")));
    }
}
