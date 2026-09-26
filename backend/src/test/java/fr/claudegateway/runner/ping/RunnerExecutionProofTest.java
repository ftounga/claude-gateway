package fr.claudegateway.runner.ping;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;

/**
 * <b>Ce qui prouve qu'un poste exécute</b> (F-161 / SF-161-04).
 *
 * <p>Toute la subtilité tient en une phrase : une <b>erreur d'outil</b> est une preuve, une erreur
 * <b>de la gateway</b> n'en est pas une. La première vient de la machine — la trame a été reçue, le
 * projet résolu, un worker a répondu. La seconde décrit ce que la gateway constate toute seule.</p>
 */
class RunnerExecutionProofTest {

    private final RunnerExecutionProof proof = new RunnerExecutionProof();
    private final UUID hostId = UUID.randomUUID();

    private static RunnerCallResult ok() {
        return new RunnerCallResult(true, "contenu", false, null, 3L, null, null, null, "", false);
    }

    private static RunnerCallResult error(String code) {
        return RunnerCallResult.backendError(code, "peu importe");
    }

    @Test
    @DisplayName("une réponse réussie prouve l'exécution")
    void successProves() {
        assertThat(RunnerExecutionProof.provesExecution(ok())).isTrue();
    }

    @Test
    @DisplayName("une erreur D'OUTIL prouve l'exécution — c'est la chaîne qui est prouvée, pas le succès")
    void aToolErrorProves() {
        assertThat(RunnerExecutionProof.provesExecution(error("not_found"))).isTrue();
        assertThat(RunnerExecutionProof.provesExecution(error("io_error"))).isTrue();
        assertThat(RunnerExecutionProof.provesExecution(error("too_large"))).isTrue();
    }

    @Test
    @DisplayName("les codes PRODUITS PAR LA GATEWAY ne prouvent rien — personne n'a rien exécuté")
    void backendCodesProveNothing() {
        assertThat(RunnerExecutionProof.provesExecution(error(RunnerErrorCodes.RUNNER_UNAVAILABLE)))
                .isFalse();
        assertThat(RunnerExecutionProof.provesExecution(
                error(RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE))).isFalse();
        assertThat(RunnerExecutionProof.provesExecution(error(RunnerErrorCodes.RUNNER_TIMEOUT)))
                .isFalse();
        assertThat(RunnerExecutionProof.provesExecution(
                error(RunnerErrorCodes.RUNNER_PROTOCOL_ERROR))).isFalse();
        assertThat(RunnerExecutionProof.provesExecution(error(RunnerErrorCodes.INVALID_INPUT)))
                .isFalse();
    }

    @Test
    @DisplayName("`unsupported_tool` est AMBIGU : dans le doute, il ne prouve rien")
    void unsupportedToolIsAmbiguous() {
        assertThat(RunnerExecutionProof.provesExecution(error(RunnerErrorCodes.UNSUPPORTED_TOOL)))
                .as("la gateway l'émet quand la capacité n'est pas déclarée, le runner quand il ne "
                        + "connaît pas l'outil : au pire une sonde de plus, jamais un poste bloqué "
                        + "déclaré sain")
                .isFalse();
    }

    @Test
    @DisplayName("une issue nulle ou sans code ne prouve rien")
    void nothingProvesNothing() {
        assertThat(RunnerExecutionProof.provesExecution(null)).isFalse();
        assertThat(RunnerExecutionProof.provesExecution(
                new RunnerCallResult(false, "", false, null, 0L, null, null, null, "", false)))
                .isFalse();
    }

    @Test
    @DisplayName("la fenêtre de fraîcheur est respectée, et un poste jamais vu n'a aucune preuve")
    void theWindowIsHonoured() {
        assertThat(proof.provedWithin(hostId, Duration.ofMinutes(2)))
                .as("poste jamais vu : aucune preuve, donc la sonde est due")
                .isFalse();

        proof.noteProved(hostId);

        assertThat(proof.provedWithin(hostId, Duration.ofMinutes(2))).isTrue();
        assertThat(proof.provedWithin(hostId, Duration.ZERO))
                .as("une fenêtre nulle ne peut jamais dispenser de sonder")
                .isFalse();
        assertThat(proof.provedWithin(UUID.randomUUID(), Duration.ofMinutes(2)))
                .as("la preuve est POSTE PAR POSTE — sinon un poste vivant couvrirait un poste mort")
                .isFalse();
    }

    @Test
    @DisplayName("`note` ne retient que ce qui prouve quelque chose")
    void noteOnlyKeepsProofs() {
        proof.note(hostId, error(RunnerErrorCodes.RUNNER_TIMEOUT));
        assertThat(proof.provedWithin(hostId, Duration.ofMinutes(2))).isFalse();

        proof.note(hostId, ok());
        assertThat(proof.provedWithin(hostId, Duration.ofMinutes(2))).isTrue();

        proof.forget(hostId);
        assertThat(proof.provedWithin(hostId, Duration.ofMinutes(2))).isFalse();
    }

    @Test
    @DisplayName("un poste nul ne fait rien planter — la preuve n'est pas un contrôle d'accès")
    void nullHostIsHarmless() {
        proof.noteProved(null);
        proof.note(null, ok());
        assertThat(proof.provedWithin(null, Duration.ofMinutes(2))).isFalse();
    }
}
