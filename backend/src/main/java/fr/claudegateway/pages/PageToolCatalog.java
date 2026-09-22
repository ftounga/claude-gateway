package fr.claudegateway.pages;

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
 * <b>L'outil {@code page_publish} donné à un agent — et la garde qui décide s'il l'est</b>
 * (F-109 / SF-109-02, cadrage §4).
 *
 * <h2>La garde</h2>
 *
 * <p>Même doctrine que les catalogues Teams et Radar : la garde est au niveau de l'outil. Il est donné à
 * un utilisateur qui a le <b>droit de l'espace du terminal</b> — la Vigie pour un terminal Teams, la Forge
 * pour tout autre — <b>quelle que soit la cible d'exécution</b> : un poste (la boucle maison) comme un
 * projet hébergé (bac à sable Managed Agents, F-109 / SF-109-06). {@link SpaceEntitlementService} ouvre ce
 * droit d'office au rôle {@code ADMIN}. Sans le droit : aucun outil, aucun guide — l'agent n'a pas la
 * capacité. L'exécuteur lit ensuite ce qu'il faut <b>là où vit le projet</b> (poste ou stockage).</p>
 *
 * <h2>Le guide de conception</h2>
 *
 * <p>{@link #DESIGN_GUIDE} rejoint la consigne <b>sous la même garde</b>. Il reprend l'esprit de ce que
 * Claude Code applique à ses pages : identité, typographie, thèmes clair et sombre, téléphone, contenu
 * réel, titre court — et ce que la politique de §3 interdit, pour que le modèle n'écrive pas une page qui
 * échouerait en silence.</p>
 */
@Component
public class PageToolCatalog {

    /** Le nom de l'outil. */
    public static final String PUBLISH = "page_publish";

    /** Le guide de conception ajouté à la consigne quand l'outil est donné. */
    public static final String DESIGN_GUIDE = "--- Pages (documents graphiques) ---\n"
            + "Tu peux publier une PAGE : un document HTML que l'utilisateur voit dans l'application, range et "
            + "peut partager. Une page vaut mieux qu'un long message pour une maquette d'écran, une comparaison "
            + "d'options, un schéma d'architecture ou de flux, un compte rendu à transmettre, le tableau de bord "
            + "d'un sujet. Jamais pour une réponse courte.\n"
            + "ACCORD : quand une page serait plus claire, PROPOSE-la en une phrase (« Voulez-vous que j'en fasse "
            + "une page ? ») et attends. Publie directement seulement si l'utilisateur l'a demandé. La publication "
            + "lui demande de toute façon son accord d'un clic.\n"
            + "CONCEPTION, comme une page soignée et non comme un brouillon :\n"
            + "- Identité : la charte du client ou du projet si elle existe (charte, DESIGN_SYSTEM.md, couleurs et "
            + "logo connus dans le projet) ; sinon celle de l'application — navy #0B1020, orange #E07B39 pour "
            + "l'action, fond #F5F6FA, surfaces blanches, titres Space Grotesk, texte Inter, code JetBrains Mono "
            + "(Google Fonts).\n"
            + "- Typographie : une hiérarchie nette (un titre, des intertitres, du corps de texte), deux familles "
            + "au plus, interlignage aéré, lignes de 60 à 80 caractères, chiffres alignés dans les tableaux.\n"
            + "- Thèmes clair ET sombre : toutes les couleurs en variables CSS sur :root, redéfinies sous "
            + "@media (prefers-color-scheme: dark) ; fond et texte toujours posés explicitement ; contrastes "
            + "lisibles dans les deux.\n"
            + "- Téléphone : lisible à 400 px — grilles qui passent en une colonne, marges latérales d'au moins "
            + "16 px, images en max-width:100 %, aucun défilement horizontal hors tableaux et schémas (dans leur "
            + "propre conteneur défilant).\n"
            + "- Contenu réel : uniquement ce que tu sais du projet, du client ou de la conversation — jamais de "
            + "texte de remplissage, de chiffres ou de noms inventés. Ce qui manque se dit (« à compléter »).\n"
            + "- Titre court et distinctif : deux à quatre mots qui nomment la page (« Maquette de la Forge », "
            + "« Radar MFA — septembre »), pas une phrase ni une catégorie ; description d'une phrase.\n"
            + "- Structure : un document autonome (<!doctype html>, <meta charset=\"utf-8\">, <meta "
            + "name=\"viewport\">, <title>), CSS et JS dans la page.\n"
            + "CE QU'UNE PAGE PEUT FAIRE : scripts depuis https://cdnjs.cloudflare.com et https://cdn.jsdelivr.net "
            + "seulement (versions exactes), polices depuis Google Fonts, images en data: ou en pièce jointe — une "
            + "capture ou un logo de la machine se joint (png, jpg, jpeg, gif, webp) et se référence par son nom "
            + "(<img src=\"capture.png\">). "
            + "AUCUN appel réseau (fetch, XHR, WebSocket), aucun formulaire envoyé, aucun cookie ni stockage, "
            + "aucune autre origine : tout cela est BLOQUÉ. Embarque les données dans la page. 8 Mo au plus.\n"
            + "DIAGRAMMES (Mermaid) : pour un schéma — architecture, flux, séquence —, écris un bloc "
            + "<pre class=\"mermaid\"> … code Mermaid … </pre>. L'application le REND automatiquement en "
            + "diagramme : n'ajoute PAS toi-même la bibliothèque mermaid ni de script de rendu. Le code reste "
            + "visible et éditable (republie avec le page_id pour le modifier). Types : flowchart (flux), "
            + "sequenceDiagram (séquence), et architecture-beta (architecture cloud/on-prem : group, service, "
            + "edge). FACTUEL : ne dessine que ce qui est ÉTABLI — jamais un composant ni un lien inventé ; ce "
            + "qui est supposé se marque « (supposé) ». Un bloc invalide n'empêche pas la page : son code "
            + "s'affiche avec un message.\n"
            + "ICÔNES CLOUD OFFICIELLES : dans la page elle-même (rendu navigateur, hors ligne), "
            + "architecture-beta rend la structure avec des icônes génériques. Pour un livrable soigné avec "
            + "les VRAIS glyphes de service (AWS/Azure/GCP/on-prem — S3, Lambda, RDS…), rends un PNG avec la "
            + "lib Python diagrams DANS LE SANDBOX (recette skill pptx : diagrams+graphviz), puis JOINS-le à "
            + "la page (pièce jointe, <img src=\"archi.png\">). diagrams = le haut de gamme (icônes "
            + "officielles) ; architecture-beta = le repli zéro-installation.\n"
            + "Jamais de transcription brute de réunion dans une page : des extraits courts, sourcés.\n"
            + "Pour modifier une page déjà publiée, republie avec son page_id : c'est une nouvelle version, pas "
            + "une nouvelle page.";

    private final SpaceEntitlementService entitlements;

    @Autowired
    public PageToolCatalog(SpaceEntitlementService entitlements) {
        this.entitlements = entitlements;
    }

    /** Catalogue <b>vide</b> : l'outil n'est jamais donné (formes historiques, tests). */
    public static PageToolCatalog none() {
        return new PageToolCatalog(null);
    }

    /** Vrai si ce nom d'outil est celui des pages. */
    public static boolean isPageTool(String tool) {
        return PUBLISH.equals(tool);
    }

    /** L'espace où se range une page publiée depuis ce terminal. */
    public static PageSpace spaceOf(Workspace workspace) {
        return workspace.isTeamsTerminal() ? PageSpace.VIGIE : PageSpace.FORGE;
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
        EntitlementSpace space = spaceOf(workspace) == PageSpace.VIGIE ? EntitlementSpace.VIGIE : EntitlementSpace.FORGE;
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
        Map<String, Object> string = Map.of("type", "string");
        return new AgentTool(PUBLISH,
                "Publie une PAGE HTML que l'utilisateur voit dans l'application (privée, à lui seul). Donne "
                        + "EXACTEMENT UN de html (le document complet) ou path (un fichier .html de la machine). "
                        + "page_id republie une page existante en nouvelle version. attachments : fichiers de la "
                        + "machine servis avec la page, référencés par leur nom relatif — texte (css, js, mjs, json, "
                        + "svg, csv, txt, md) ET images (png, jpg, jpeg, gif, webp, ex. une capture : "
                        + "<img src=\"capture.png\">) ; ou embarque les images en data:. L'utilisateur confirme "
                        + "d'un clic.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "title", Map.of("type", "string",
                                        "description", "Titre court et distinctif, deux à quatre mots (120 caractères au plus)."),
                                "description", Map.of("type", "string",
                                        "description", "Une phrase qui dit ce que montre la page (300 caractères au plus)."),
                                "html", Map.of("type", "string",
                                        "description", "Le document HTML complet et autonome."),
                                "path", Map.of("type", "string",
                                        "description", "Chemin d'un fichier .html sur la machine, à la place de html."),
                                "page_id", Map.of("type", "string",
                                        "description", "Identifiant d'une page déjà publiée, pour en créer une nouvelle version."),
                                "attachments", Map.of("type", "array",
                                        "items", Map.of("type", "object",
                                                "properties", Map.of("name", string, "path", string),
                                                "required", List.of("name", "path")))),
                        "required", List.of("title")));
    }
}
