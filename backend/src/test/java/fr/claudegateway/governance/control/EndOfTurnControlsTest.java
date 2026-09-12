package fr.claudegateway.governance.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;
import fr.claudegateway.governance.GovernanceMapDestinations;

/**
 * Les deux contrôles de fin de tour du premier paquet (F-52 / SF-52-02, complétés par F-93 /
 * SF-93-01).
 *
 * <p>Ce que ces tests protègent : que le juge <b>alerte</b> au lieu de laisser passer quand il ne
 * comprend pas, qu'il nomme ce qu'il faut promouvoir <b>et où</b>, que la dette bloque la clôture
 * sans jamais réclamer elle-même la forme du marqueur, et qu'une promotion <b>muette sur sa
 * destination</b> soit refusée — c'est tout l'objet de F-93.</p>
 */
class EndOfTurnControlsTest {

    private final GovernanceMapDestinations destinations = mock(GovernanceMapDestinations.class);
    private final JugeFinDeTourControl juge = new JugeFinDeTourControl(destinations);
    private final PromotionDetteBloquanteControl dette =
            new PromotionDetteBloquanteControl(destinations);
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(destinations.pathsForProject(any(), any()))
                .thenReturn(List.of("README.md", "acces.md", "plateformes.md"));
    }

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
        assertThat(verdict.correction()).contains(FinDeTourMarker.FORME).contains("promu");
    }

    @Test
    @DisplayName("un marqueur illisible alerte comme un marqueur absent")
    void anUnreadableMarkerAlertsToo() {
        assertThat(juge.evaluate(reply("<!-- fin-de-tour: promotion=aucune; dette=beaucoup -->"))
                .blocked()).isTrue();
    }

    @Test
    @DisplayName("ce qui est déclaré durable et non promu bloque, et le refus NOMME LA DESTINATION")
    void whatIsDurableAndNotPromotedBlocks() {
        AtelierCheckpointVerdict verdict = juge.evaluate(reply(
                "<!-- fin-de-tour: promotion=le cluster atlas, le VPN client; dette=0 -->"));

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction())
                .contains("le cluster atlas")
                .contains("le VPN client")
                // C'est le défaut que F-93 corrige : le refus disait « la carte du projet ».
                .contains("acces.md")
                .contains("plateformes.md")
                .contains("PLAN-ACTION.md")
                .contains("STATE.md")
                .contains("promotion=aucune");
    }

    @Test
    @DisplayName("carte non listable : le juge reste correct, avec la formulation générique")
    void anUnlistableMapStillGivesAUsableRefusal() {
        when(destinations.pathsForProject(any(), any())).thenReturn(List.of());

        AtelierCheckpointVerdict verdict =
                juge.evaluate(reply("<!-- fin-de-tour: promotion=le bastion; dette=0 -->"));

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction()).contains(GovernanceMapDestinations.GENERIC);
    }

    @Test
    @DisplayName("rien à promouvoir : le juge passe, même avec de la dette — ce n'est pas son rôle")
    void nothingToPromotePasses() {
        assertThat(juge.evaluate(reply("Fait.\n<!-- fin-de-tour: promotion=aucune; dette=3 -->"))
                .blocked()).isFalse();
    }

    @Test
    @DisplayName("rien à promouvoir : la carte n'est même pas listée — on ne paie pas pour rien")
    void nothingToPromoteCostsNothing() {
        juge.evaluate(reply("<!-- fin-de-tour: promotion=aucune; dette=0 -->"));

        verify(destinations, never()).pathsForProject(any(), any());
    }

    @Test
    @DisplayName("un contexte absent ne fait jamais lever le juge")
    void anAbsentContextIsSurvived() {
        assertThat(juge.evaluate(null).blocked()).isTrue();
        assertThat(juge.evaluate(AtelierCheckpointContext.endOfTurn(null, null, null, List.of()))
                .blocked()).isTrue();
        assertThat(juge.evaluate(AtelierCheckpointContext.endOfTurn(null, null,
                "<!-- fin-de-tour: promotion=un serveur; dette=0 -->", List.of())).blocked())
                .isTrue();
    }

    // ------------------------------------------------------- promotion sans destination

    @Test
    @DisplayName("une promotion qui ne dit PAS OÙ est refusée, avec la forme exacte à reprendre")
    void aPromotionWithoutADestinationIsRefused() {
        AtelierCheckpointVerdict verdict = dette.evaluate(
                reply("<!-- fin-de-tour: promotion=aucune; promu=cluster atlas; dette=0 -->"));

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction())
                .contains("cluster atlas")
                .contains("promu=cluster atlas -> <fichier>")
                .contains("acces.md")
                .contains("- [x]")
                .contains("STATE.md");
    }

    @Test
    @DisplayName("une destination étrangère à la carte est refusée, et les vraies sont nommées")
    void aForeignDestinationIsRefused() {
        AtelierCheckpointVerdict verdict = dette.evaluate(reply(
                "<!-- fin-de-tour: promotion=aucune; promu=cluster atlas -> notes.md; dette=0 -->"));

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction()).contains("notes.md").contains("plateformes.md");
    }

    @Test
    @DisplayName("une destination de la carte passe — flèche, chemin relatif ou casse indifférents")
    void aRealDestinationPasses() {
        assertThat(dette.evaluate(reply(
                "<!-- fin-de-tour: promotion=aucune; promu=cluster atlas -> plateformes.md; dette=0 -->"))
                .blocked()).isFalse();
        assertThat(dette.evaluate(reply(
                "<!-- fin-de-tour: promotion=aucune; promu=VPN → ./Acces.MD; dette=0 -->"))
                .blocked()).isFalse();
        assertThat(dette.evaluate(reply(
                "<!-- fin-de-tour: promotion=aucune; promu=contact réseau vers README.md; dette=0 -->"))
                .blocked()).isFalse();
    }

    @Test
    @DisplayName("carte non listable : aucune destination n'est jugée étrangère")
    void withoutAMapNoDestinationIsForeign() {
        when(destinations.pathsForProject(any(), any())).thenReturn(List.of());

        assertThat(dette.evaluate(reply(
                "<!-- fin-de-tour: promotion=aucune; promu=cluster -> reseau.md; dette=0 -->"))
                .blocked()).isFalse();
    }

    @Test
    @DisplayName("une promotion muette est refusée AVANT la dette : l'ordre est le message")
    void theMuteDestinationComesFirst() {
        AtelierCheckpointVerdict verdict = dette.evaluate(
                reply("<!-- fin-de-tour: promotion=aucune; promu=cluster atlas; dette=4 -->"));

        assertThat(verdict.correction()).contains("sans dire où").doesNotContain("4");
    }

    // ----------------------------------------------------------------------- dette

    @Test
    @DisplayName("une case non cochée empêche de clore, et le refus dit combien ET OÙ PROMOUVOIR")
    void anUncheckedBoxBlocks() {
        AtelierCheckpointVerdict verdict =
                dette.evaluate(reply("<!-- fin-de-tour: promotion=aucune; dette=2 -->"));

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction()).contains("2").contains("dette=0")
                .contains("acces.md").contains("- [x]");
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
        verify(destinations, never()).pathsForProject(any(), any());
    }

    @Test
    @DisplayName("un marqueur sans « promu » reste lisible : les paquets déjà activés continuent")
    void anOldMarkerStillWorks() {
        assertThat(dette.evaluate(reply("<!-- fin-de-tour: promotion=aucune; dette=0 -->")).blocked())
                .isFalse();
        assertThat(dette.evaluate(reply("<!-- fin-de-tour: promotion=aucune; promu=aucune; dette=0 -->"))
                .blocked()).isFalse();
    }

    // ------------------------------------------------------------------ isolation

    @Test
    @DisplayName("la dette ne lit la carte QUE du couple (utilisateur, projet) du tour en cours")
    void theMapIsReadForTheCurrentProjectOnly() {
        dette.evaluate(reply("<!-- fin-de-tour: promotion=aucune; promu=x; dette=0 -->"));

        verify(destinations).pathsForProject(userId, workspaceId);
    }

    @Test
    @DisplayName("un couple absent ne fait jamais lever : la carte est simplement vide")
    void anAbsentIdentityNeverThrows() {
        String marked = "<!-- fin-de-tour: promotion=aucune; dette=4 -->";

        assertThat(dette.evaluate(AtelierCheckpointContext.endOfTurn(null, null, marked, List.of()))
                .blocked()).isTrue();
        assertThat(juge.evaluate(AtelierCheckpointContext.endOfTurn(null, null, marked, List.of()))
                .blocked()).isFalse();
    }
}
