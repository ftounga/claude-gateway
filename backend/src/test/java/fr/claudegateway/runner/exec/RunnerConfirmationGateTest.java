package fr.claudegateway.runner.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fr.claudegateway.runner.exec.RunnerConfirmationGate.Decision;
import fr.claudegateway.runner.exec.RunnerConfirmationGate.Outcome;

/**
 * Porte de validation des actions exécutées sur la machine de l'utilisateur (F-38 / SF-38-08, D7).
 *
 * <p>Ce qui est vérifié ici tient en une phrase : <b>seul un « oui » explicite du propriétaire
 * autorise</b>. Tout le reste — silence, identifiant deviné, interruption — refuse.</p>
 */
class RunnerConfirmationGateTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** Lance l'attente sur un autre thread et rend la main dès que la demande est enregistrée. */
    private Future<Outcome> awaitAsync(RunnerConfirmationGate gate, String callId) throws Exception {
        CountDownLatch registered = new CountDownLatch(1);
        Future<Outcome> pending = executor.submit(
                () -> gate.await(userId, workspaceId, callId, registered::countDown));
        assertThat(registered.await(2, TimeUnit.SECONDS)).isTrue();
        return pending;
    }

    @Test
    void anExplicitAllowAuthorises() throws Exception {
        RunnerConfirmationGate gate = new RunnerConfirmationGate(5_000L);
        Future<Outcome> pending = awaitAsync(gate, "toolu_1");

        gate.resolve(userId, workspaceId, "toolu_1", true, null);

        Outcome outcome = pending.get(2, TimeUnit.SECONDS);
        assertThat(outcome.decision()).isEqualTo(Decision.ALLOW);
        assertThat(outcome.decision().allows()).isTrue();
    }

    @Test
    void aDenialCarriesItsReasonToTheModel() throws Exception {
        RunnerConfirmationGate gate = new RunnerConfirmationGate(5_000L);
        Future<Outcome> pending = awaitAsync(gate, "toolu_2");

        gate.resolve(userId, workspaceId, "toolu_2", false, "  trop risqué  ");

        Outcome outcome = pending.get(2, TimeUnit.SECONDS);
        assertThat(outcome.decision()).isEqualTo(Decision.DENY);
        assertThat(outcome.decision().allows()).isFalse();
        assertThat(outcome.reason()).isEqualTo("trop risqué");
    }

    @Test
    void silenceRefusesRatherThanAuthorises() {
        RunnerConfirmationGate gate = new RunnerConfirmationGate(120L);

        Outcome outcome = gate.await(userId, workspaceId, "toolu_3", () -> { });

        assertThat(outcome.decision()).isEqualTo(Decision.TIMEOUT);
        assertThat(outcome.decision().allows()).isFalse();
        // La demande n'est plus en attente : une réponse tardive ne peut plus rien autoriser.
        assertThatThrownBy(() -> gate.resolve(userId, workspaceId, "toolu_3", true, null))
                .isInstanceOf(NoPendingConfirmationException.class);
    }

    @Test
    void answeringAnUnknownRequestIsRefused() {
        RunnerConfirmationGate gate = new RunnerConfirmationGate(5_000L);

        assertThatThrownBy(() -> gate.resolve(userId, workspaceId, "inconnu", true, null))
                .isInstanceOf(NoPendingConfirmationException.class);
    }

    @Test
    void anotherUserCannotAuthoriseMyCommand() throws Exception {
        RunnerConfirmationGate gate = new RunnerConfirmationGate(400L);
        Future<Outcome> pending = awaitAsync(gate, "toolu_4");

        assertThatThrownBy(
                () -> gate.resolve(UUID.randomUUID(), workspaceId, "toolu_4", true, null))
                .isInstanceOf(NoPendingConfirmationException.class);
        assertThatThrownBy(() -> gate.resolve(userId, UUID.randomUUID(), "toolu_4", true, null))
                .isInstanceOf(NoPendingConfirmationException.class);

        // La demande reste non tranchée : elle finit refusée par expiration, jamais autorisée.
        assertThat(pending.get(2, TimeUnit.SECONDS).decision()).isEqualTo(Decision.TIMEOUT);
    }

    @Test
    void interruptingTheTurnReleasesPendingRequestsAsRefusals() throws Exception {
        RunnerConfirmationGate gate = new RunnerConfirmationGate(10_000L);
        Future<Outcome> pending = awaitAsync(gate, "toolu_5");

        assertThat(gate.cancelWorkspace(workspaceId)).isEqualTo(1);

        assertThat(pending.get(2, TimeUnit.SECONDS).decision()).isEqualTo(Decision.DENY);
    }

    @Test
    void interruptingAnotherWorkspaceReleasesNothing() throws Exception {
        RunnerConfirmationGate gate = new RunnerConfirmationGate(400L);
        Future<Outcome> pending = awaitAsync(gate, "toolu_6");

        assertThat(gate.cancelWorkspace(UUID.randomUUID())).isZero();

        assertThat(pending.get(2, TimeUnit.SECONDS).decision()).isEqualTo(Decision.TIMEOUT);
    }

    @Test
    void decisionLabelsMatchTheStreamContract() {
        assertThat(Decision.ALLOW.label()).isEqualTo("allow");
        assertThat(Decision.DENY.label()).isEqualTo("deny");
        assertThat(Decision.TIMEOUT.label()).isEqualTo("timeout");
    }
}
