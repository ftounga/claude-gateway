package fr.claudegateway.images;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.SpaceEntitlementService;

/**
 * <b>L'outil {@code generate_image} donné à un agent — et la garde qui décide s'il l'est</b>
 * (F-142 / SF-142-04).
 *
 * <p>Même doctrine que les pages (F-109) et les présentations (F-129) : la garde est au niveau de
 * l'outil, donné à un utilisateur qui a le <b>droit de l'espace du terminal</b> (Vigie pour un terminal
 * Teams, Forge sinon), quelle que soit la cible d'exécution ; {@link SpaceEntitlementService} l'ouvre
 * d'office à l'ADMIN. Sans le droit : aucun outil, aucun guide.</p>
 *
 * <h2>Le guide de doctrine</h2>
 * <p>{@link #GUIDE} rejoint la consigne <b>sous la même garde</b>. Il enseigne la <b>frontière</b> :
 * images <b>décoratives uniquement</b> (couverture, ambiance) ; <b>jamais</b> un schéma d'architecture ni
 * un diagramme technique — ceux-là restent du diagramme-as-code (Mermaid SF-142-01/02, {@code diagrams}
 * SF-142-03).</p>
 */
@Component
public class ImageToolCatalog {

    /** Le nom de l'outil. */
    public static final String GENERATE = "generate_image";

    /** Le guide de doctrine ajouté à la consigne quand l'outil est donné. */
    public static final String GUIDE = "--- Images décoratives (génération IA) ---\n"
            + "Tu peux GÉNÉRER une IMAGE DÉCORATIVE (generate_image) : la gateway relaie un fournisseur "
            + "d'images (OpenAI), range l'image, la dépose dans le projet et te rend son chemin. Sers-t'en "
            + "pour l'ORNEMENT d'un livrable : couverture d'un deck, visuel d'ambiance d'une page, bandeau, "
            + "vignette d'illustration.\n"
            + "FRONTIÈRE ABSOLUE — DÉCORATIF SEULEMENT : n'utilise JAMAIS une image IA pour un SCHÉMA "
            + "D'ARCHITECTURE, un diagramme technique, un flux, une séquence, un réseau — l'IA d'images "
            + "invente des icônes, du texte en charabia, des liens absurdes : inutilisable en livrable. Pour "
            + "un schéma, reste au DIAGRAMME-AS-CODE : Mermaid dans une page (flowchart, sequenceDiagram, "
            + "architecture-beta), ou la lib diagrams (icônes cloud officielles) rendue en PNG — jamais "
            + "generate_image.\n"
            + "COMMENT : appelle generate_image avec prompt (la description de l'image — une consigne "
            + "visuelle, pas des données sensibles ni de l'archi client), size facultatif (1024x1024, "
            + "1536x1024, 1024x1536) et filename facultatif. La gateway te rend un chemin déposé dans le "
            + "projet : réfère-le ensuite en pièce jointe d'une page (attachments de page_publish, "
            + "<img src=\"couverture.png\">) ou insère-le dans une slide (add_picture, skill pptx). "
            + "L'utilisateur confirme d'un clic ; c'est un service payant et borné (nombre par tour, quota "
            + "de compte) — n'en génère pas à la volée, seulement quand une illustration sert vraiment.\n"
            + "CONFIDENTIALITÉ : l'image générée est décorative ; n'y mets aucune archi ni donnée sensible "
            + "(le prompt part chez le fournisseur).";

    private final SpaceEntitlementService entitlements;

    @Autowired
    public ImageToolCatalog(SpaceEntitlementService entitlements) {
        this.entitlements = entitlements;
    }

    /** Catalogue <b>vide</b> : l'outil n'est jamais donné (formes historiques, tests). */
    public static ImageToolCatalog none() {
        return new ImageToolCatalog(null);
    }

    /** Vrai si ce nom d'outil est celui de la génération d'images. */
    public static boolean isImageTool(String tool) {
        return GENERATE.equals(tool);
    }

    /** L'espace où se range une image générée depuis ce terminal. */
    public static ImageSpace spaceOf(Workspace workspace) {
        return workspace.isTeamsTerminal() ? ImageSpace.VIGIE : ImageSpace.FORGE;
    }

    /**
     * Vrai si l'outil est ouvert pour ce tour.
     *
     * @param userId    propriétaire du terminal (celui du tour, jamais un paramètre client)
     * @param workspace terminal du tour, déjà vérifié comme possédé
     */
    public boolean isOpenFor(UUID userId, Workspace workspace) {
        if (entitlements == null || userId == null || workspace == null) {
            return false;
        }
        EntitlementSpace space = spaceOf(workspace) == ImageSpace.VIGIE
                ? EntitlementSpace.VIGIE : EntitlementSpace.FORGE;
        try {
            return entitlements.isEntitled(userId, space);
        } catch (RuntimeException e) {
            // Abonnement illisible : fermé, comme toute garde.
            return false;
        }
    }

    /** L'outil à donner à l'agent pour ce tour, ou <b>la liste vide</b>. */
    public List<AgentTool> toolsFor(UUID userId, Workspace workspace) {
        return isOpenFor(userId, workspace) ? List.of(definition()) : List.of();
    }

    /** La définition de l'outil (schéma d'entrée). */
    static AgentTool definition() {
        return new AgentTool(GENERATE,
                "Génère une IMAGE DÉCORATIVE (couverture, ambiance, illustration) via un fournisseur "
                        + "d'images relayé par la gateway, la range et la dépose dans le projet ; te rend "
                        + "son chemin pour l'insérer en page (attachments) ou en slide (add_picture). "
                        + "JAMAIS pour un schéma d'architecture ou un diagramme technique (utilise Mermaid "
                        + "ou la lib diagrams). L'utilisateur confirme d'un clic ; service payant et borné.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "prompt", Map.of("type", "string",
                                        "description", "Description visuelle de l'image décorative à générer "
                                                + "(1000 caractères au plus). Une consigne d'illustration, pas "
                                                + "de l'architecture ni des données sensibles."),
                                "size", Map.of("type", "string",
                                        "description", "Taille facultative : 1024x1024 (défaut), 1536x1024 "
                                                + "(paysage) ou 1024x1536 (portrait).",
                                        "enum", List.of("1024x1024", "1536x1024", "1024x1536")),
                                "filename", Map.of("type", "string",
                                        "description", "Nom de fichier facultatif (extension .png) pour le "
                                                + "dépôt dans le projet ; dérivé de l'identifiant sinon.")),
                        "required", List.of("prompt")));
    }
}
