package fr.claudegateway.governance;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.exec.RunnerTargets;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * Lit et écrit les fichiers d'un projet <b>là où ils vivent réellement</b> (F-51 / SF-51-03) : par le
 * runner en cible {@code RUNNER}, par le stockage sinon.
 *
 * <p>C'est le même geste que la boucle fait déjà pour bâtir sa consigne système
 * ({@code AtelierChatService.safeTree} / {@code readOptional}), isolé ici pour deux raisons : le
 * dépôt d'un paquet doit pouvoir être vérifié <b>sans fournisseur ni tour de conversation</b>, et
 * l'erreur qu'on veut absolument éviter — écrire en stockage un projet qui vit sur une machine — se
 * corrige à un seul endroit.</p>
 *
 * <p><b>Une arborescence illisible n'est jamais rendue comme une arborescence vide.</b> La
 * différence est tout le sujet : un projet vide accepte tous les dépôts, un projet <i>illisible</i>
 * n'autorise à conclure sur rien. {@link #listPaths} rend donc un {@link Optional} vide quand la
 * lecture a échoué, et l'appelant est obligé de traiter le cas.</p>
 *
 * <p><b>Isolation.</b> Ce service reçoit un {@link Workspace} <b>déjà vérifié comme possédé</b>,
 * jamais un identifiant brut : il n'a pas à rejouer un contrôle d'appartenance qu'il ne saurait pas
 * faire mieux que l'appelant.</p>
 */
@Service
public class GovernanceProjectFiles {

    private static final Logger log = LoggerFactory.getLogger(GovernanceProjectFiles.class);

    private final WorkspaceService workspaceService;
    private final RunnerToolGateway runnerToolGateway;

    public GovernanceProjectFiles(WorkspaceService workspaceService,
            RunnerToolGateway runnerToolGateway) {
        this.workspaceService = workspaceService;
        this.runnerToolGateway = runnerToolGateway;
    }

    /**
     * Les chemins présents dans le projet.
     *
     * @return les chemins, ou {@link Optional#empty()} si le projet <b>n'a pas pu être lu</b> —
     *         machine éteinte, refus du runner, stockage indisponible
     */
    public Optional<Set<String>> listPaths(UUID userId, Workspace workspace) {
        try {
            if (workspace.isRunnerTarget()) {
                RunnerCallResult result = runnerToolGateway.listFiles(RunnerTargets.of(workspace),
                        UUID.randomUUID().toString());
                if (!result.ok()) {
                    return Optional.empty();
                }
                String content = result.content() == null ? "" : result.content();
                // Un dossier vide est un état normal, et se distingue d'une lecture ratée : ici, la
                // machine a bien répondu.
                return Optional.of(content.isBlank() ? Set.of() : Set.of(content.split("\n")));
            }
            return Optional.of(Set.copyOf(workspaceService.tree(userId, workspace.getId())));
        } catch (RuntimeException ex) {
            log.debug("Arborescence illisible pour la gouvernance ({})", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Écrit un fichier dans le projet.
     *
     * <p><b>N'écrase jamais rien de son propre chef</b> : la décision « ce fichier manque » est prise
     * par l'appelant à partir de {@link #listPaths}. Ce service ne fait qu'exécuter, sur la bonne
     * cible.</p>
     *
     * @return vrai si l'écriture a abouti ; faux si elle a été refusée ou n'a pas pu aboutir
     */
    public boolean write(UUID userId, Workspace workspace, String path, String content) {
        try {
            if (workspace.isRunnerTarget()) {
                RunnerCallResult result = runnerToolGateway.writeFile(RunnerTargets.of(workspace),
                        UUID.randomUUID().toString(), path, content);
                return result.ok();
            }
            workspaceService.writeFile(userId, workspace.getId(), path, content);
            return true;
        } catch (RuntimeException ex) {
            // Un dépôt qui échoue laisse l'activation en attente ; il ne casse ni le tour ni la
            // création d'un projet. Le contenu n'est jamais journalisé : c'est le fichier de
            // l'utilisateur.
            log.debug("Dépôt de gouvernance refusé ({})", ex.getClass().getSimpleName());
            return false;
        }
    }

    /** Les chemins présents, ou une liste vide si le projet est illisible. Confort de lecture. */
    public List<String> listPathsOrEmpty(UUID userId, Workspace workspace) {
        return listPaths(userId, workspace).map(List::copyOf).orElseGet(List::of);
    }
}
