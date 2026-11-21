package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.autonomy.AutonomyRunStatus;

/** F-270 / SF-270-01 — le nom court d'un travail et la confirmation attendue, sans base. */
class JarvisWorksTest {

    @Test
    @DisplayName("nom court : la clé de ticket d'abord, puis « chantier N », puis le sujet, puis les premiers mots")
    void shortName() {
        assertThat(JarvisWorks.shortName("Lancer LDIC-325 en autonome", 4, "data-platform")).isEqualTo("LDIC-325");
        assertThat(JarvisWorks.shortName("le backlog", 4, "data-platform")).isEqualTo("chantier 4");
        assertThat(JarvisWorks.shortName("le backlog", null, "data-platform")).isEqualTo("data-platform");
        assertThat(JarvisWorks.shortName("les tickets du sprint neuf", null, "")).isEqualTo("les tickets du");
        assertThat(JarvisWorks.shortName(null, null, null)).isEqualTo("Jarvis");
        assertThat(JarvisWorks.shortName("x", null, "un-nom-de-sujet-vraiment-tres-long")).hasSize(24).endsWith("…");
    }

    @Test
    @DisplayName("confirmation : seule la ligne 🎙 qui finit par « Vous confirmez ? » compte")
    void confirmation() {
        assertThat(JarvisWorks.asksConfirmation("🎙 Je lance LDIC-325 en autonome, sept étapes. Vous confirmez ?\n"
                + "Détail : …")).isTrue();
        assertThat(JarvisWorks.asksConfirmation("🎙 C'est fait : quatre tickets.\nVous confirmez ?")).isFalse();
        assertThat(JarvisWorks.asksConfirmation("🎙 Tu confirmes ?")).isTrue();
        assertThat(JarvisWorks.asksConfirmation(null)).isFalse();
    }

    @Test
    @DisplayName("phase d'une course : en cours = exécute ; finie, partielle = fait ; bloquée, échec = échec ; arrêtée = annulé")
    void coursePhase() {
        assertThat(JarvisWorksService.coursePhase(AutonomyRunStatus.RUNNING)).isEqualTo(JarvisWorkPhase.EXECUTING);
        assertThat(JarvisWorksService.coursePhase(AutonomyRunStatus.PARTIAL)).isEqualTo(JarvisWorkPhase.DONE);
        assertThat(JarvisWorksService.coursePhase(AutonomyRunStatus.BLOCKED)).isEqualTo(JarvisWorkPhase.FAILED);
        assertThat(JarvisWorksService.coursePhase(AutonomyRunStatus.STOPPED)).isEqualTo(JarvisWorkPhase.CANCELLED);
    }

    @Test
    @DisplayName("SF-271-04 : la raison d'arrêt dite tient en une phrase courte, sans Markdown")
    void shortReason() {
        assertThat(JarvisWorks.shortReason("**Bloqué** : il manque le `droit IAM`. Ensuite x."))
                .isEqualTo("Bloqué : il manque le droit IAM.");
        assertThat(JarvisWorks.shortReason("  ")).isNull();
        assertThat(JarvisWorks.shortReason(null)).isNull();
        assertThat(JarvisWorks.shortReason("mot ".repeat(80))).hasSizeLessThanOrEqualTo(161).endsWith("…");
    }
}
