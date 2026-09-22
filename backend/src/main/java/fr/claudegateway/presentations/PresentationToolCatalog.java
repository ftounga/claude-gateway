package fr.claudegateway.presentations;

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
 * <b>L'outil {@code presentation_publish} donné à un agent — et la garde qui décide s'il l'est</b>
 * (F-129 / SF-129-02). Prolonge {@code page_publish} (F-109) au format PowerPoint.
 *
 * <p>Même doctrine que les pages : la garde est au niveau de l'outil, donné à un utilisateur qui a le
 * <b>droit de l'espace du terminal</b> (Vigie pour un terminal Teams, Forge sinon), quelle que soit la
 * cible d'exécution ; {@link SpaceEntitlementService} l'ouvre d'office à l'ADMIN. Sans le droit : aucun
 * outil, aucun guide. L'exécuteur lit ensuite le fichier <b>là où vit le projet</b>.</p>
 */
@Component
public class PresentationToolCatalog {

    /** Le nom de l'outil. */
    public static final String PUBLISH = "presentation_publish";

    /** Le guide ajouté à la consigne quand l'outil est donné. */
    public static final String GUIDE = "--- Présentations (PowerPoint) ---\n"
            + "Tu peux CAPTURER une présentation .pptx que tu as produite sur le terminal (skill pptx / "
            + "python-pptx) : l'utilisateur la voit dans l'application, la range et la télécharge (le vrai "
            + "fichier, ouvrable dans PowerPoint/Keynote). Une présentation vaut pour un support structuré "
            + "en slides — un onboarding, une revue d'étapes, un plan — pas pour une réponse courte ni pour "
            + "ce qu'une page HTML (page_publish) rend déjà mieux à l'écran.\n"
            + "COMMENT : produis d'abord le .pptx (skill pptx), vérifie qu'il existe, puis appelle "
            + "presentation_publish avec un title court et le path du fichier sur la machine. La gateway lit "
            + "le fichier là où vit le projet (poste ou projet hébergé) et le range ; tu n'as pas à "
            + "l'encoder toi-même.\n"
            + "APERÇU IN-APP (lisible entièrement) : pour que l'utilisateur lise le deck slide par slide "
            + "SANS ouvrir PowerPoint, rends aussi le .pptx en IMAGES PNG (une par slide) et passe leurs "
            + "chemins ORDONNÉS dans slides. Le rendu se fait LÀ OÙ TU TRAVAILLES (sandbox de préférence : "
            + "`soffice --headless --convert-to pdf deck.pptx` puis `pdftoppm -png -r 150 deck.pdf slide`), "
            + "jamais sur un composant serveur dédié. Sans slides, seul le téléchargement est offert.\n"
            + "DIAGRAMME DANS UNE SLIDE : pour un schéma — architecture, flux, séquence —, n'utilise PAS "
            + "d'image IA (icônes inventées). Fais du diagramme-as-code : écris-le en Mermaid, rends-le en "
            + "PNG dans le sandbox (mmdc/mermaid-cli), puis insère-le dans la slide (add_picture) — recette "
            + "détaillée dans le skill pptx. Rendu dans le sandbox, jamais sur le cluster ; échec NOMMÉ si "
            + "le moteur de rendu manque ; FACTUEL (ne dessine que l'établi, marque le supposé).\n"
            + "ICÔNES CLOUD OFFICIELLES : pour une architecture cloud soignée avec les VRAIS glyphes de "
            + "service (S3, Lambda, RDS, VNet…), utilise la lib Python diagrams (nœuds diagrams.aws/azure/"
            + "gcp/onprem, rendus par graphviz) → PNG → add_picture — recette dans le skill pptx. C'est le "
            + "HAUT DE GAMME, dans le sandbox (exige python+graphviz) ; si indisponible, REPLI NOMMÉ sur "
            + "Mermaid architecture-beta (icônes génériques, zéro installation).\n"
            + "IMAGE DÉCORATIVE : pour un ORNEMENT (couverture du deck, visuel d'ambiance — jamais un "
            + "schéma), tu peux générer une image avec generate_image ; elle est déposée dans le projet et "
            + "s'insère en add_picture. JAMAIS pour une architecture ni un diagramme technique (icônes "
            + "inventées) : garde le diagramme-as-code (Mermaid / diagrams) ci-dessus.\n"
            + "ACCORD : quand une présentation serait utile, PROPOSE-la en une phrase et attends, sauf si "
            + "l'utilisateur l'a demandée. Pour REMPLACER une présentation déjà capturée, rappelle son "
            + "presentation_id (nouveau fichier, même entrée).";

    private final SpaceEntitlementService entitlements;

    @Autowired
    public PresentationToolCatalog(SpaceEntitlementService entitlements) {
        this.entitlements = entitlements;
    }

    /** Catalogue <b>vide</b> : l'outil n'est jamais donné (formes historiques, tests). */
    public static PresentationToolCatalog none() {
        return new PresentationToolCatalog(null);
    }

    /** Vrai si ce nom d'outil est celui des présentations. */
    public static boolean isPresentationTool(String tool) {
        return PUBLISH.equals(tool);
    }

    /** L'espace où se range une présentation publiée depuis ce terminal. */
    public static PresentationSpace spaceOf(Workspace workspace) {
        return workspace.isTeamsTerminal() ? PresentationSpace.VIGIE : PresentationSpace.FORGE;
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
        EntitlementSpace space = spaceOf(workspace) == PresentationSpace.VIGIE
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
        return new AgentTool(PUBLISH,
                "Capture une PRÉSENTATION .pptx produite sur le terminal (l'utilisateur la voit et la "
                        + "télécharge dans l'application). Donne un title court et le path du fichier .pptx "
                        + "sur la machine. presentation_id remplace une présentation déjà capturée.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "title", Map.of("type", "string",
                                        "description", "Titre court et distinctif (120 caractères au plus)."),
                                "description", Map.of("type", "string",
                                        "description", "Une phrase qui dit ce que montre la présentation (300 au plus)."),
                                "path", Map.of("type", "string",
                                        "description", "Chemin du fichier .pptx sur la machine."),
                                "presentation_id", Map.of("type", "string",
                                        "description", "Identifiant d'une présentation déjà capturée, pour la remplacer."),
                                "slides", Map.of("type", "array",
                                        "description", "Facultatif : chemins des images PNG du rendu, une par slide "
                                                + "et DANS L'ORDRE (rendues dans le sandbox : LibreOffice → PDF → "
                                                + "pdftoppm -png). Permet l'aperçu in-app slide par slide.",
                                        "items", Map.of("type", "string"))),
                        "required", List.of("title", "path")));
    }
}
