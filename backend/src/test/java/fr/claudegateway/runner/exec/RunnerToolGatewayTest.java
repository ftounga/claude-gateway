package fr.claudegateway.runner.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.relay.RunnerCallRouter;

/**
 * Tests de la façade des outils fichiers du runner (F-38 / SF-38-05). Elle a une seule
 * responsabilité propre : refuser <b>avant émission</b> ce qui n'a aucune raison de traverser le
 * réseau, et poser les délais du contrat (§2.2).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RunnerToolGatewayTest {

    @Mock
    private RunnerCallRouter router;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID workspaceId = UUID.randomUUID();

    private RunnerToolGateway gateway() {
        when(router.call(any(), anyString(), anyString(), any(), anyLong()))
                .thenReturn(new RunnerCallResult(true, "ok", false, null, 1L, null, null, null, "", false));
        return new RunnerToolGateway(router, objectMapper);
    }

    private JsonNode capturedInput(String expectedTool) {
        ArgumentCaptor<JsonNode> input = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<Long> timeout = ArgumentCaptor.forClass(Long.class);
        verify(router).call(org.mockito.ArgumentMatchers.eq(workspaceId), anyString(),
                org.mockito.ArgumentMatchers.eq(expectedTool), input.capture(), timeout.capture());
        assertThat(timeout.getValue()).isEqualTo(RunnerToolGateway.FILE_TOOL_TIMEOUT_MS);
        return input.getValue();
    }

    @Test
    void listFilesSendsAnEmptyInputWithTheContractTimeout() {
        gateway().listFiles(workspaceId, "toolu_1");

        assertThat(capturedInput("list_files").isObject()).isTrue();
    }

    @Test
    void readFileNormalisesThePathBeforeSendingIt() {
        gateway().readFile(workspaceId, "toolu_1", "./src//a.ts");

        assertThat(capturedInput("read_file").path("path").asText()).isEqualTo("src/a.ts");
    }

    @Test
    void readFileRefusesAPathThatLeavesTheRoot() {
        RunnerCallResult result = gateway().readFile(workspaceId, "toolu_1", "../etc/passwd");

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.INVALID_INPUT);
        verify(router, never()).call(any(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void readFileRefusesAnAbsolutePath() {
        RunnerCallResult result = gateway().readFile(workspaceId, "toolu_1", "/etc/passwd");

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.INVALID_INPUT);
        verify(router, never()).call(any(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void writeFileRefusesAContentBeyondTheContractBound() {
        String tooBig = "a".repeat(RunnerToolGateway.MAX_WRITE_BYTES + 1);

        RunnerCallResult result = gateway().writeFile(workspaceId, "toolu_1", "a.txt", tooBig);

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.INVALID_INPUT);
        verify(router, never()).call(any(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void writeFileSendsPathAndContent() {
        gateway().writeFile(workspaceId, "toolu_1", "src/a.ts", "const x = 1;");

        JsonNode input = capturedInput("write_file");
        assertThat(input.path("path").asText()).isEqualTo("src/a.ts");
        assertThat(input.path("content").asText()).isEqualTo("const x = 1;");
    }

    @Test
    void searchFilesSendsASingleQuery() {
        gateway().searchFiles(workspaceId, "toolu_1", "  TODO  ");

        assertThat(capturedInput("search_files").path("query").asText()).isEqualTo("TODO");
    }

    @Test
    void searchFilesRefusesAnEmptyQuery() {
        RunnerCallResult result = gateway().searchFiles(workspaceId, "toolu_1", "   ");

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.INVALID_INPUT);
        verify(router, never()).call(any(), anyString(), anyString(), any(), anyLong());
    }

    // -------------------------------------------------------------- bash (SF-38-07)

    /** Passerelle dont l'appel {@code bash} (6 arguments, avec relais de flux) est stubé. */
    private RunnerToolGateway bashGateway(RunnerCallResult response) {
        when(router.call(any(), anyString(), anyString(), any(), anyLong(), any()))
                .thenReturn(response);
        return new RunnerToolGateway(router, objectMapper);
    }

    private JsonNode capturedBashInput(long expectedTimeoutMs) {
        ArgumentCaptor<JsonNode> input = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<Long> timeout = ArgumentCaptor.forClass(Long.class);
        verify(router).call(org.mockito.ArgumentMatchers.eq(workspaceId), anyString(),
                org.mockito.ArgumentMatchers.eq("bash"), input.capture(), timeout.capture(), any());
        assertThat(timeout.getValue()).isEqualTo(expectedTimeoutMs);
        return input.getValue();
    }

    @Test
    void bashSendsTheCommandWithTheContractTimeout() {
        bashGateway(bashOk()).bash(workspaceId, "toolu_1", "  npm test  ", null,
                RunnerToolGateway.BASH_TIMEOUT_MS, null);

        JsonNode input = capturedBashInput(RunnerToolGateway.BASH_TIMEOUT_MS);
        assertThat(input.path("command").asText()).isEqualTo("npm test");
        assertThat(input.has("cwd")).isFalse();
    }

    @Test
    void bashNormalisesTheWorkingDirectoryBeforeSending() {
        bashGateway(bashOk()).bash(workspaceId, "toolu_1", "ls", "./src\\app/",
                RunnerToolGateway.BASH_TIMEOUT_MS, null);

        assertThat(capturedBashInput(RunnerToolGateway.BASH_TIMEOUT_MS).path("cwd").asText())
                .isEqualTo("src/app");
    }

    @Test
    void bashRefusesAnEscapingWorkingDirectoryBeforeSending() {
        RunnerCallResult result = bashGateway(bashOk()).bash(workspaceId, "toolu_1", "ls",
                "../ailleurs", RunnerToolGateway.BASH_TIMEOUT_MS, null);

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.INVALID_INPUT);
        verify(router, never()).call(any(), anyString(), anyString(), any(), anyLong(), any());
    }

    @Test
    void bashRefusesAnEmptyOrOversizedCommandBeforeSending() {
        RunnerToolGateway gateway = bashGateway(bashOk());

        assertThat(gateway.bash(workspaceId, "toolu_1", "   ", null, 1_000L, null).errorCode())
                .isEqualTo(RunnerErrorCodes.INVALID_INPUT);
        assertThat(gateway.bash(workspaceId, "toolu_2",
                "x".repeat(RunnerToolGateway.MAX_COMMAND_CHARS + 1), null, 1_000L, null).errorCode())
                .isEqualTo(RunnerErrorCodes.INVALID_INPUT);
        verify(router, never()).call(any(), anyString(), anyString(), any(), anyLong(), any());
    }

    @Test
    void bashClampsTheTimeoutBetweenTheFloorAndTheContractCeiling() {
        bashGateway(bashOk()).bash(workspaceId, "toolu_1", "ls", null, 10L, null);
        capturedBashInput(RunnerToolGateway.MIN_BASH_TIMEOUT_MS);

        org.mockito.Mockito.reset(router);
        bashGateway(bashOk()).bash(workspaceId, "toolu_2", "ls", null, 3_600_000L, null);
        capturedBashInput(RunnerToolGateway.BASH_TIMEOUT_MS);
    }

    @Test
    void anUnsupportedBashSaysHowToEnableItOnTheMachine() {
        RunnerCallResult refused = RunnerCallResult.backendError(RunnerErrorCodes.UNSUPPORTED_TOOL);

        RunnerCallResult result = bashGateway(refused).bash(workspaceId, "toolu_1", "ls", null,
                RunnerToolGateway.BASH_TIMEOUT_MS, null);

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.UNSUPPORTED_TOOL);
        assertThat(result.errorMessage()).contains("--allow-bash");
    }

    private static RunnerCallResult bashOk() {
        return new RunnerCallResult(true, "", false, 0, 1L, null, null, null, "", false);
    }
}
