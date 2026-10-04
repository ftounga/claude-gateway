package fr.claudegateway.atelier.actions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L'état proposé à la reprise (F-175 / SF-175-07, décision D9). */
class TerminalActionReviewServiceTest {

    @Test
    @DisplayName("« Demander / Relancer / Transférer » → Demandé ; le reste → À faire ; accents et casse ignorés")
    void suggestion() {
        assertThat(TerminalActionReviewService.suggest("Relancer Habib pour la dérogation SCP"))
                .isEqualTo(TerminalActionStatus.DEMANDE);
        assertThat(TerminalActionReviewService.suggest("  Transférer le mail v3 à Zahi"))
                .isEqualTo(TerminalActionStatus.DEMANDE);
        assertThat(TerminalActionReviewService.suggest("DEMANDER le compte forge CAPFM"))
                .isEqualTo(TerminalActionStatus.DEMANDE);
        assertThat(TerminalActionReviewService.suggest("Vérifier le droit d'accès"))
                .isEqualTo(TerminalActionStatus.A_FAIRE);
        assertThat(TerminalActionReviewService.suggest(null)).isEqualTo(TerminalActionStatus.A_FAIRE);
    }
}
