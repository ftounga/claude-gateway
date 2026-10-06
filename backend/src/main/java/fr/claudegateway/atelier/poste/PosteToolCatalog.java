package fr.claudegateway.atelier.poste;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;

/**
 * <b>Les lectures du terminal du poste</b> (F-178) — et la garde qui décide si elles sont données.
 *
 * <p>Ces outils ne sont déclarés <b>qu'au terminal du poste</b> ({@link Workspace#isHostTerminal()}) d'un
 * poste réel : c'est là, et là seulement, qu'on se demande « où en est chaque sujet ? ». Dans un sujet, ils
 * n'existent pas ; l'exécuteur ({@link PosteToolExecutor}) refuse un appel malgré tout.</p>
 *
 * <p>Les définitions sont des <b>littéraux stables</b> (cache F-134) : aucune donnée volatile n'y entre ;
 * la condition d'offre est stable pour un workspace donné. <b>Aucune écriture</b>.</p>
 */
@Component
public class PosteToolCatalog {

    /** L'état de chaque sujet du poste (F-178 / SF-178-02). */
    public static final String SUBJECTS_STATE = "sujets_etat";

    /** Tous les noms d'outils de ce catalogue — la boucle aiguille par ce test avant tout autre volet. */
    public static final Set<String> NAMES = Set.of(SUBJECTS_STATE);

    private static final AgentTool SUBJECTS_STATE_TOOL = new AgentTool(SUBJECTS_STATE,
            "Rend, en une lecture, l'état de CHAQUE sujet de ce poste : dernière activité, parcours (Libre ou "
                    + "Guidé, phase, chantier), attentes ouvertes, dernier bilan de session, activité de la "
                    + "semaine. Utilise-le pour « où en est chaque sujet ? », « qu'est-ce qui attend ? », "
                    + "« sur quoi ai-je travaillé ? » — plutôt que d'ouvrir les fichiers de chaque sujet. "
                    + "Lecture seule.",
            Map.of("type", "object", "properties", Map.of(), "required", List.of()));

    /** Vrai si ce nom est un outil de ce catalogue. */
    public static boolean isPosteTool(String name) {
        return name != null && NAMES.contains(name);
    }

    /** Vrai si les lectures du poste sont ouvertes dans ce terminal : terminal du poste d'un poste réel. */
    public boolean isOpenFor(UUID userId, Workspace workspace) {
        return workspace != null && workspace.isHostTerminal() && workspace.getHostId() != null
                && userId != null && userId.equals(workspace.getUserId());
    }

    /** Les outils à déclarer dans ce terminal (liste vide hors du terminal du poste). */
    public List<AgentTool> toolsFor(UUID userId, Workspace workspace) {
        return isOpenFor(userId, workspace) ? List.of(SUBJECTS_STATE_TOOL) : List.of();
    }
}
