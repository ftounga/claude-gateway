package fr.claudegateway.governance.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import fr.claudegateway.governance.juge.JugeAvis;
import fr.claudegateway.governance.juge.JugeIndependantService;
import fr.claudegateway.governance.juge.JugeMemo;
import fr.claudegateway.governance.juge.JugeVerdict;

/**
 * F-94 / SF-94-03 — <b>le juge branché en fin de tour</b>.
 *
 * <p>Ce que ces tests protègent :</p>
 * <ul>
 *   <li><b>il ne coûte que quand il peut servir</b> — un tour qui n'a rien écrit ne consulte
 *       personne, et la même question n'est pas reposée ;</li>
 *   <li><b>best-effort, jamais une autorité</b> — le refus se présente comme une liste à vérifier,
 *       et invite explicitement à ignorer ce qui ne tient pas ;</li>
 *   <li><b>le repli alerte</b> — un verdict illisible bloque, un juge indisponible non ;</li>
 *   <li>il <b>ne casse jamais rien</b>.</li>
 * </ul>
 */
class JugeIndependantControlTest {

    private final JugeIndependantService juge = mock(JugeIndependantService.class);
    private final GovernanceMapDestinations destinations = mock(GovernanceMapDestinations.class);
    private final JugeMemo memo = new JugeMemo();
    private final JugeIndependantControl control =
            new JugeIndependantControl(juge, memo, destinations);

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        memo.clear();
        when(destinations.citedForProject(any(), any())).thenReturn("acces.md, plateformes.md");
    }

    private AtelierCheckpointContext tour(String... ecrits) {
        return AtelierCheckpointContext.endOfTurn(userId, workspaceId, "voilà.", List.of(ecrits));
    }

    @Test
    @DisplayName("Il se déclare sur la fin de tour, sous un identifiant stable")
    void identite() {
        assertThat(control.id()).isEqualTo("juge-independant");
        assertThat(control.kind()).isEqualTo(AtelierCheckpointKind.END_OF_TURN);
        assertThat(control.description()).isNotBlank();
    }

    @Test
    @DisplayName("Un tour sans écriture ne consulte personne")
    void sansEcriture() {
        assertThat(control.evaluate(tour()).blocked()).isFalse();
        verify(juge, never()).consulter(any(), any());
    }

    @Test
    @DisplayName("Un contexte inutilisable passe sans rien consulter")
    void contexteInutilisable() {
        assertThat(control.evaluate(null).blocked()).isFalse();
        assertThat(control.evaluate(AtelierCheckpointContext.endOfTurn(null, workspaceId, "x",
                List.of("STATE.md"))).blocked()).isFalse();
        verify(juge, never()).consulter(any(), any());
    }

    @Test
    @DisplayName("Rien à signaler : le tour se clôt")
    void rienASignaler() {
        when(juge.consulter(userId, workspaceId)).thenReturn(JugeAvis.rien());

        assertThat(control.evaluate(tour("STATE.md")).blocked()).isFalse();
    }

    @Test
    @DisplayName("Pas de matière ou juge indisponible : le tour se clôt, sans bruit")
    void indisponible() {
        when(juge.consulter(userId, workspaceId)).thenReturn(JugeAvis.pasDeMatiere());
        assertThat(control.evaluate(tour("STATE.md")).blocked()).isFalse();

        memo.clear();
        when(juge.consulter(userId, workspaceId)).thenReturn(JugeAvis.indisponible());
        assertThat(control.evaluate(tour("STATE.md")).blocked()).isFalse();
    }

    @Test
    @DisplayName("Des éléments : un refus best-effort, avec la source et les destinations réelles")
    void elements() {
        when(juge.consulter(userId, workspaceId)).thenReturn(JugeAvis.elements(
                List.of(new JugeVerdict.Element("bastion bst-01", "migration-dns/STATE.md"))));

        AtelierCheckpointVerdict verdict = control.evaluate(tour("STATE.md"));

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction())
                .startsWith(JugeIndependantControl.BEST_EFFORT.strip())
                .contains("bastion bst-01", "migration-dns/STATE.md")
                .contains("acces.md, plateformes.md")
                .contains("vérifie-le dans le fichier cité")
                .contains("ignore-le");
    }

    @Test
    @DisplayName("LE REPLI QUI ALERTE : verdict illisible, on signale et on dit quoi relire")
    void verdictIllisible() {
        when(juge.consulter(userId, workspaceId)).thenReturn(JugeAvis.verdictIllisible());

        AtelierCheckpointVerdict verdict = control.evaluate(tour("STATE.md"));

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.correction())
                .contains("n'a PAS rendu de verdict lisible")
                .contains("Relis toi-même")
                .contains("acces.md, plateformes.md");
    }

    @Test
    @DisplayName("La même question n'est pas reposée — le tour rejoué ne rappelle pas le juge")
    void memeQuestionNonReposee() {
        when(juge.consulter(userId, workspaceId)).thenReturn(JugeAvis.verdictIllisible());

        assertThat(control.evaluate(tour("STATE.md")).blocked()).isTrue();
        assertThat(control.evaluate(tour("STATE.md")).blocked()).isFalse();

        verify(juge, times(1)).consulter(userId, workspaceId);
    }

    @Test
    @DisplayName("Une écriture nouvelle repose la question")
    void ecritureNouvelle() {
        when(juge.consulter(userId, workspaceId)).thenReturn(JugeAvis.rien());

        control.evaluate(tour("STATE.md"));
        control.evaluate(tour("STATE.md", "PLAN-ACTION.md"));

        verify(juge, times(2)).consulter(userId, workspaceId);
    }

    @Test
    @DisplayName("Un juge qui lève ne casse pas le tour")
    void jugeQuiLeve() {
        doThrow(new IllegalStateException("bogue")).when(juge).consulter(userId, workspaceId);

        assertThat(control.evaluate(tour("STATE.md")).blocked()).isFalse();
    }
}
