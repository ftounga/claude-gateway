package fr.claudegateway.atelier.actions;

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
 * <b>L'outil {@code record_blocker} donné à un agent — et la garde qui décide s'il l'est</b>
 * (F-151 / SF-151-02).
 *
 * <p>Même doctrine que les autres outils de la gateway : la garde est au niveau de l'outil, donné à
 * un utilisateur qui a le <b>droit de l'espace du terminal</b> (Vigie pour un terminal Teams, Forge
 * sinon) ; {@link SpaceEntitlementService} l'ouvre d'office à l'ADMIN. Sans le droit : aucun outil,
 * aucun guide — et donc rien dans la consigne (cache de prompt, F-134).</p>
 */
@Component
public class TerminalActionToolCatalog {

    /** Le nom de l'outil. */
    public static final String RECORD = "record_blocker";

    /** Le guide ajouté à la consigne quand l'outil est donné. */
    public static final String GUIDE = "--- Actions à faire par l'utilisateur ---\n"
            + "Quand tu butes sur quelque chose que TOI tu ne peux pas faire — il faut contacter "
            + "quelqu'un, obtenir un accès, une validation, une information qui n'est nulle part —, "
            + "appelle record_blocker. L'action apparaît dans le menu du terminal de l'utilisateur et "
            + "SURVIT au tour ; écrite seulement dans ta réponse, elle serait perdue.\n"
            + "QUAND : uniquement pour une dépendance HUMAINE qui bloque. Pas une liste de choses à "
            + "faire, pas tes propres étapes, pas ce que tu peux trouver ou essayer toi-même. Une "
            + "action mal inscrite coûte plus cher qu'une action manquante : elle fait douter de la "
            + "liste entière.\n"
            + "COMMENT : description à l'IMPÉRATIF et du point de vue de l'utilisateur (« Demander "
            + "l'accès VPN à Karim »), blocks = ce que ça débloque (« le déploiement du connecteur »), "
            + "person = qui est concerné, kind = MESSAGE si c'est un message à envoyer, ACTION sinon, "
            + "et key = une clé courte et STABLE du blocage (« acces-vpn-karim ») — c'est elle qui "
            + "évite dix fois la même ligne.\n"
            + "CE QUE LE RÉSULTAT TE DIT : « inscrite » (c'est neuf), « déjà inscrite » (elle attend "
            + "déjà — n'en reparle pas), « annulée par l'utilisateur » (il a dit non : NE REDEMANDE "
            + "PAS, contourne ou explique ce qui restera impossible), « déjà faite ».\n"
            + "TU CONTINUES TON TOUR : inscrire une action n'interrompt rien et n'attend rien. Fais "
            + "ensuite tout ce qui ne dépend pas de ce blocage, puis dis clairement ce qui reste "
            + "suspendu à lui.";

    private final SpaceEntitlementService entitlements;

    @Autowired
    public TerminalActionToolCatalog(SpaceEntitlementService entitlements) {
        this.entitlements = entitlements;
    }

    /** Catalogue <b>vide</b> : l'outil n'est jamais donné (formes historiques, tests). */
    public static TerminalActionToolCatalog none() {
        return new TerminalActionToolCatalog(null);
    }

    /** Vrai si ce nom d'outil est celui des actions du terminal. */
    public static boolean isTerminalActionTool(String tool) {
        return RECORD.equals(tool);
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
        EntitlementSpace space = workspace.isTeamsTerminal()
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
        return new AgentTool(RECORD,
                "Inscrit une ACTION QUE L'UTILISATEUR DOIT FAIRE pour débloquer le travail : "
                        + "contacter quelqu'un, obtenir un accès, une validation. Elle apparaît dans le "
                        + "menu de son terminal et survit au tour. Uniquement pour une dépendance "
                        + "humaine qui bloque — jamais pour tes propres étapes.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "description", Map.of("type", "string",
                                        "description", "Ce qu'il faut faire, à l'impératif et du point de vue de "
                                                + "l'utilisateur (300 caractères au plus)."),
                                "blocks", Map.of("type", "string",
                                        "description", "Ce que ça débloque — la raison d'être de l'action "
                                                + "(200 au plus)."),
                                "person", Map.of("type", "string",
                                        "description", "Qui est concerné, tel que le fil le nomme (120 au plus)."),
                                "kind", Map.of("type", "string",
                                        "description", "MESSAGE si c'est un message à envoyer, ACTION sinon.",
                                        "enum", List.of("ACTION", "MESSAGE")),
                                "key", Map.of("type", "string",
                                        "description", "Clé courte et STABLE du blocage (« acces-vpn-karim ») : "
                                                + "elle évite d'inscrire dix fois la même action.")),
                        "required", List.of("description")));
    }
}
