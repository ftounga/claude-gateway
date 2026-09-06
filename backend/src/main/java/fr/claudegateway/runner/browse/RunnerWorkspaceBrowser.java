package fr.claudegateway.runner.browse;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * Lecture des fichiers d'un projet <b>sur la machine de l'utilisateur</b>, pour l'écran
 * (F-38 / SF-38-17).
 *
 * <p>En cible {@code RUNNER}, l'explorateur lisait le stockage objet — <b>vide par construction</b>,
 * puisque les fichiers vivent sur la machine. Il affichait donc un projet inexistant. Ce service
 * relaie les deux lectures au runner, par les mêmes méthodes que l'agent utilise déjà : rien n'est
 * copié, rien n'est mis en cache, et il n'existe toujours qu'<b>une seule</b> source de vérité — le
 * disque de l'utilisateur (décision D1).</p>
 *
 * <p>Les exclusions {@code .runnerignore} (SF-38-10) s'appliquent telles quelles : la garde est
 * celle du runner, pas une seconde règle posée ici. Et chaque lecture est <b>auditée</b>, sous un
 * nom d'outil distinct de celui de l'agent : le journal doit pouvoir répondre à « qu'est-ce qui a
 * été lu sur ma machine, et par qui » (D3).</p>
 */
@Service
public class RunnerWorkspaceBrowser {

    /** Noms d'outil réservés aux lectures déclenchées par l'écran, distincts de ceux de l'agent. */
    static final String SCREEN_LIST = "screen_list_files";
    static final String SCREEN_READ = "screen_read_file";

    /**
     * Marqueur d'arborescence incomplète (F-38 / SF-38-21), rendu comme un chemin pour n'exiger
     * aucun changement de contrat : l'écran l'affiche là où il affiche les fichiers, en tête de
     * liste. Mieux vaut une ligne qui dérange qu'un projet amputé en silence.
     */
    public static final String TRUNCATED_MARKER =
            "⚠ liste incomplète — trop de fichiers ; ajoutez un .runnerignore et relancez le runner";

    private final RunnerToolGateway gateway;
    private final RunnerAuditService auditService;

    public RunnerWorkspaceBrowser(RunnerToolGateway gateway, RunnerAuditService auditService) {
        this.gateway = gateway;
        this.auditService = auditService;
    }

    /**
     * Arborescence du projet, telle qu'elle est sur la machine.
     *
     * @throws RunnerBrowseException si aucune machine n'est joignable, ou si le runner refuse
     */
    public List<String> tree(Workspace workspace) {
        String callId = UUID.randomUUID().toString();
        RunnerCallResult result = gateway.listFiles(workspace.getId(), callId);
        auditService.recordCall(workspace.getUserId(), workspace.getId(), callId, SCREEN_LIST, null,
                result);
        requireOk(result);
        String content = result.content() == null ? "" : result.content();
        if (content.isBlank()) {
            // Un dossier vide est un état normal — c'est même le point de départ d'un projet neuf.
            return List.of();
        }
        List<String> paths = new java.util.ArrayList<>(List.of(content.split("\n")));
        if (result.truncated()) {
            // La troncature se DIT (F-38 / SF-38-21). Afficher un projet incomplet en silence a
            // coûté dix minutes de recherche d'un dossier que le système savait ne pas avoir
            // envoyé — c'est exactement le mode d'échec que tout ce chantier cherche à supprimer.
            paths.add(TRUNCATED_MARKER);
        }
        return List.copyOf(paths);
    }

    /**
     * Arborescence, ou <b>liste vide</b> si la machine n'est pas joignable.
     *
     * <p>Réservé au <b>détail du projet</b>, qui porte aussi son nom, sa source et sa cible : une
     * machine éteinte ne doit pas rendre le projet inaccessible. L'écran sonde déjà l'état du runner
     * (SF-38-06) et dit « hors ligne » lui-même, sans que le backend ait à refuser la page.</p>
     *
     * <p>La lecture d'un <b>fichier</b>, elle, échoue explicitement : l'utilisateur a demandé un
     * contenu précis, et une chaîne vide serait un mensonge.</p>
     */
    public List<String> treeOrEmpty(Workspace workspace) {
        try {
            return tree(workspace);
        } catch (RunnerBrowseException ex) {
            return List.of();
        }
    }

    /**
     * Contenu d'un fichier, lu sur la machine.
     *
     * @throws RunnerBrowseException si la machine est absente, le chemin exclu ou la lecture refusée
     */
    public String readFile(Workspace workspace, String path) {
        String callId = UUID.randomUUID().toString();
        RunnerCallResult result = gateway.readFile(workspace.getId(), callId, path);
        auditService.recordCall(workspace.getUserId(), workspace.getId(), callId, SCREEN_READ, path,
                result);
        requireOk(result);
        return result.truncated()
                ? result.content() + "\n… (contenu tronqué)"
                : result.content();
    }

    /**
     * Traduit un refus du runner en erreur d'écran. La distinction qui compte pour l'utilisateur est
     * entre « aucune machine connectée » — un état, réparable en lançant le runner — et « le runner
     * a refusé », qui appelle une autre action.
     */
    private static void requireOk(RunnerCallResult result) {
        if (result.ok()) {
            return;
        }
        boolean offline = RunnerErrorCodes.RUNNER_UNAVAILABLE.equals(result.errorCode())
                || RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE.equals(result.errorCode())
                || RunnerErrorCodes.RUNNER_TIMEOUT.equals(result.errorCode());
        if (offline) {
            // Jamais une arborescence vide : elle laisserait croire à un projet vide, alors que le
            // projet est simplement hors de portée.
            throw new RunnerBrowseException(
                    "Projet hors ligne : lancez le runner pour parcourir les fichiers.");
        }
        throw new RunnerBrowseException(result.errorMessage() == null
                ? "Lecture refusée par la machine."
                : result.errorMessage());
    }
}
