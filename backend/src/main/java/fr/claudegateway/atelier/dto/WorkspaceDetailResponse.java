package fr.claudegateway.atelier.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import fr.claudegateway.atelier.ProjectInstructions;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceSource;

/**
 * Vue détaillée d'un workspace : métadonnées + arborescence (chemins relatifs). N'expose aucune clé
 * de stockage brute.
 *
 * <p>Depuis F-31 (SF-31-02), la vue porte aussi la <b>source</b> du projet et, pour un projet Git,
 * le dépôt et la branche montés. Aucun secret : le jeton d'accès n'est jamais renvoyé — seule l'URL
 * publique du dépôt l'est.</p>
 *
 * @param source     {@code ARCHIVE} ou {@code GIT}
 * @param gitRepoUrl URL publique du dépôt, {@code null} pour un projet d'archive
 * @param gitRepo    {@code owner/repo}, {@code null} pour un projet d'archive
 * @param gitBranch  branche montée, {@code null} pour un projet d'archive
 * @param truncated  vrai si l'arborescence est <b>partielle</b> (SF-31-03) : le dire évite de faire
 *                   croire qu'un fichier absent de la liste n'existe pas
 * @param askBeforeBash vrai si le projet demande l'autorisation avant d'exécuter une commande
 *                   (F-33 / SF-33-01). Champ <b>additif</b> : absent de la vue d'un client antérieur,
 *                   il vaut {@code false} — le comportement historique
 * @param instructionsPath chemin du fichier d'instructions du projet (F-34 / SF-34-01), qui sera
 *                   ajouté au prompt de l'agent à la <b>prochaine ouverture de session</b>, ou
 *                   {@code null} si le projet n'en porte pas. Dérivé de l'arborescence déjà chargée :
 *                   l'annoncer à l'écran ne coûte ni lecture de stockage ni appel à GitHub
 */
public record WorkspaceDetailResponse(
        UUID id, String name, int fileCount, List<String> files, OffsetDateTime createdAt,
        WorkspaceSource source, String gitRepoUrl, String gitRepo, String gitBranch, boolean truncated,
        String instructionsPath, boolean askBeforeBash) {

    public static WorkspaceDetailResponse from(Workspace workspace, List<String> files) {
        return from(workspace, files, false);
    }

    public static WorkspaceDetailResponse from(Workspace workspace, List<String> files, boolean truncated) {
        return new WorkspaceDetailResponse(
                workspace.getId(), workspace.getName(), files.size(), files, workspace.getCreatedAt(),
                workspace.sourceOrDefault(), workspace.getGitRepoUrl(), fullName(workspace),
                workspace.getGitBranch(), truncated, ProjectInstructions.detectPath(files).orElse(null),
                workspace.isAgentAskBeforeBash());
    }

    /** {@code owner/repo} lisible, ou {@code null} si le workspace n'est pas adossé à un dépôt. */
    private static String fullName(Workspace workspace) {
        if (workspace.getGitOwner() == null || workspace.getGitRepo() == null) {
            return null;
        }
        return workspace.getGitOwner() + "/" + workspace.getGitRepo();
    }
}
