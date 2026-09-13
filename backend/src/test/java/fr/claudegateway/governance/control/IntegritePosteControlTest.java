package fr.claudegateway.governance.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;
import fr.claudegateway.governance.integrite.IntegriteConstat;
import fr.claudegateway.governance.integrite.IntegriteInspection;
import fr.claudegateway.governance.integrite.IntegriteMemo;
import fr.claudegateway.governance.integrite.IntegriteRapport;

/**
 * F-95 / SF-95-03 — <b>l'intégrité remise au modèle</b>.
 *
 * <p>Ce que ces tests protègent :</p>
 * <ul>
 *   <li>un <b>avertissement ne bloque jamais</b> — c'est l'exigence littérale de la feature, et
 *       c'est aussi ce qui l'empêche de devenir insupportable ;</li>
 *   <li>l'inspection ne part <b>que si le tour a écrit</b>, et <b>une seule fois</b> pour les mêmes
 *       écritures : sans ces deux gardes, un tour refusé ferait trois inspections de la machine
 *       d'un client ;</li>
 *   <li>une inspection qui lève laisse le tour se terminer (F-50, décision D2).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IntegritePosteControlTest {

    @Mock
    private IntegriteInspection inspection;

    private IntegriteMemo memo;
    private IntegritePosteControl control;

    private final UUID alice = UUID.randomUUID();
    private final UUID projet = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        memo = new IntegriteMemo();
        control = new IntegritePosteControl(inspection, memo);
    }

    @Test
    @DisplayName("il se branche en fin de tour, sous un identifiant stable")
    void itHangsOnTheEndOfTurn() {
        assertThat(control.id()).isEqualTo("integrite-du-poste");
        assertThat(control.kind()).isEqualTo(AtelierCheckpointKind.END_OF_TURN);
        assertThat(control.description()).isNotBlank();
    }

    @Test
    @DisplayName("un tour qui n'a rien écrit ne déclenche aucune inspection")
    void aTurnThatWroteNothingIsFree() {
        AtelierCheckpointVerdict verdict = control.evaluate(finDeTour(List.of()));

        assertThat(verdict.blocked()).isFalse();
        verifyNoInteractions(inspection);
    }

    @Test
    @DisplayName("les mêmes écritures ne sont inspectées qu'une fois")
    void theSameWritesAreInspectedOnlyOnce() {
        when(inspection.deProjet(alice, projet)).thenReturn(IntegriteRapport.sain());

        control.evaluate(finDeTour(List.of("STATE.md")));
        control.evaluate(finDeTour(List.of("STATE.md")));

        verify(inspection, times(1)).deProjet(alice, projet);
    }

    @Test
    @DisplayName("une écriture nouvelle relance l'inspection — une correction doit être vérifiée")
    void aNewWriteIsInspectedAgain() {
        when(inspection.deProjet(alice, projet)).thenReturn(IntegriteRapport.sain());

        control.evaluate(finDeTour(List.of("STATE.md")));
        control.evaluate(finDeTour(List.of("STATE.md", "acces.md")));

        verify(inspection, times(2)).deProjet(alice, projet);
    }

    @Test
    @DisplayName("des avertissements seuls ne bloquent jamais")
    void warningsAloneNeverBlock() {
        when(inspection.deProjet(alice, projet)).thenReturn(IntegriteRapport.de(List.of(
                IntegriteConstat.detteEnCours("migration-dns", 2, "acces.md"),
                IntegriteConstat.lienMort("acces.md", "vieux/notes.md"))));

        assertThat(control.evaluate(finDeTour(List.of("STATE.md"))).blocked()).isFalse();
    }

    @Test
    @DisplayName("une erreur refuse la fin du tour, et le message porte les deux niveaux séparés")
    void anErrorBlocksAndCarriesBothLevels() {
        when(inspection.deProjet(alice, projet)).thenReturn(IntegriteRapport.de(List.of(
                IntegriteConstat.carteAbsente("reseau.md"),
                IntegriteConstat.detteEnCours("migration-dns", 1, "acces.md"))));

        AtelierCheckpointVerdict verdict = control.evaluate(finDeTour(List.of("STATE.md")));

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction()).contains(IntegriteRapport.ENTETE_ERREURS);
        assertThat(verdict.correction()).contains(IntegriteRapport.ENTETE_AVERTISSEMENTS);
        assertThat(verdict.correction()).contains("ne bloquent pas");
        assertThat(verdict.correction().length())
                .isLessThanOrEqualTo(AtelierCheckpointVerdict.MAX_CORRECTION_CHARS);
    }

    @Test
    @DisplayName("un rapport silencieux laisse passer : on ne bloque pas sur ce qu'on n'a pas lu")
    void aSilentReportNeverBlocks() {
        when(inspection.deProjet(alice, projet)).thenReturn(IntegriteRapport.silencieux());

        assertThat(control.evaluate(finDeTour(List.of("STATE.md"))).blocked()).isFalse();
    }

    @Test
    @DisplayName("une inspection qui lève laisse le tour se terminer")
    void aBrokenInspectionNeverHoldsTheTurnHostage() {
        when(inspection.deProjet(alice, projet)).thenThrow(new IllegalStateException("cassé"));

        assertThat(control.evaluate(finDeTour(List.of("STATE.md"))).blocked()).isFalse();
    }

    @Test
    @DisplayName("un contexte sans projet ne déclenche rien")
    void aContextWithoutProjectDoesNothing() {
        AtelierCheckpointVerdict verdict = control.evaluate(AtelierCheckpointContext.endOfTurn(
                null, null, "fini", List.of("STATE.md")));

        assertThat(verdict.blocked()).isFalse();
        verify(inspection, never()).deProjet(any(), any());
    }

    private AtelierCheckpointContext finDeTour(List<String> ecrits) {
        return AtelierCheckpointContext.endOfTurn(alice, projet, "voilà.", ecrits);
    }
}
