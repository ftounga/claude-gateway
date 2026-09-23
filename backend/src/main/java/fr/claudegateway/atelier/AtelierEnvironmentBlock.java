package fr.claudegateway.atelier;

import java.time.LocalDate;

/**
 * Le bloc « Environnement » de la consigne système (F-121 / SF-121-21), à la manière du {@code <env>}
 * de Claude Code : date du jour, répertoire de travail, plateforme/OS, shell et instantané git.
 *
 * <p><b>Mise en forme pure</b> — aucune dépendance, aucune E/S. Le service résout les valeurs
 * (propriétés de poste, colonnes du workspace, instantané git figé du cache) puis appelle l'une des
 * deux fabriques ci-dessous. Cette séparation rend le rendu testable à l'octet, ce qui est
 * exactement la garantie qui compte : le préfixe doit être <b>stable entre tours</b> pour ne pas
 * casser le cache de prompt (F-134).</p>
 *
 * <p><b>Stabilité.</b> La seule donnée recalculée à chaque tour est la date, à la granularité du
 * <b>jour</b> : elle ne change pas d'un tour à l'autre. Tout le reste est stable (propriétés de la
 * machine / du projet) ou figé (instantané git write-once, capturé une fois par
 * {@link fr.claudegateway.atelier.promptsource.PromptSourceStore}).</p>
 */
final class AtelierEnvironmentBlock {

    static final String HEADER = "--- Environnement ---\n";

    private AtelierEnvironmentBlock() {
    }

    /**
     * Bloc pour la cible SANDBOX (espace de travail hébergé). Le dépôt git est déduit des colonnes du
     * workspace ; pas de statut git (le stockage objet n'est pas une copie git vivante).
     */
    static String sandbox(LocalDate today, String cwd, boolean isGit, String gitBranch) {
        StringBuilder block = new StringBuilder(HEADER);
        block.append("Date du jour : ").append(today).append('\n');
        block.append("Répertoire de travail : ").append(cwdLabel(cwd, "(racine du projet)")).append('\n');
        block.append("Plateforme : espace de travail hébergé\n");
        if (isGit) {
            block.append("Dépôt git : oui");
            String branch = trimToNull(gitBranch);
            if (branch != null) {
                block.append(" (branche : ").append(branch).append(')');
            }
            block.append('\n');
        } else {
            block.append("Dépôt git : non\n");
        }
        return block.append('\n').toString();
    }

    /**
     * Bloc pour la cible RUNNER (machine de l'utilisateur). L'OS et le shell viennent de ce que le
     * runner a déclaré à l'appairage / au {@code ready} ; l'instantané git est capturé une fois et
     * embarqué verbatim. Un OS absent voit sa ligne omise ; un instantané pas encore capturé
     * ({@code null} ou vide) voit son sous-bloc omis.
     */
    static String runner(LocalDate today, String cwd, String os, String shell, String gitSnapshot) {
        StringBuilder block = new StringBuilder(HEADER);
        block.append("Date du jour : ").append(today).append('\n');
        block.append("Répertoire de travail : ").append(cwdLabel(cwd, "(racine du poste)")).append('\n');
        block.append("Plateforme : poste de l'utilisateur");
        String system = trimToNull(os);
        if (system != null) {
            block.append(" (").append(system).append(')');
        }
        block.append('\n');
        String shellLabel = trimToNull(shell);
        if (shellLabel != null) {
            block.append("Shell : ").append(shellLabel).append('\n');
        }
        String git = trimToNull(gitSnapshot);
        if (git != null) {
            block.append(git).append('\n');
        }
        return block.append('\n').toString();
    }

    private static String cwdLabel(String cwd, String fallback) {
        String value = trimToNull(cwd);
        return value == null ? fallback : value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
