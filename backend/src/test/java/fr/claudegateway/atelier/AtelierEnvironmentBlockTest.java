package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le bloc « Environnement » de la consigne (F-121 / SF-121-21), mis en forme à la manière du
 * {@code <env>} de Claude Code.
 *
 * <p>La garantie qui compte : le rendu est <b>stable à l'octet</b> à entrée constante — c'est ce qui
 * préserve le cache de préfixe (F-134). La date est à la granularité du <b>jour</b>, jamais un
 * horodatage à la seconde.</p>
 */
class AtelierEnvironmentBlockTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 23);

    @Test
    @DisplayName("SANDBOX : dépôt git déduit du workspace, pas de statut git")
    void sandboxGitFromWorkspace() {
        String block = AtelierEnvironmentBlock.sandbox(DAY, "src/app", true, "main");

        assertThat(block)
                .startsWith("--- Environnement ---\n")
                .contains("Date du jour : 2026-09-23")
                .contains("Répertoire de travail : src/app")
                .contains("Plateforme : espace de travail hébergé")
                .contains("Dépôt git : oui (branche : main)")
                .doesNotContain("État git");
    }

    @Test
    @DisplayName("SANDBOX : projet archive → dépôt git non ; répertoire vide → racine du projet")
    void sandboxArchive() {
        String block = AtelierEnvironmentBlock.sandbox(DAY, "  ", false, null);

        assertThat(block)
                .contains("Répertoire de travail : (racine du projet)")
                .contains("Dépôt git : non")
                .doesNotContain("branche");
    }

    @Test
    @DisplayName("RUNNER : OS et shell déclarés présents, instantané git embarqué verbatim")
    void runnerWithOsShellAndGit() {
        String git = "Dépôt git : oui (branche : dev)\n"
                + "État git (instantané au démarrage, non rafraîchi en cours de session) :\n"
                + " M src/App.java";
        String block = AtelierEnvironmentBlock.runner(DAY, "poste/projet", "Linux 5.15", "posix", git);

        assertThat(block)
                .startsWith("--- Environnement ---\n")
                .contains("Date du jour : 2026-09-23")
                .contains("Répertoire de travail : poste/projet")
                .contains("Plateforme : poste de l'utilisateur (Linux 5.15)")
                .contains("Shell : posix")
                .contains("Dépôt git : oui (branche : dev)")
                .contains(" M src/App.java");
    }

    @Test
    @DisplayName("RUNNER : OS absent → ligne omise ; instantané git pas encore capturé → sous-bloc omis")
    void runnerWithoutOsNorGit() {
        String block = AtelierEnvironmentBlock.runner(DAY, "poste", null, "cmd", null);

        assertThat(block)
                .contains("Plateforme : poste de l'utilisateur\n")
                .doesNotContain("(")
                .contains("Shell : cmd")
                .doesNotContain("Dépôt git");
    }

    @Test
    @DisplayName("STABILITÉ : deux rendus à entrée constante sont identiques à l'octet (cache F-134)")
    void twoRendersAreByteIdentical() {
        String a = AtelierEnvironmentBlock.runner(DAY, "poste/projet", "Linux", "posix", "Dépôt git : non");
        String b = AtelierEnvironmentBlock.runner(DAY, "poste/projet", "Linux", "posix", "Dépôt git : non");

        assertThat(a).isEqualTo(b);
    }
}
