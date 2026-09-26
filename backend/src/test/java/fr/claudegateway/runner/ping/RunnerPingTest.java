package fr.claudegateway.runner.ping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.relay.RunnerCallRouter;

/**
 * <b>Le ping conditionnel</b> (F-161 / SF-161-04).
 *
 * <p>Ce que ces tests tiennent, et qui est toute la feature :</p>
 * <ul>
 *   <li>il est <b>conditionnel</b> — une session qui vient d'exécuter ne paie <b>aucune</b>
 *       latence : c'est la condition posée par le cadrage §6, et sans elle on aurait livré le ping
 *       systématique qu'il refuse ;</li>
 *   <li>il ne ferme que sur un <b>silence avéré</b> — le doute laisse passer, comme la porte ;</li>
 *   <li>il fait <b>exécuter</b> le poste au lieu de croire ce qu'il déclare.</li>
 * </ul>
 */
class RunnerPingTest {

    private final RunnerCallRouter router = mock(RunnerCallRouter.class);
    private final RunnerExecutionProof proof = new RunnerExecutionProof();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID hostId = UUID.randomUUID();
    private final RunnerTarget target = new RunnerTarget(hostId, UUID.randomUUID(), "projet");

    private RunnerPing ping(boolean enabled) {
        return new RunnerPing(router, proof, objectMapper, enabled, Duration.ofMinutes(2), 1_200L);
    }

    private void answers(RunnerCallResult result) {
        when(router.call(any(), anyString(), anyString(), any(), anyLong())).thenReturn(result);
    }

    private static RunnerCallResult toolError(String code) {
        return RunnerCallResult.backendError(code, "peu importe");
    }

    @Test
    @DisplayName("exécution déjà prouvée : AUCUNE trame ne part — la sonde ne coûte rien à une session active")
    void aRecentProofSkipsTheProbe() {
        proof.noteProved(hostId);

        assertThat(ping(true).probe(target, "CAGIP").mute()).isFalse();

        verifyNoInteractions(router);
    }

    @Test
    @DisplayName("sans preuve : la sonde part, et une réponse du runner ouvre le tour ET pose la preuve")
    void withoutProofItProbesAndRemembers() {
        answers(toolError("not_found"));

        assertThat(ping(true).probe(target, "CAGIP").mute()).isFalse();

        verify(router).call(eq(target), anyString(), eq("read_file"), any(), eq(1_200L));
        assertThat(proof.provedWithin(hostId, Duration.ofMinutes(2)))
                .as("le tour suivant ne doit pas re-sonder")
                .isTrue();
    }

    @Test
    @DisplayName("poste MUET (délai dépassé) : la sonde ferme, et le message dit le poste et quoi faire")
    void silenceCloses() {
        answers(toolError(RunnerErrorCodes.RUNNER_TIMEOUT));

        RunnerPingVerdict verdict = ping(true).probe(target, "CAGIP");

        assertThat(verdict.mute()).isTrue();
        assertThat(verdict.reason())
                .contains("CAGIP")
                .contains("n'exécute plus rien")
                .contains("rien n'a été dépensé");
        assertThat(proof.provedWithin(hostId, Duration.ofMinutes(2)))
                .as("un silence ne prouve surtout pas l'exécution")
                .isFalse();
    }

    @Test
    @DisplayName("canal disparu : la sonde ferme aussi — le battement mentait")
    void aVanishedChannelCloses() {
        answers(toolError(RunnerErrorCodes.RUNNER_UNAVAILABLE));
        assertThat(ping(true).probe(target, "CAGIP").mute()).isTrue();

        answers(toolError(RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE));
        assertThat(ping(true).probe(target, "CAGIP").mute()).isTrue();
    }

    @Test
    @DisplayName("le DOUTE laisse passer : une réponse illisible ne dit rien du poste")
    void doubtOpens() {
        answers(toolError(RunnerErrorCodes.RUNNER_PROTOCOL_ERROR));
        assertThat(ping(true).probe(target, "CAGIP").mute())
                .as("une porte qui ferme faute de savoir est pire que pas de porte")
                .isFalse();

        answers(toolError(RunnerErrorCodes.UNSUPPORTED_TOOL));
        assertThat(ping(true).probe(target, "CAGIP").mute()).isFalse();
    }

    @Test
    @DisplayName("une panne de la sonde elle-même laisse passer le tour")
    void ourOwnFailureNeverCloses() {
        when(router.call(any(), anyString(), anyString(), any(), anyLong()))
                .thenThrow(new IllegalStateException("relais cassé"));

        assertThat(ping(true).probe(target, "CAGIP").mute()).isFalse();
    }

    @Test
    @DisplayName("débranché : aucune trame, quel que soit l'état du poste")
    void disabledProbesNothing() {
        assertThat(ping(false).probe(target, "CAGIP").mute()).isFalse();
        verifyNoInteractions(router);
    }

    @Test
    @DisplayName("sans poste, rien à sonder")
    void noHostNoProbe() {
        assertThat(ping(true).probe(new RunnerTarget(null, UUID.randomUUID(), ""), "x").mute())
                .isFalse();
        assertThat(ping(true).probe(null, "x").mute()).isFalse();
        verifyNoInteractions(router);
    }

    @Test
    @DisplayName("le chemin sondé est ALÉATOIRE : rien n'est lu, rien n'est écrit")
    void theProbedPathCannotExist() {
        answers(toolError("not_found"));
        ArgumentCaptor<JsonNode> input = ArgumentCaptor.forClass(JsonNode.class);

        ping(true).probe(target, "CAGIP");
        proof.forget(hostId);
        ping(true).probe(target, "CAGIP");

        verify(router, org.mockito.Mockito.times(2))
                .call(any(), anyString(), anyString(), input.capture(), anyLong());
        String first = input.getAllValues().get(0).path("path").asText();
        String second = input.getAllValues().get(1).path("path").asText();
        assertThat(first).startsWith(RunnerPing.PROBE_PATH_PREFIX);
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("la fenêtre est celle de la configuration : une fenêtre courte re-sonde")
    void theWindowComesFromConfiguration() {
        answers(toolError("not_found"));
        RunnerPing shortWindow =
                new RunnerPing(router, proof, objectMapper, true, Duration.ZERO, 1_200L);

        shortWindow.probe(target, "CAGIP");
        shortWindow.probe(target, "CAGIP");

        verify(router, org.mockito.Mockito.times(2))
                .call(any(), anyString(), anyString(), any(), anyLong());
        verify(router, never()).call(any(), anyString(), anyString(), any(), anyLong(), any());
    }
}
