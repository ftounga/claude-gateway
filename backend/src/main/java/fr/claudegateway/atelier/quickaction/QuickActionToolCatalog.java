package fr.claudegateway.atelier.quickaction;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;

/**
 * <b>L'agent range les actions rapides</b> (F-275 / SF-275-02, D3) — et la garde qui décide où l'outil est donné.
 *
 * <p>Déclaré dans tout terminal de son propriétaire, <b>sauf</b> le terminal Teams et le fil Jarvis
 * ({@link QuickActionService#offeredIn}). La définition est un <b>littéral stable</b> (cache F-134) : aucune donnée
 * volatile n'y entre.</p>
 */
@Component
public class QuickActionToolCatalog {

    /** Nom de l'outil. */
    public static final String NAME = "action_rapide";

    private static final List<String> DAYS = List.of("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi",
            "dimanche", "jours_ouvres");

    private static final AgentTool TOOL = new AgentTool(NAME,
            "Range les ACTIONS RAPIDES de l'utilisateur : les workflows qu'il lance d'un clic depuis le bouton "
                    + "« Actions rapides » de la saisie, dans le bon terminal, sans se souvenir de la phrase. Une action "
                    + "= un libellé, la phrase de démarrage envoyée, un sujet cible facultatif (sujet du poste de ce "
                    + "terminal ; vide = le terminal où l'on clique), une portée (sujet = ce terminal, poste = tous les "
                    + "terminaux de ce poste, compte = partout), le geste du clic (deposer = la phrase va dans la "
                    + "saisie, envoyer = elle part directement) et une suggestion facultative (jours + moment), jamais "
                    + "exécutée seule. Opérations : ajouter, lister, modifier, supprimer (désigner l'action par son "
                    + "libellé ou son id dans « action »). Les skills qui déclarent leurs déclencheurs (« Déclenché "
                    + "par « … » ») y figurent déjà : ne les recopie pas. À utiliser quand l'utilisateur demande un "
                    + "bouton, un raccourci ou une action rapide.",
            Map.of("type", "object",
                    "properties", Map.of(
                            "operation", Map.of("type", "string",
                                    "enum", List.of("ajouter", "lister", "modifier", "supprimer")),
                            "action", Map.of("type", "string",
                                    "description", "Pour modifier / supprimer : le libellé ou l'id de l'action."),
                            "libelle", Map.of("type", "string", "description", "Libellé du menu (60 caractères au plus)."),
                            "phrase", Map.of("type", "string", "description", "La phrase de démarrage envoyée."),
                            "sujet", Map.of("type", "string",
                                    "description", "Nom ou id du sujet du poste où lancer ; \"\" = le terminal où l'on clique."),
                            "portee", Map.of("type", "string", "enum", List.of("sujet", "poste", "compte")),
                            "clic", Map.of("type", "string", "enum", List.of("deposer", "envoyer")),
                            "suggestion_jours", Map.of("type", "array", "items", Map.of("type", "string", "enum", DAYS)),
                            "suggestion_moment", Map.of("type", "string",
                                    "enum", List.of("matin", "apres_midi", "journee")),
                            "sans_suggestion", Map.of("type", "boolean",
                                    "description", "Pour modifier : retire la suggestion.")),
                    "required", List.of("operation")));

    /** Vrai si ce nom est l'outil de ce catalogue. */
    public static boolean isQuickActionTool(String name) {
        return NAME.equals(name);
    }

    /** Vrai si l'outil est ouvert dans ce terminal : terminal de son propriétaire, ni Teams ni fil Jarvis. */
    public boolean isOpenFor(UUID userId, Workspace workspace) {
        return userId != null && workspace != null && userId.equals(workspace.getUserId())
                && QuickActionService.offeredIn(workspace);
    }

    /** Les outils à déclarer dans ce terminal (vide ailleurs). */
    public List<AgentTool> toolsFor(UUID userId, Workspace workspace) {
        return isOpenFor(userId, workspace) ? List.of(TOOL) : List.of();
    }
}
