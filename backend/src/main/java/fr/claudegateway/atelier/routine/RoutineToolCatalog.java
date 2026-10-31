package fr.claudegateway.atelier.routine;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;

/**
 * <b>L'agent propose une routine</b> (F-183 / SF-183-01, D1) — et la garde qui décide où l'outil est
 * donné.
 *
 * <p>Déclaré dans le terminal d'un <b>sujet sur poste</b> seulement ({@link RoutineService#isRoutineCapable}),
 * jamais au terminal du poste (« routines au poste » hors périmètre). L'outil <b>ne programme rien</b> :
 * il range une proposition inactive que l'utilisateur valide d'une carte [Activer] / [Refuser].</p>
 *
 * <p>La définition est un <b>littéral stable</b> (cache F-134) : aucune donnée volatile n'y entre.</p>
 */
@Component
public class RoutineToolCatalog {

    /** Nom de l'outil. */
    public static final String PROPOSE = "routine_proposer";

    private static final AgentTool PROPOSE_TOOL = new AgentTool(PROPOSE,
            "Propose à l'utilisateur une ROUTINE pour ce sujet : un travail récurrent exécuté seul, sans "
                    + "écran ouvert, dans ce terminal (ex. « chaque jour ouvré à 8 h, fais le point sur les "
                    + "tickets et mets à jour les attentes »). À utiliser quand l'utilisateur demande de "
                    + "programmer, répéter ou automatiser un travail à heure fixe. La routine N'EST PAS "
                    + "activée : l'utilisateur la valide d'une carte. Rythme : quotidien, jours_ouvres ou "
                    + "hebdo (avec le jour). Ne propose pas de rythme libre.",
            Map.of("type", "object",
                    "properties", Map.of(
                            "nom", Map.of("type", "string", "description", "Nom court (80 caractères au plus)."),
                            "consigne", Map.of("type", "string",
                                    "description", "La demande exacte que la routine enverra à chaque exécution."),
                            "rythme", Map.of("type", "string", "enum", List.of("quotidien", "jours_ouvres", "hebdo")),
                            "jour", Map.of("type", "string",
                                    "enum", List.of("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi",
                                            "dimanche"),
                                    "description", "Jour de la semaine, pour le rythme hebdo seulement."),
                            "heure", Map.of("type", "string", "description", "Heure HH:mm, heure du poste."),
                            "budget_eur", Map.of("type", "number",
                                    "description", "Budget en euros par exécution (0,10 à 50 ; 4,60 par défaut).")),
                    "required", List.of("nom", "consigne", "rythme", "heure")));

    /** Vrai si ce nom est l'outil de ce catalogue. */
    public static boolean isRoutineTool(String name) {
        return PROPOSE.equals(name);
    }

    /** Vrai si l'outil est ouvert dans ce terminal : sujet sur poste, terminal de son propriétaire. */
    public boolean isOpenFor(UUID userId, Workspace workspace) {
        return userId != null && workspace != null && userId.equals(workspace.getUserId())
                && RoutineService.isRoutineCapable(workspace);
    }

    /** Les outils à déclarer dans ce terminal (vide ailleurs). */
    public List<AgentTool> toolsFor(UUID userId, Workspace workspace) {
        return isOpenFor(userId, workspace) ? List.of(PROPOSE_TOOL) : List.of();
    }
}
