package fr.claudegateway.atelier.git;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.git.GitHubClient;
import fr.claudegateway.git.GitProperties;
import fr.claudegateway.git.GitTreeListing;
import fr.claudegateway.git.GitHubRepository;
import fr.claudegateway.git.GitTokenMissingException;
import fr.claudegateway.git.GitTokenService;

/**
 * Ouverture d'un projet d'Atelier sur un dépôt GitHub (F-31 / SF-31-02, ADR-015).
 *
 * <p>L'ordre des vérifications est délibéré et se lit de haut en bas :</p>
 * <ol>
 *   <li><b>URL</b> — validée hors-ligne : une URL malformée est refusée sans appel réseau ;</li>
 *   <li><b>jeton</b> — sans jeton enregistré, rien n'est tenté (aucun appel, aucune écriture) ;</li>
 *   <li><b>accès au dépôt</b> — confirmé auprès de GitHub, ce qui donne aussi la branche par défaut ;</li>
 *   <li><b>création</b> — le workspace n'est écrit qu'une fois les trois précédents verts.</li>
 * </ol>
 *
 * <p>Un workspace créé sur un dépôt inaccessible ne se manifesterait qu'au premier message, loin du
 * geste qui l'a causé : c'est exactement l'erreur que cet ordre supprime.</p>
 *
 * <p><b>Isolation multi-tenant</b> : le {@code userId} vient du {@code SecurityContext} ; le jeton
 * résolu est celui de cet utilisateur, jamais un autre. Le jeton n'est ni journalisé, ni renvoyé.</p>
 */
@Service
public class GitWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(GitWorkspaceService.class);

    private final WorkspaceService workspaceService;
    private final GitTokenService gitTokenService;
    private final GitHubClient gitHubClient;
    private final GitProperties properties;

    public GitWorkspaceService(WorkspaceService workspaceService, GitTokenService gitTokenService,
            GitHubClient gitHubClient, GitProperties properties) {
        this.workspaceService = workspaceService;
        this.gitTokenService = gitTokenService;
        this.gitHubClient = gitHubClient;
        this.properties = properties;
    }

    /**
     * Crée un workspace de source {@code GIT}.
     *
     * @param userId  propriétaire (isolation multi-tenant)
     * @param repoUrl URL du dépôt saisie par l'utilisateur
     * @param branch  branche demandée, ou {@code null} pour la branche par défaut du dépôt
     * @param name    nom du projet, ou {@code null} pour le nom du dépôt
     * @return le workspace créé
     * @throws fr.claudegateway.git.InvalidGitRepositoryException URL invalide, ou dépôt introuvable /
     *                                                            hors de portée du jeton
     * @throws GitTokenMissingException                           aucun jeton GitHub enregistré
     * @throws fr.claudegateway.git.InvalidGitTokenException      jeton refusé par GitHub
     * @throws fr.claudegateway.git.GitHubUnavailableException    GitHub injoignable
     */
    public Workspace create(UUID userId, String repoUrl, String branch, String name) {
        GitRepositoryRef ref = GitRepositoryRef.parse(repoUrl);
        String requestedBranch = branch == null || branch.isBlank()
                ? null
                : GitRepositoryRef.requireValidBranch(branch);

        String token = tokenOf(userId);

        GitHubRepository repository = gitHubClient.getRepository(token, ref.owner(), ref.repo());
        String mountedBranch = requestedBranch == null ? repository.defaultBranch() : requestedBranch;

        Workspace workspace = workspaceService.createFromGit(userId, name, ref.cloneUrl(), ref.owner(),
                ref.repo(), mountedBranch);
        log.info("Workspace Git ouvert pour l'utilisateur {} sur {} ({})", userId, ref.fullName(),
                mountedBranch);
        return workspace;
    }

    /**
     * Contenu d'un workspace, quelle que soit sa source (F-31 / SF-31-03).
     *
     * <p>Pour un projet d'<b>archive</b>, c'est le stockage objet, exactement comme avant. Pour un
     * projet <b>Git</b>, c'est l'<b>union</b> de deux sources, et aucune ne suffit seule :</p>
     * <ul>
     *   <li>la <b>branche</b> (API GitHub) donne l'état du dépôt — sans elle, l'explorateur d'un
     *       projet Git afficherait un projet vide, laissant croire que le clone a échoué ;</li>
     *   <li>le <b>stockage objet</b> contient les fichiers que la session a réécrits — sans lui, le
     *       travail de l'agent n'apparaîtrait nulle part avant le push.</li>
     * </ul>
     *
     * @param userId    propriétaire (isolation)
     * @param workspace workspace <b>déjà vérifié comme possédé</b> par l'appelant
     * @return les chemins triés et dédoublonnés, et l'indication de troncage
     */
    public WorkspaceContent tree(UUID userId, Workspace workspace) {
        List<String> local = workspaceService.tree(userId, workspace.getId());
        if (!workspace.isGit()) {
            return new WorkspaceContent(local, false);
        }
        GitTreeListing listing = gitHubClient.listTree(tokenOf(userId), workspace.getGitOwner(),
                workspace.getGitRepo(), branchOf(workspace), properties.maxTreeEntries());

        Set<String> merged = new TreeSet<>(listing.paths());
        merged.addAll(local);
        return new WorkspaceContent(List.copyOf(merged), listing.truncated());
    }

    /**
     * Contenu texte d'un fichier. Sur un projet Git, la version <b>locale</b> prime : c'est celle que
     * la session a produite, et c'est la seule qui montre le travail en cours. À défaut, le fichier
     * est lu sur la branche.
     *
     * @param userId    propriétaire (isolation)
     * @param workspace workspace <b>déjà vérifié comme possédé</b> par l'appelant
     * @param path      chemin relatif du fichier
     * @return le contenu texte
     */
    public String readFile(UUID userId, Workspace workspace, String path) {
        if (!workspace.isGit()) {
            return workspaceService.readFile(userId, workspace.getId(), path);
        }
        try {
            return workspaceService.readFile(userId, workspace.getId(), path);
        } catch (WorkspaceNotFoundException notLocal) {
            // Absent du stockage : le fichier n'a pas encore été touché par la session, il vit sur
            // la branche. Ce n'est pas une erreur, c'est le cas courant.
            return gitHubClient.readFile(tokenOf(userId), workspace.getGitOwner(), workspace.getGitRepo(),
                    branchOf(workspace), path, properties.maxFileBytes());
        }
    }

    /**
     * Refuse toute écriture sur un projet Git (SF-31-03) : écrire dans le stockage pendant que l'agent
     * travaille sur le clone créerait deux vérités divergentes, silencieusement.
     *
     * @param workspace workspace concerné
     * @throws GitWorkspaceReadOnlyException si le workspace est adossé à un dépôt
     */
    public void requireWritable(Workspace workspace) {
        if (workspace.isGit()) {
            throw new GitWorkspaceReadOnlyException(
                    "Ce projet est adossé à un dépôt Git : les fichiers se modifient via Claude, "
                            + "puis se publient sur une branche.");
        }
    }

    /**
     * Refuse le mode « Assistant » sur un projet Git (SF-31-03) : il lit le stockage objet, vide sur
     * ce type de projet — Claude répondrait sur un projet inexistant.
     *
     * @param workspace workspace concerné
     * @throws GitWorkspaceModeException si le workspace est adossé à un dépôt
     */
    public void requireArchiveChatMode(Workspace workspace) {
        if (workspace.isGit()) {
            throw new GitWorkspaceModeException(
                    "Ce projet est adossé à un dépôt Git : utilisez le mode Terminal, "
                            + "où le dépôt est réellement disponible.");
        }
    }

    /** Jeton du propriétaire du workspace, déchiffré à la volée. Jamais journalisé, jamais renvoyé. */
    private String tokenOf(UUID userId) {
        return gitTokenService.resolveToken(userId)
                .orElseThrow(() -> new GitTokenMissingException(
                        "Aucun jeton GitHub enregistré : ajoutez-en un dans vos réglages pour ouvrir ce projet."));
    }

    /**
     * Branche montée, revalidée avant de partir dans une URL. La valeur a été validée à la création ;
     * la revérifier ici garde la garantie locale au point où elle compte.
     */
    private static String branchOf(Workspace workspace) {
        return GitRepositoryRef.requireValidBranch(workspace.getGitBranch());
    }

    /**
     * Contenu listé d'un workspace : les chemins, et l'indication que la liste est <b>partielle</b>.
     * Le drapeau existe parce qu'un explorateur silencieusement tronqué ferait croire qu'un fichier
     * n'existe pas.
     *
     * @param files     chemins relatifs, triés et dédoublonnés
     * @param truncated vrai si la liste ne couvre pas tout le dépôt
     */
    public record WorkspaceContent(List<String> files, boolean truncated) {
    }
}
