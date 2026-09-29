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

import fr.claudegateway.runner.exec.RunnerConfirmationGate.AnswerOutcome;
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
    void theGateAnnouncesTheDelayItWaits() {
        // F-47 / SF-47-02 : le délai est dit à l'écran, jamais codé en dur côté client.
        assertThat(new RunnerConfirmationGate(45_000L).timeoutMs()).isEqualTo(45_000L);
    }

    @Test
    void anUnusableDelayFallsBackOnTheDefaultOne() {
        // Non-régression : un réglage à zéro ou négatif ne doit pas rendre la porte passante.
        assertThat(new RunnerConfirmationGate(0L).timeoutMs())
                .isEqualTo(RunnerConfirmationGate.DEFAULT_TIMEOUT_MS);
        assertThat(new RunnerConfirmationGate(-1L).timeoutMs())
                .isEqualTo(RunnerConfirmationGate.DEFAULT_TIMEOUT_MS);
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

    // ------------------------------------------------ F-164 / SF-164-01 : la question structurée

    /** Lance l'attente d'une réponse sur un autre thread et rend la main dès l'enregistrement. */
    private Future<AnswerOutcome> awaitAnswerAsync(RunnerConfirmationGate gate, String callId) throws Exception {
        CountDownLatch registered = new CountDownLatch(1);
        Future<AnswerOutcome> pending = executor.submit(
                () -> gate.awaitAnswer(userId, workspaceId, callId, registered::countDown));
        assertThat(registered.await(2, TimeUnit.SECONDS)).isTrue();
        return pending;
    }

    @Test
    void aQuestionCarriesTheUsersReplyBack() throws Exception {
        RunnerConfirmationGate gate = new RunnerConfirmationGate(5_000L);
        Future<AnswerOutcome> pending = awaitAnswerAsync(gate, "q_1");

        gate.answerQuestions(userId, workspaceId, "q_1", "Option A");

        AnswerOutcome outcome = pending.get(2, TimeUnit.SECONDS);
        assertThat(outcome.answered()).isTrue();
        assertThat(outcome.status()).isEqualTo(AnswerOutcome.Status.ANSWERED);
        assertThat(outcome.content()).isEqualTo("Option A");
    }

    @Test
    void silenceOnAQuestionTimesOutRatherThanAnswering() {
        RunnerConfirmationGate gate = new RunnerConfirmationGate(120L);

        AnswerOutcome outcome = gate.awaitAnswer(userId, workspaceId, "q_2", () -> { });

        assertThat(outcome.status()).isEqualTo(AnswerOutcome.Status.TIMEOUT);
        assertThat(outcome.answered()).isFalse();
        // Une réponse tardive ne peut plus rien trancher : la question n'est plus en attente.
        assertThatThrownBy(() -> gate.answerQuestions(userId, workspaceId, "q_2", "trop tard"))
                .isInstanceOf(NoPendingConfirmationException.class);
    }

    @Test
    void aQuestionCanBePausedAndResumedSeveralTimesInATurn() throws Exception {
        // EXIGENCE PO : l'outil est appelable plusieurs fois dans un même tour. La porte doit donc
        // supporter N pauses successives — une par callId, chacune tranchée à son tour.
        RunnerConfirmationGate gate = new RunnerConfirmationGate(5_000L);

        Future<AnswerOutcome> first = awaitAnswerAsync(gate, "q_a");
        gate.answerQuestions(userId, workspaceId, "q_a", "réponse A");
        assertThat(first.get(2, TimeUnit.SECONDS).content()).isEqualTo("réponse A");

        Future<AnswerOutcome> second = awaitAnswerAsync(gate, "q_b");
        gate.answerQuestions(userId, workspaceId, "q_b", "réponse B");
        assertThat(second.get(2, TimeUnit.SECONDS).content()).isEqualTo("réponse B");
    }

    @Test
    void aQuestionAlreadyPendingIsNotOverwritten() throws Exception {
        RunnerConfirmationGate gate = new RunnerConfirmationGate(5_000L);
        awaitAnswerAsync(gate, "q_dup");

        // Un second awaitAnswer sur le même identifiant échoue plutôt que d'écraser la question en cours.
        assertThat(gate.awaitAnswer(userId, workspaceId, "q_dup", () -> { }).status())
                .isEqualTo(AnswerOutcome.Status.FAILED);
    }

    @Test
    void anotherUserCannotAnswerMyQuestion() throws Exception {
        RunnerConfirmationGate gate = new RunnerConfirmationGate(400L);
        Future<AnswerOutcome> pending = awaitAnswerAsync(gate, "q_iso");

        assertThatThrownBy(
                () -> gate.answerQuestions(UUID.randomUUID(), workspaceId, "q_iso", "pas moi"))
                .isInstanceOf(NoPendingConfirmationException.class);
        assertThatThrownBy(
                () -> gate.answerQuestions(userId, UUID.randomUUID(), "q_iso", "pas ici"))
                .isInstanceOf(NoPendingConfirmationException.class);

        // La question reste non tranchée : elle finit en TIMEOUT, jamais répondue par un tiers.
        assertThat(pending.get(2, TimeUnit.SECONDS).status()).isEqualTo(AnswerOutcome.Status.TIMEOUT);
    }

    @Test
    void aQuestionAndAConfirmationNeverCrossResolve() throws Exception {
        // Le discriminant de genre : répondre « comme une autorisation » à une question (et l'inverse)
        // ne tranche jamais — la reprise reste sûre.
        RunnerConfirmationGate gate = new RunnerConfirmationGate(400L);
        Future<AnswerOutcome> question = awaitAnswerAsync(gate, "mixte");
        assertThatThrownBy(() -> gate.resolve(userId, workspaceId, "mixte", true, null))
                .isInstanceOf(NoPendingConfirmationException.class);
        assertThat(question.get(2, TimeUnit.SECONDS).status()).isEqualTo(AnswerOutcome.Status.TIMEOUT);

        Future<Outcome> confirm = awaitAsync(gate, "mixte2");
        assertThatThrownBy(() -> gate.answerQuestions(userId, workspaceId, "mixte2", "x"))
                .isInstanceOf(NoPendingConfirmationException.class);
        assertThat(confirm.get(2, TimeUnit.SECONDS).decision()).isEqualTo(Decision.TIMEOUT);
    }

    @Test
    void interruptingTheTurnReleasesAPendingQuestionAsInterrupted() throws Exception {
        RunnerConfirmationGate gate = new RunnerConfirmationGate(10_000L);
        Future<AnswerOutcome> pending = awaitAnswerAsync(gate, "q_int");

        assertThat(gate.cancelWorkspace(workspaceId)).isEqualTo(1);

        assertThat(pending.get(2, TimeUnit.SECONDS).status())
                .isEqualTo(AnswerOutcome.Status.INTERRUPTED);
    }
}
