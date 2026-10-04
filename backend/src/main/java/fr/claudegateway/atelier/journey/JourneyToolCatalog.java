package fr.claudegateway.atelier.journey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.SpaceEntitlementService;

/**
 * <b>Les outils du parcours du sujet donnés à l'agent — et la garde qui décide s'ils le sont</b> (F-176).
 *
 * <p>Même doctrine que les attentes (F-154/F-175) : la garde est au niveau de l'outil, donné à un
 * utilisateur qui a le <b>droit de l'espace du terminal</b> (Vigie pour un terminal Teams, Forge
 * sinon). Sans le droit : aucun outil, aucun guide — et donc rien dans la consigne (cache F-134).</p>
 *
 * <p>Les définitions et le guide sont des <b>littéraux stables</b> : rien de volatil n'y entre.</p>
 */
@Component
public class JourneyToolCatalog {

    /** Proposer le mode guidé (SF-176-02). */
    public static final String PROPOSE_GUIDED = "propose_guided_mode";

    /** Tous les noms d'outils du parcours. */
    public static final Set<String> NAMES = Set.of(PROPOSE_GUIDED);

    /** Le guide ajouté à la consigne système quand les outils sont donnés (stable). */
    public static final String GUIDE = "--- Parcours du sujet (Libre / Guidé) ---\n"
            + "Chaque terminal traite un SUJET, en mode LIBRE (par défaut) ou GUIDÉ. Le bloc « Parcours "
            + "du sujet », joint au message quand il y a quelque chose à dire, te donne le mode et la "
            + "phase.\n"
            + "QUALIFIER : au premier message d'un sujet en Libre, qualifie la demande — une QUESTION "
            + "(répondre suffit), un PETIT GESTE (une modification courte et sans risque), ou un "
            + "CHANTIER (incident, changement d'infra, plusieurs étapes ou plusieurs jours, inconnues à "
            + "lever avant d'agir). Si c'est un chantier, appelle propose_guided_mode avec la raison en "
            + "une phrase : l'utilisateur voit [Passer en guidé] [Rester libre] et choisit. Fais-le aussi "
            + "plus tard si une demande simple devient un chantier.\n"
            + "NE PROPOSE PAS pour une question ou un petit geste, ni deux fois : s'il a choisi de rester "
            + "libre, c'est réglé pour ce sujet.\n"
            + "EN ATTENDANT SON CHOIX : continue à comprendre (lecture libre), mais ne modifie rien de "
            + "plus que ce qu'il a explicitement demandé.";

    private final SpaceEntitlementService entitlements;

    @Autowired
    public JourneyToolCatalog(SpaceEntitlementService entitlements) {
        this.entitlements = entitlements;
    }

    /** Catalogue <b>vide</b> : les outils ne sont jamais donnés (formes historiques, tests). */
    public static JourneyToolCatalog none() {
        return new JourneyToolCatalog(null);
    }

    /** Vrai si ce nom d'outil est l'un de ceux du parcours. */
    public static boolean isJourneyTool(String tool) {
        return tool != null && NAMES.contains(tool);
    }

    /** Vrai si les outils sont ouverts pour ce tour (droit de l'espace du terminal). */
    public boolean isOpenFor(UUID userId, Workspace workspace) {
        if (entitlements == null || userId == null || workspace == null) {
            return false;
        }
        EntitlementSpace space = workspace.isTeamsTerminal()
                ? EntitlementSpace.VIGIE : EntitlementSpace.FORGE;
        try {
            return entitlements.isEntitled(userId, space);
        } catch (RuntimeException e) {
            return false; // abonnement illisible : fermé, comme toute garde
        }
    }

    /** Les outils à donner à l'agent pour ce tour, ou la liste vide. */
    public List<AgentTool> toolsFor(UUID userId, Workspace workspace) {
        if (!isOpenFor(userId, workspace)) {
            return List.of();
        }
        List<AgentTool> tools = new ArrayList<>();
        tools.add(proposeGuidedDefinition());
        return tools;
    }

    static AgentTool proposeGuidedDefinition() {
        return new AgentTool(PROPOSE_GUIDED,
                "Propose à l'utilisateur de passer ce sujet en mode GUIDÉ (investigation, plan validé, "
                        + "exécution, vérification) quand la demande est un CHANTIER : incident, changement "
                        + "d'infra, plusieurs étapes ou inconnues à lever avant d'agir. Il voit [Passer en "
                        + "guidé] [Rester libre] et choisit. Jamais pour une question ou un petit geste.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "reason", Map.of("type", "string",
                                        "description", "Pourquoi c'est un chantier, en une phrase lisible par "
                                                + "l'utilisateur (300 caractères au plus).")),
                        "required", List.of("reason")));
    }
}
