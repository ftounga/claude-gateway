package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import fr.claudegateway.atelier.AtelierProgressListener.AtelierConfirmResolved;
import fr.claudegateway.atelier.AtelierProgressListener.AtelierQuestionResolved;
import fr.claudegateway.atelier.actions.AttenteBlock;
import fr.claudegateway.atelier.proposal.GovernanceProposalBlock;

/** Le relevé d'un tour (F-185 / SF-185-02) : il retient sans rien retirer à l'écouteur d'origine. */
class TurnSignalsTest {

    private static AttenteBlock attente(String kind) {
        return new AttenteBlock(UUID.randomUUID(), UUID.randomUUID(), kind, "NONE", "x", null, null, null,
                null, null, null, null);
    }

    @Test
    void transmetChaqueMethodeDeLEcouteur() throws Exception {
        // Toute méthode de l'interface doit être redéfinie : une méthode « par défaut » héritée
        // couperait silencieusement le relais vers l'écran.
        for (Method method : AtelierProgressListener.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Method own = TurnSignals.class.getMethod(method.getName(), method.getParameterTypes());
            assertThat(own.getDeclaringClass()).as(method.getName()).isEqualTo(TurnSignals.class);
        }
    }

    @Test
    void relaieLesEvenementsAuDelegue() {
        AtelierProgressListener delegate = mock(AtelierProgressListener.class);
        TurnSignals signals = new TurnSignals(delegate);
        AtelierQuestionResolved resolved = new AtelierQuestionResolved("c1", "answered", List.of());
        UUID host = UUID.randomUUID();

        signals.onText("bonjour");
        signals.onQuestionResolved(resolved);
        signals.onRunnerOffline(host);
        signals.takeSteers();

        verify(delegate).onText("bonjour");
        verify(delegate).onQuestionResolved(resolved);
        verify(delegate).onRunnerOffline(host);
        verify(delegate).takeSteers();
    }

    @Test
    void retientLeDelaiEcouleDUneQuestionOuDUneAutorisation() {
        TurnSignals question = new TurnSignals(null);
        question.onQuestionResolved(new AtelierQuestionResolved("c1", "answered", List.of()));
        assertThat(question.timedOut()).isFalse();
        question.onQuestionResolved(new AtelierQuestionResolved("c2", "timeout", List.of()));
        assertThat(question.timedOut()).isTrue();

        TurnSignals permission = new TurnSignals(null);
        permission.onConfirmResolved(new AtelierConfirmResolved("p1", "deny"));
        assertThat(permission.timedOut()).isFalse();
        permission.onConfirmResolved(new AtelierConfirmResolved("p2", "timeout"));
        assertThat(permission.timedOut()).isTrue();
    }

    @Test
    void retientUneValidationAConfirmer() {
        TurnSignals added = new TurnSignals(null);
        added.onAttente("t1", attente(AttenteBlock.ADDED));
        assertThat(added.validationAwaiting()).isFalse();
        added.onAttente("t2", attente(AttenteBlock.PROPOSED));
        assertThat(added.validationAwaiting()).isTrue();

        TurnSignals handoff = new TurnSignals(null);
        handoff.onHandoff("t3", new SubjectHandoff(UUID.randomUUID(), "suite", "On reprend ici."));
        assertThat(handoff.validationAwaiting()).isTrue();

        TurnSignals governance = new TurnSignals(null);
        governance.onGovernanceProposal("t4", Mockito.mock(GovernanceProposalBlock.class));
        assertThat(governance.validationAwaiting()).isTrue();
    }

    @Test
    void retientLePostePerdu() {
        TurnSignals signals = new TurnSignals(null);
        assertThat(signals.machineLost()).isFalse();
        signals.onRunnerOffline(UUID.randomUUID());
        assertThat(signals.machineLost()).isTrue();
    }
}
