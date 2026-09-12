package fr.claudegateway.runner.host;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Comparaison de versions de runner (F-81 / SF-81-03).
 *
 * <p>La seule question posée est « ce runner est-il plus ancien que celui que la gateway
 * distribue ? ». Une réponse indécidable vaut mieux qu'une réponse inventée : le doute ne produit
 * jamais de ligne de journal, parce qu'une alerte fausse apprend à ne plus les lire.</p>
 */
class RunnerVersionsTest {

    @Test
    @DisplayName("compare segment par segment, en nombres et non en texte")
    void compareSegmentParSegment() {
        assertThat(RunnerVersions.isOlder("0.0.1", "0.2.0")).isTrue();
        assertThat(RunnerVersions.isOlder("0.2.0", "0.0.1")).isFalse();
        assertThat(RunnerVersions.isOlder("1.0.0", "1.0.0")).isFalse();

        assertThat(RunnerVersions.isOlder("0.2.0", "0.10.0"))
                .as("comparées comme du TEXTE, « 0.10.0 » serait antérieure à « 0.2.0 » — et un "
                        + "runner à jour serait signalé en retard à chaque connexion")
                .isTrue();
    }

    @Test
    @DisplayName("le qualificatif de Maven ne change pas le verdict")
    void leQualificatifEstIgnore() {
        assertThat(RunnerVersions.isOlder("0.0.1-SNAPSHOT", "0.1.0")).isTrue();
        assertThat(RunnerVersions.isOlder("0.0.1", "0.0.1-SNAPSHOT")).isFalse();
    }

    @Test
    @DisplayName("les segments absents valent zéro")
    void lesSegmentsAbsentsValentZero() {
        assertThat(RunnerVersions.isOlder("1.2", "1.2.1")).isTrue();
        assertThat(RunnerVersions.isOlder("1.2.0", "1.2")).isFalse();
    }

    @Test
    @DisplayName("une version illisible ou absente n'est jamais déclarée en retard")
    void leDouteNeProduitAucunVerdict() {
        assertThat(RunnerVersions.isOlder(null, "1.0.0")).isFalse();
        assertThat(RunnerVersions.isOlder("", "1.0.0")).isFalse();
        assertThat(RunnerVersions.isOlder("maison", "1.0.0")).isFalse();
        assertThat(RunnerVersions.isOlder("1.0.x", "1.0.0")).isFalse();
        assertThat(RunnerVersions.isOlder("0.0.1", null))
                .as("pas de référence, pas de verdict : la gateway ne sait pas ce qu'elle distribue")
                .isFalse();
    }
}
