package fr.claudegateway.governance.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;

/**
 * Les deux contrôles de fin de tour du premier paquet (F-52 / SF-52-02).
 *
 * <p>Ce que ces tests protègent : que le juge <b>alerte</b> au lieu de laisser passer quand il ne
 * comprend pas, qu'il nomme ce qu'il faut promouvoir, et que la dette bloque la clôture sans jamais
 * réclamer elle-même la forme du marqueur.</p>
 */
class EndOfTurnControlsTest {

    private final JugeFinDeTourControl juge = new JugeFinDeTourControl();
    private final PromotionDetteBloquanteControl dette = new PromotionDetteBloquanteControl();
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private AtelierCheckpointContext reply(String text) {
        return AtelierCheckpointContext.endOfTurn(userId, workspaceId, text, List.of());
    }

    // -------------------------------------------------------------------- identité

    @Test
    @DisplayName("les deux se déclarent sur la fin de tour, avec un identifiant stable")
    void bothDeclareTheEndOfTurn() {
        assertThat(juge.id()).isEqualTo("juge-fin-de-tour");
        assertThat(dette.id()).isEqualTo("promotion-dette-bloquante");
        assertThat(juge.kind()).isEqualTo(AtelierCheckpointKind.END_OF_TURN);
        assertThat(dette.kind()).isEqualTo(AtelierCheckpointKind.END_OF_TURN);
        assertThat(juge.description()).isNotBlank();
        assertThat(dette.description()).isNotBlank();
    }

    // ------------------------------------------------------------------------ juge

    @Test
    @DisplayName("sans marqueur, le juge ALERTE et rend la forme exacte attendue")
    void withoutAMarkerTheJudgeAlerts() {
        AtelierCheckpointVerdict verdict = juge.evaluate(reply("C'est fait."));

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction()).contains(FinDeTourMarker.FORME).contains("PLAN-ACTION.md");
    }

    @Test
    @DisplayName("un marqueur illisible alerte comme un marqueur absent")
    void anUnreadableMarkerAlertsToo() {
        assertThat(juge.evaluate(reply("<!-- fin-de-tour: promotion=aucune; dette=beaucoup -->"))
                .blocked()).isTrue();
    }

    @Test
    @DisplayName("ce qui est déclaré durable et non promu bloque, et le refus le NOMME")
    void whatIsDurableAndNotPromotedBlocks() {
        AtelierCheckpointVerdict verdict = juge.evaluate(reply(
                "<!-- fin-de-tour: promotion=le format du jeton runner, la borne SSE; dette=0 -->"));

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction())
                .contains("le format du jeton runner")
                .contains("la borne SSE")
                .contains("promotion=aucune");
    }

    @Test
    @DisplayName("rien à promouvoir : le juge passe, même avec de la dette — ce n'est pas son rôle")
    void nothingToPromotePasses() {
        assertThat(juge.evaluate(reply("Fait.\n<!-- fin-de-tour: promotion=aucune; dette=3 -->"))
                .blocked()).isFalse();
    }

    @Test
    @DisplayName("un contexte absent ne fait jamais lever le juge")
    void anAbsentContextIsSurvived() {
        assertThat(juge.evaluate(null).blocked()).isTrue();
        assertThat(juge.evaluate(AtelierCheckpointContext.endOfTurn(null, null, null, List.of()))
                .blocked()).isTrue();
    }

    // ----------------------------------------------------------------------- dette

    @Test
    @DisplayName("une case non cochée empêche de clore, et le refus dit combien")
    void anUncheckedBoxBlocks() {
        AtelierCheckpointVerdict verdict =
                dette.evaluate(reply("<!-- fin-de-tour: promotion=aucune; dette=2 -->"));

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction()).contains("2").contains("dette=0");
    }

    @Test
    @DisplayName("une seule case se dit au singulier")
    void oneBoxIsSingular() {
        assertThat(dette.evaluate(reply("<!-- fin-de-tour: promotion=aucune; dette=1 -->"))
                .correction()).contains("case « - [ ] » non cochée");
    }

    @Test
    @DisplayName("dette nulle : la clôture passe")
    void noDebtPasses() {
        assertThat(dette.evaluate(reply("<!-- fin-de-tour: promotion=aucune; dette=0 -->")).blocked())
                .isFalse();
    }

    @Test
    @DisplayName("sans marqueur, la dette PASSE : réclamer la forme est le travail du juge")
    void withoutAMarkerTheDebtControlPasses() {
        assertThat(dette.evaluate(reply("C'est fait.")).blocked()).isFalse();
        assertThat(dette.evaluate(null).blocked()).isFalse();
    }

    // ------------------------------------------------------------------ isolation

    @Test
    @DisplayName("les deux contrôles ne lisent aucune donnée : l'identité ne change rien au verdict")
    void neitherControlReadsAnyData() {
        String marked = "<!-- fin-de-tour: promotion=aucune; dette=4 -->";

        assertThat(dette.evaluate(AtelierCheckpointContext.endOfTurn(UUID.randomUUID(),
                UUID.randomUUID(), marked, List.of())).blocked()).isTrue();
        assertThat(dette.evaluate(AtelierCheckpointContext.endOfTurn(null, null, marked, List.of()))
                .blocked()).isTrue();
        assertThat(juge.evaluate(AtelierCheckpointContext.endOfTurn(null, null, marked, List.of()))
                .blocked()).isFalse();
    }
}
