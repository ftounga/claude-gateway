package fr.claudegateway.teams;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;

/**
 * <b>Le catalogue d'outils Teams donné à l'agent — et la garde qui décide s'il l'est</b>
 * (F-89 / SF-89-01, cadrage §5.4).
 *
 * <h2>La garde est au niveau de l'outil, pas de l'écran</h2>
 *
 * <p>{@code buildTools(workspace)} décide déjà quels outils l'agent reçoit : c'est là que
 * {@code bash} est donné ou non selon la cible d'exécution. Les outils {@code teams_*} suivent
 * exactement le même chemin, sous <b>deux</b> conditions cumulatives : le workspace est un
 * <b>terminal Teams</b>, et l'utilisateur a le <b>droit Teams</b>.</p>
 *
 * <p><b>Sans l'une ou l'autre, les outils ne sont pas donnés — et c'est tout.</b> La nuance est le
 * motif même de cette conception : <i>l'agent ne refuse pas, il n'a pas la capacité</i>. Il ne dira
 * jamais « je pourrais mais vous n'avez pas payé » ; interrogé sur Teams, il dira qu'il ne sait pas
 * le lire. On ne met pas l'utilisateur devant une porte fermée à chaque phrase.</p>
 *
 * <h2>Un seul endroit</h2>
 *
 * <p>Le catalogue et sa garde vivent <b>ici</b>, et nulle part ailleurs. F-88 y ajoute ses outils de
 * lecture ({@code teams_read_conversation}, {@code teams_mentions}, {@code teams_search}…), F-89 /
 * SF-89-02 ses outils de présentation : aucun des deux n'a à réécrire la garde, et aucun ne peut
 * l'oublier. Un catalogue éparpillé serait un catalogue dont une entrée échapperait un jour au
 * droit.</p>
 *
 * <h2>Ce que le catalogue contient aujourd'hui</h2>
 *
 * <p>{@code teams_status} — l'outil que le runner sait exécuter depuis F-87 / SF-87-03, et que
 * l'agent n'avait jamais reçu : l'indicateur de la barre le consommait, l'agent non. Il est le bon
 * premier outil du catalogue parce qu'il est celui par lequel l'agent apprend qu'il <b>peut</b>
 * lire Teams, et, quand il ne le peut pas, <b>quoi dire</b> à l'utilisateur (lancer le navigateur
 * avec son port de débogage) : le résultat porte la phrase toute faite.</p>
 */
@Component
public class TeamsToolCatalog {

    /** L'état de la liaison — le seul outil que le runner sait exécuter depuis F-87 / SF-87-03. */
    public static final String STATUS = "teams_status";

    /**
     * Préfixe commun à tous les outils du volet. Sert à une seule chose, mais elle compte : un test
     * peut vérifier qu'<b>aucun</b> outil commençant par {@code teams_} n'est donné là où le droit
     * n'est pas ouvert, sans avoir à énumérer un catalogue qui grandira.
     */
    public static final String PREFIX = "teams_";

    private final TeamsAccessService teamsAccess;

    public TeamsToolCatalog(TeamsAccessService teamsAccess) {
        this.teamsAccess = teamsAccess;
    }

    /**
     * Catalogue <b>vide</b>, sur le modèle d'{@code AtelierCheckpointRunner.none()} : aucun outil
     * Teams n'est jamais donné. C'est ce que reçoivent les formes d'{@code AtelierChatService}
     * conservées pour les appelants et les tests qui ne se soucient pas du volet Teams — leur
     * comportement est alors celui d'avant F-89, à l'identique.
     */
    public static TeamsToolCatalog none() {
        return new TeamsToolCatalog(null);
    }

    /**
     * Les outils Teams à donner à l'agent pour ce tour, ou <b>la liste vide</b>.
     *
     * @param userId    propriétaire du terminal (isolation : celui du tour, jamais un paramètre client)
     * @param workspace terminal du tour
     * @return le catalogue si le workspace est un terminal Teams <b>et</b> que le droit est ouvert ;
     *         la liste vide dans tous les autres cas
     */
    public List<AgentTool> toolsFor(UUID userId, Workspace workspace) {
        if (teamsAccess == null || workspace == null || !workspace.isTeamsTerminal()
                || !teamsAccess.hasAccess(userId)) {
            return List.of();
        }
        List<AgentTool> tools = new ArrayList<>();
        tools.add(new AgentTool(STATUS,
                "Dit où en est la liaison Teams de cette machine : reliée, navigateur non détecté, "
                        + "ou Teams a changé de forme. Appelle-le AVANT toute lecture de Teams — et "
                        + "quand il dit que la liaison n'est pas établie, répète à l'utilisateur la "
                        + "phrase et le remède qu'il te rend, mot pour mot : ils contiennent la "
                        + "commande exacte à lancer.",
                Map.of("type", "object", "properties", Map.of())));
        return List.copyOf(tools);
    }
}
