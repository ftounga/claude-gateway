package fr.claudegateway.atelier;

import java.util.UUID;

/**
 * <b>Le worktree d'un chantier parallèle</b> (F-221 / SF-221-06) — règles pures : son identifiant, son
 * chemin (relatif à la racine du poste, et depuis le dossier du projet), sa branche et les deux commandes git
 * qui le créent.
 *
 * <p>Le dossier vit sous la racine du poste, dans {@code .atelier-chantiers/} — <b>hors</b> de
 * {@code .atelier-worktrees/}, que le runner nettoie au démarrage (F-150) : le travail d'un chantier ne doit
 * jamais disparaître parce que le poste a redémarré. Les commandes sont simples et portables (POSIX comme
 * PowerShell) : aucune opération runner nouvelle, les postes déjà installés suffisent.</p>
 */
public final class LaneWorktree {

    /** Dossier, sous la racine du poste, des worktrees de chantiers. */
    public static final String DIR = ".atelier-chantiers";
    /** Préfixe des branches de chantiers. */
    public static final String BRANCH_PREFIX = "atelier/chantier-";

    private LaneWorktree() {
    }

    /** {@code <8 premiers caractères du sujet>-ch<N>} : unique par sujet et chantier, sûr pour un chemin. */
    public static String id(UUID workspaceId, int chantier) {
        String subject = workspaceId.toString().replace("-", "");
        return subject.substring(0, 8) + "-ch" + chantier;
    }

    /** Chemin du worktree relatif à la racine du poste — la cible runner du tour isolé. */
    public static String hostPath(String id) {
        return DIR + "/" + id;
    }

    /** Branche du worktree. */
    public static String branch(String id) {
        return BRANCH_PREFIX + id;
    }

    /**
     * Le chemin du worktree vu <b>depuis le dossier du projet</b> (où s'exécutent les commandes) : autant de
     * {@code ../} que le projet a de niveaux sous la racine du poste.
     */
    public static String fromProject(String projectPath, String id) {
        StringBuilder up = new StringBuilder();
        if (projectPath != null) {
            for (String segment : projectPath.replace('\\', '/').split("/")) {
                if (!segment.isBlank() && !".".equals(segment)) {
                    up.append("../");
                }
            }
        }
        return up + hostPath(id);
    }

    /** « Est-ce un dépôt git ? » — {@code true} sur la sortie si oui. */
    public static String probeCommand() {
        return "git rev-parse --is-inside-work-tree";
    }

    /** Crée le worktree sur une branche neuve, partie du dernier commit. */
    public static String addCommand(String projectPath, String id) {
        return "git worktree add -b " + branch(id) + " " + fromProject(projectPath, id) + " HEAD";
    }

    /** Recrée le worktree sur une branche qui existe déjà (un chantier rouvert, un worktree démonté). */
    public static String addExistingBranchCommand(String projectPath, String id) {
        return "git worktree add " + fromProject(projectPath, id) + " " + branch(id);
    }
}
