package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-271 / SF-271-05 — le nom court d'une étape : un nom, jamais une phrase. */
class StepShortNameTest {

    @Test
    @DisplayName("un sigle technique d'abord, puis un nom propre, sinon deux mots sans le verbe de tête")
    void names() {
        assertThat(StepShortName.of("Créer le topic SNS d'ingestion", 1)).isEqualTo("SNS");
        assertThat(StepShortName.of("Donner le droit IAM au rôle d'ingestion", 2)).isEqualTo("IAM");
        assertThat(StepShortName.of("Appliquer le Terraform du compte dev", 3)).isEqualTo("Terraform");
        assertThat(StepShortName.of("Test en dev", 4)).isEqualTo("Test dev");
        assertThat(StepShortName.of("Ouvrir la MR", 5)).isEqualTo("MR");
        assertThat(StepShortName.of("Vérifier les droits", 6)).isEqualTo("Droits");
        assertThat(StepShortName.of("", 7)).isEqualTo("étape 7");
        assertThat(StepShortName.of(null, 8)).isEqualTo("étape 8");
    }

    @Test
    @DisplayName("jamais plus de 14 caractères")
    void bounded() {
        assertThat(StepShortName.of("Reconfigurer l'ordonnancement interministériel", 1)).hasSizeLessThanOrEqualTo(14);
        assertThat(StepShortName.of("Anticonstitutionnellement", 1)).hasSizeLessThanOrEqualTo(14).endsWith("…");
    }
}
