package fr.claudegateway.governance.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;
import fr.claudegateway.atelier.checkpoint.AtelierMachineReach;
import fr.claudegateway.governance.GovernanceMapDestinations;
import fr.claudegateway.governance.integrite.IntegriteInspection;
import fr.claudegateway.governance.integrite.IntegriteMemo;
import fr.claudegateway.governance.juge.JugeIndependantService;
import fr.claudegateway.governance.juge.JugeMemo;

/**
 * <b>Poste hors ligne : la promotion est reportée, pas exigée</b> (F-93 / SF-93-04).
 *
 * <p>Ce que ces tests protègent : qu'un contrôle qui exige d'écrire sur la machine ne refuse plus
 * la clôture quand la machine ne répond pas — sans pour autant effacer la dette, qui est réclamée
 * au premier tour où le poste répond, une fois, au bon projet.</p>
 */
class OfflineEndOfTurnControlsTest {

    private final GovernanceMapDestinations destinations = mock(GovernanceMapDestinations.class);
    private final PromotionReportee reportees = new PromotionReportee();
    private final JugeFinDeTourControl juge = new JugeFinDeTourControl(destinations, reportees);
    private final PromotionDetteBloquanteControl dette =
            new PromotionDetteBloquanteControl(destinations, reportees);
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID projet = UUID.randomUUID();
    private final UUID host = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(destinations.pathsForProject(any(), any()))
                .thenReturn(List.of("README.md", "acces.md", "plateformes.md"));
    }

    private AtelierCheckpointContext tour(UUID user, String text, AtelierMachineReach machine) {
        return AtelierCheckpointContext.endOfTurn(user, host, projet, text, List.of(), machine);
    }

    private static String marqueur(String corps) {
        return "Je ne peux pas écrire : le runner n'est pas connecté.\n\n<!-- fin-de-tour: " + corps + " -->";
    }

    @Test
    @DisplayName("hors ligne, une promotion déclarée est reportée : aucun refus, la mention unique")
    void offlinePromotionIsDeferred() {
        AtelierCheckpointVerdict verdict = juge.evaluate(tour(alice,
                marqueur("promotion=cluster atlas; dette=0"), AtelierMachineReach.OFFLINE));

        assertThat(verdict.blocked()).isFalse();
        assertThat(verdict.notice()).isEqualTo(PromotionReportee.NOTICE);
        assertThat(reportees.estDue(alice, host, projet)).isTrue();
    }

    @Test
    @DisplayName("hors ligne, une dette ou une promotion sans destination est reportée par le contrôle de dette")
    void offlineDebtIsDeferred() {
        AtelierCheckpointVerdict detteVerdict = dette.evaluate(tour(alice,
                marqueur("promotion=aucune; dette=3"), AtelierMachineReach.OFFLINE));
        assertThat(detteVerdict.blocked()).isFalse();
        assertThat(detteVerdict.notice()).isEqualTo(PromotionReportee.NOTICE);

        AtelierCheckpointVerdict muet = dette.evaluate(tour(alice,
                marqueur("promotion=aucune; promu=vpn nord; dette=0"), AtelierMachineReach.OFFLINE));
        assertThat(muet.blocked()).isFalse();
        assertThat(muet.hasNotice()).isTrue();

        PromotionReportee.Report report = reportees.reclamer(alice, host, projet).orElseThrow();
        assertThat(report.elements()).containsExactly("vpn nord");
        assertThat(report.dette()).isEqualTo(3);
    }

    @Test
    @DisplayName("hors ligne, un marqueur complet et soldé passe sans mention")
    void offlineCleanMarkerProceeds() {
        String propre = marqueur("promotion=aucune; promu=cluster -> acces.md; dette=0");

        assertThat(juge.evaluate(tour(alice, propre, AtelierMachineReach.OFFLINE)).hasNotice()).isFalse();
        assertThat(dette.evaluate(tour(alice, propre, AtelierMachineReach.OFFLINE)).hasNotice()).isFalse();
        assertThat(reportees.estDue(alice, host, projet)).isFalse();
    }

    @Test
    @DisplayName("hors ligne, le marqueur absent est toujours réclamé : le poser n'écrit rien sur la machine")
    void offlineMissingMarkerStillBlocks() {
        AtelierCheckpointVerdict verdict = juge.evaluate(tour(alice, "Je ne peux pas écrire.",
                AtelierMachineReach.OFFLINE));

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction()).contains(FinDeTourMarker.FORME);
    }

    @Test
    @DisplayName("poste revenu : la dette reportée est réclamée une fois, puis les contrôles ordinaires reprennent")
    void reachedClaimsOnce() {
        juge.evaluate(tour(alice, marqueur("promotion=cluster atlas; dette=1"), AtelierMachineReach.OFFLINE));
        String soldé = marqueur("promotion=aucune; dette=0");

        AtelierCheckpointVerdict reclame = dette.evaluate(tour(alice, soldé, AtelierMachineReach.REACHED));
        assertThat(reclame.blocked()).isTrue();
        assertThat(reclame.correction()).contains("hors ligne").contains("cluster atlas")
                .contains("plateformes.md");

        assertThat(juge.evaluate(tour(alice, soldé, AtelierMachineReach.REACHED)).blocked()).isFalse();
        assertThat(dette.evaluate(tour(alice, soldé, AtelierMachineReach.REACHED)).blocked()).isFalse();
    }

    @Test
    @DisplayName("sans preuve que le poste répond, rien n'est réclamé")
    void unknownDoesNotClaim() {
        juge.evaluate(tour(alice, marqueur("promotion=cluster atlas; dette=0"), AtelierMachineReach.OFFLINE));
        String soldé = marqueur("promotion=aucune; dette=0");

        assertThat(juge.evaluate(tour(alice, soldé, AtelierMachineReach.UNKNOWN)).blocked()).isFalse();
        assertThat(reportees.estDue(alice, host, projet)).isTrue();
    }

    @Test
    @DisplayName("isolation : le report d'Alice n'est jamais réclamé à Bob")
    void isolation() {
        juge.evaluate(tour(alice, marqueur("promotion=cluster atlas; dette=0"), AtelierMachineReach.OFFLINE));

        assertThat(juge.evaluate(tour(bob, marqueur("promotion=aucune; dette=0"),
                AtelierMachineReach.REACHED)).blocked()).isFalse();
        assertThat(reportees.estDue(alice, host, projet)).isTrue();
    }

    @Test
    @DisplayName("poste joignable, les refus d'avant sont inchangés")
    void reachedKeepsTheRules() {
        AtelierCheckpointVerdict verdict = juge.evaluate(tour(alice,
                marqueur("promotion=cluster atlas; dette=0"), AtelierMachineReach.REACHED));

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.hasNotice()).isFalse();
        // F-125 / SF-125-04 : une dette seule ne bloque plus (la carte se tient en silence).
        assertThat(dette.evaluate(tour(alice, marqueur("promotion=aucune; dette=2"),
                AtelierMachineReach.REACHED)).blocked()).isFalse();
        assertThat(reportees.estDue(alice, host, projet)).isFalse();
    }

    @Test
    @DisplayName("hors ligne, le juge indépendant et l'intégrité ne lisent pas la machine")
    void offlineReadersProceedWithoutCalling() {
        JugeIndependantService service = mock(JugeIndependantService.class);
        IntegriteInspection inspection = mock(IntegriteInspection.class);
        JugeIndependantControl independant =
                new JugeIndependantControl(service, mock(JugeMemo.class), destinations);
        IntegritePosteControl integrite = new IntegritePosteControl(inspection, mock(IntegriteMemo.class));
        AtelierCheckpointContext offline = AtelierCheckpointContext.endOfTurn(alice, projet, "x",
                List.of("STATE.md"), AtelierMachineReach.OFFLINE);

        assertThat(independant.evaluate(offline).blocked()).isFalse();
        assertThat(integrite.evaluate(offline).blocked()).isFalse();
        verifyNoInteractions(service, inspection);
        verify(destinations, never()).citedForProject(any(), any());
    }
}
