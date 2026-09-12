package fr.claudegateway.governance.juge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-94 / SF-94-02 — les garde-fous du juge.
 *
 * <p>Ce que ces tests protègent : <b>un réglage aberrant retombe sur le défaut</b> plutôt que
 * d'empêcher le démarrage. Une valeur mal saisie dans une carte de configuration ne doit pas
 * condamner le produit entier pour une fonction qui, elle, n'a qu'à s'abstenir.</p>
 */
class JugePropertiesTest {

    @Test
    @DisplayName("Rien de configuré : les défauts, et le juge est actif")
    void defauts() {
        JugeProperties props = new JugeProperties(null, null, null, null);

        assertThat(props.actif()).isTrue();
        assertThat(props.model()).isNull();
        assertThat(props.maxTokens()).isEqualTo(JugeProperties.DEFAULT_MAX_TOKENS);
        assertThat(props.timeout()).isEqualTo(JugeProperties.DEFAULT_TIMEOUT);
    }

    @Test
    @DisplayName("Le coupe-circuit se ferme explicitement")
    void coupeCircuit() {
        assertThat(new JugeProperties(false, null, null, null).actif()).isFalse();
    }

    @Test
    @DisplayName("Un modèle vide vaut « pas de préférence »")
    void modeleVide() {
        assertThat(new JugeProperties(true, "   ", null, null).model()).isNull();
        assertThat(new JugeProperties(true, " claude-haiku-4-5 ", null, null).model())
                .isEqualTo("claude-haiku-4-5");
    }

    @Test
    @DisplayName("Un plafond hors bornes retombe sur le défaut")
    void plafondHorsBornes() {
        assertThat(new JugeProperties(true, null, 0, null).maxTokens())
                .isEqualTo(JugeProperties.DEFAULT_MAX_TOKENS);
        assertThat(new JugeProperties(true, null, 99_999, null).maxTokens())
                .isEqualTo(JugeProperties.DEFAULT_MAX_TOKENS);
        assertThat(new JugeProperties(true, null, 2_000, null).maxTokens()).isEqualTo(2_000);
    }

    @Test
    @DisplayName("Un délai hors bornes retombe sur le défaut")
    void delaiHorsBornes() {
        assertThat(new JugeProperties(true, null, null, Duration.ofMillis(10)).timeout())
                .isEqualTo(JugeProperties.DEFAULT_TIMEOUT);
        assertThat(new JugeProperties(true, null, null, Duration.ofHours(1)).timeout())
                .isEqualTo(JugeProperties.DEFAULT_TIMEOUT);
        assertThat(new JugeProperties(true, null, null, Duration.ofSeconds(40)).timeout())
                .isEqualTo(Duration.ofSeconds(40));
    }
}
