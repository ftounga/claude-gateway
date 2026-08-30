package fr.claudegateway.git;

/**
 * Résultat d'un commit poussé sur une branche par l'utilisateur lui-même (F-31 / SF-31-08).
 *
 * @param branch     branche sur laquelle le commit a été créé (jamais la branche par défaut)
 * @param commitSha  identifiant du commit créé
 * @param branchCreated {@code true} si la branche a été créée par cet appel, {@code false} si elle existait
 */
public record GitCommitResult(String branch, String commitSha, boolean branchCreated) {
}
