package fr.claudegateway.atelier.git;

import java.util.regex.Pattern;

import fr.claudegateway.git.InvalidGitBranchException;
import fr.claudegateway.git.InvalidGitRepositoryException;

/**
 * Référence d'un dépôt GitHub extraite d'une URL fournie par l'utilisateur (F-31 / SF-31-02).
 *
 * <p>Le parsing est volontairement <b>strict et hors-ligne</b> : une URL malformée est refusée sans
 * qu'aucun appel réseau ni aucune écriture n'ait lieu. Seul {@code https://github.com/owner/repo}
 * (suffixe {@code .git} et barre finale tolérés) est accepté — l'API Managed Agents ne monte que
 * GitHub, et accepter une URL d'une autre forge produirait un échec tardif, au premier clone.</p>
 *
 * @param owner propriétaire du dépôt (organisation ou utilisateur)
 * @param repo  nom du dépôt
 */
public record GitRepositoryRef(String owner, String repo) {

    /** Longueur maximale acceptée pour l'URL brute (colonne {@code git_repo_url}). */
    public static final int MAX_URL_LENGTH = 500;

    private static final Pattern URL_PATTERN = Pattern.compile(
            "^https://(?:www\\.)?github\\.com/([A-Za-z0-9._-]{1,100})/([A-Za-z0-9._-]{1,100}?)(?:\\.git)?/?$");

    private static final Pattern BRANCH_PATTERN = Pattern.compile("^[A-Za-z0-9._/-]{1,255}$");

    /**
     * Extrait la référence d'un dépôt d'une URL GitHub.
     *
     * @param url URL saisie par l'utilisateur
     * @return la référence {@code owner/repo}
     * @throws InvalidGitRepositoryException si l'URL est vide, trop longue, ou n'est pas une URL de
     *                                       dépôt GitHub en {@code https}
     */
    public static GitRepositoryRef parse(String url) {
        String candidate = url == null ? "" : url.trim();
        if (candidate.isEmpty() || candidate.length() > MAX_URL_LENGTH) {
            throw new InvalidGitRepositoryException(
                    "Indiquez l'URL d'un dépôt GitHub (https://github.com/proprietaire/depot).");
        }
        var matcher = URL_PATTERN.matcher(candidate);
        if (!matcher.matches()) {
            throw new InvalidGitRepositoryException(
                    "Seuls les dépôts GitHub sont pris en charge : https://github.com/proprietaire/depot.");
        }
        return new GitRepositoryRef(matcher.group(1), matcher.group(2));
    }

    /**
     * Valide une référence de branche saisie par l'utilisateur.
     *
     * <p>Refuse {@code ..} et les préfixes {@code -} / {@code /} : ce sont les formes qui, injectées
     * dans une ligne de commande git, se comportent comme une option ou remontent l'arborescence.</p>
     *
     * @param branch branche à valider
     * @return la branche élaguée
     * @throws InvalidGitBranchException si la branche est vide ou de forme invalide
     */
    public static String requireValidBranch(String branch) {
        String candidate = branch == null ? "" : branch.trim();
        if (!BRANCH_PATTERN.matcher(candidate).matches()
                || candidate.contains("..")
                || candidate.startsWith("-")
                || candidate.startsWith("/")
                || candidate.endsWith("/")) {
            throw new InvalidGitBranchException("Nom de branche invalide.");
        }
        return candidate;
    }

    /** URL canonique du dépôt, telle que transmise au fournisseur pour le montage. */
    public String cloneUrl() {
        return "https://github.com/" + owner + "/" + repo;
    }

    /** {@code owner/repo}, forme attendue par l'API GitHub et lisible dans l'interface. */
    public String fullName() {
        return owner + "/" + repo;
    }
}
