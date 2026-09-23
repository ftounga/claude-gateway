package fr.claudegateway.diagrams;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;

/**
 * <b>L'outil de rendu de diagrammes</b> (F-142 / SF-142-06) : le schéma devient une image
 * <b>sans que le poste installe quoi que ce soit</b>.
 *
 * <p><b>Ce que la description doit absolument dire</b> — et qui a été appris à nos dépens : que le
 * rendu <b>ne coûte rien</b>. L'agent connaît {@code generate_image}, qui est payant et borné ; sans
 * précision, il traiterait celui-ci avec la même parcimonie et éviterait de dessiner.</p>
 */
@Component
public class DiagramToolCatalog {

    /** Le nom de l'outil. */
    public static final String RENDER = "render_diagram";

    /** Le guide de doctrine ajouté à la consigne quand l'outil est donné. */
    public static final String GUIDE = "--- Diagrammes rendus par la gateway ---\n"
            + "Pour tout SCHÉMA (architecture, flux, séquence, réseau), écris du Mermaid et appelle "
            + RENDER + " : la GATEWAY le rend en image et la dépose dans le projet, puis te rend son "
            + "chemin. N'installe RIEN sur la machine du client — ni mermaid-cli, ni chromium, ni npm : "
            + "sur un poste d'entreprise ces installations échouent, et le livrable partait sans ses "
            + "diagrammes.\n"
            + "OÙ L'UTILISER : en slide (add_picture du PNG), en page (<img src=\"...\">, ou laisse le "
            + "bloc <pre class=\"mermaid\"> que la page rend elle-même), en document (.docx).\n"
            + "GRATUIT : ce rendu n'appelle aucun fournisseur et ne consomme aucun jeton — contrairement "
            + "à generate_image. Dessine dès qu'un schéma aide à comprendre.\n"
            + "DEUX MOTEURS : par défaut du Mermaid (« code »). Pour une ARCHITECTURE CLOUD avec les "
            + "ICÔNES OFFICIELLES (AWS, Azure, GCP, on-prem), passe engine=cloud et donne « spec » : "
            + "{title, direction (LR/TB), groups:[{id,label}], nodes:[{id,type,label,group}], "
            + "edges:[{from,to,label}]}. Les types sont de la forme « aws.rds », « aws.ecs », "
            + "« aws.alb », « aws.s3 », « aws.lambda », « azure.aks », « gcp.gke », "
            + "« onprem.postgresql », « onprem.kafka », « onprem.users »… Plus de MILLE icônes sont "
            + "atteignables : donne le NOM EXACT du composant (« aws.natgateway », "
            + "« aws.transitgateway », « aws.privatesubnet »), il sera trouvé.\n"
            + "N'UTILISE JAMAIS UNE ICÔNE APPROCHANTE. Si un composant n'a pas d'icône, garde son nom : "
            + "le service pose une BOÎTE NEUTRE avec son libellé et te le signale — dis-le alors à "
            + "l'utilisateur. Mettre « ce qui ressemble » (un poste client pour un réseau d'entreprise, "
            + "par exemple) met un composant FAUX dans un livrable client.\n"
            + "UN GROUPE N'EST PAS UN NŒUD : un réseau, un VPC, une zone se décrivent dans « groups », "
            + "pas par une icône.\n"
            + "Tu écris une DESCRIPTION, jamais du code : rien de ce que tu donnes n'est exécuté.\n"
            + "FACTUEL : ne dessine que ce qui est établi ; ce qui est supposé se marque « (supposé) ». "
            + "Si le rendu échoue, DIS-LE avec la raison rendue — ne fabrique jamais une image, et "
            + "n'invente pas un composant pour faire joli.";

    /** Vrai si ce nom d'outil est celui du rendu de diagrammes. */
    public static boolean isDiagramTool(String tool) {
        return RENDER.equals(tool);
    }

    private final DiagramRenderer renderer;

    public DiagramToolCatalog(DiagramRenderer renderer) {
        this.renderer = renderer;
    }

    /** Catalogue <b>vide</b> : l'outil n'est jamais donné (formes historiques, tests). */
    public static DiagramToolCatalog none() {
        return new DiagramToolCatalog(null);
    }

    /**
     * Vrai si l'outil est ouvert pour ce tour : il suffit qu'un moteur soit configuré.
     *
     * <p>Aucun droit particulier n'est exigé — rendre un schéma ne coûte rien et ne sort du poste que
     * le <b>code</b> du diagramme, qui vient de l'agent lui-même.</p>
     */
    public boolean isOpenFor(UUID userId, Workspace workspace) {
        return renderer != null && userId != null && workspace != null && renderer.isAvailable();
    }

    /** L'outil à donner à l'agent pour ce tour, ou <b>la liste vide</b>. */
    public List<AgentTool> toolsFor(UUID userId, Workspace workspace) {
        return isOpenFor(userId, workspace) ? List.of(definition()) : List.of();
    }

    /** La définition de l'outil (schéma d'entrée). */
    static AgentTool definition() {
        return new AgentTool(RENDER,
                "REND UN DIAGRAMME en image, côté gateway, et le dépose dans le projet ; te rend son "
                        + "chemin pour l'insérer en slide (add_picture), en page (<img>) ou en document. "
                        + "Donne « code » en Mermaid (flowchart, sequenceDiagram, architecture-beta…), "
                        + "ou engine=cloud + « spec » pour les ICÔNES OFFICIELLES AWS/Azure/GCP/on-prem. "
                        + "N'installe rien sur la machine du client : le rendu n'a PAS lieu là-bas. "
                        + "GRATUIT (aucun jeton, aucun fournisseur). Si le diagramme est invalide, la "
                        + "réponse dit pourquoi : corrige le code, ne fabrique pas d'image.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "code", Map.of("type", "string",
                                        "description", "Le diagramme en Mermaid (moteur par défaut)."),
                                "engine", Map.of("type", "string",
                                        "description", "mermaid (défaut) ou cloud (icônes officielles "
                                                + "AWS/Azure/GCP/on-prem, à partir de « spec »).",
                                        "enum", List.of("mermaid", "cloud")),
                                "spec", Map.of("type", "object",
                                        "description", "Pour engine=cloud : la DESCRIPTION du schéma — "
                                                + "title, direction, groups[{id,label}], "
                                                + "nodes[{id,type,label,group}], edges[{from,to,label}]. "
                                                + "Des données, jamais du code."),
                                "format", Map.of("type", "string",
                                        "description", "png (défaut, pour une slide ou un document) ou "
                                                + "svg (net à tout zoom, pour une page).",
                                        "enum", List.of("png", "svg")),
                                "width", Map.of("type", "integer",
                                        "description", "Largeur de rendu en pixels (1600 par défaut)."),
                                "filename", Map.of("type", "string",
                                        "description", "Nom de fichier facultatif pour le dépôt dans le "
                                                + "projet ; dérivé sinon.")),
                        "required", List.of("code")));
    }
}
