package fr.claudegateway.atelier.git.dto;

/**
 * Réponse de {@code POST /api/workspaces/{id}/git/commit} (F-31 / SF-31-08).
 *
 * @param branch        branche sur laquelle le commit a été publié
 * @param commitSha     identifiant du commit créé
 * @param branchCreated {@code true} si la branche vient d'être créée
 * @param compareUrl    lien de comparaison sur GitHub — repli constant de F-31
 * @param pullRequestUrl pull request déjà ouverte sur cette branche, ou {@code null}
 */
public record GitCommitResponse(String branch, String commitSha, boolean branchCreated,
        String compareUrl, String pullRequestUrl) {
}
