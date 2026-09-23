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
    /** Cible d'un appel (F-48 / SF-48-01) : le poste, le projet, et son chemin sous la racine. */
    private final fr.claudegateway.runner.channel.RunnerTarget target =
            new fr.claudegateway.runner.channel.RunnerTarget(UUID.randomUUID(), UUID.randomUUID(),
                    "projet");

    private RunnerToolGateway gateway() {
        when(router.call(any(), anyString(), anyString(), any(), anyLong()))
                .thenReturn(new RunnerCallResult(true, "ok", false, null, 1L, null, null, null, "", false));
        return new RunnerToolGateway(router, objectMapper);
    }

    private JsonNode capturedInput(String expectedTool) {
        ArgumentCaptor<JsonNode> input = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<Long> timeout = ArgumentCaptor.forClass(Long.class);
        verify(router).call(org.mockito.ArgumentMatchers.eq(target), anyString(),
                org.mockito.ArgumentMatchers.eq(expectedTool), input.capture(), timeout.capture());
        assertThat(timeout.getValue()).isEqualTo(RunnerToolGateway.FILE_TOOL_TIMEOUT_MS);
        return input.getValue();
    }

    @Test
    void listFilesSendsAnEmptyInputWithTheContractTimeout() {
        gateway().listFiles(target, "toolu_1");

        assertThat(capturedInput("list_files").isObject()).isTrue();
    }

    @Test
    void readFileNormalisesThePathBeforeSendingIt() {
        gateway().readFile(target, "toolu_1", "./src//a.ts");

        assertThat(capturedInput("read_file").path("path").asText()).isEqualTo("src/a.ts");
    }

    @Test
    void readFileRefusesAPathThatLeavesTheRoot() {
        RunnerCallResult result = gateway().readFile(target, "toolu_1", "../etc/passwd");

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.INVALID_INPUT);
        verify(router, never()).call(any(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void readFileRefusesAnAbsolutePath() {
        RunnerCallResult result = gateway().readFile(target, "toolu_1", "/etc/passwd");

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.INVALID_INPUT);
        verify(router, never()).call(any(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void writeFileRefusesAContentBeyondTheContractBound() {
        String tooBig = "a".repeat(RunnerToolGateway.MAX_WRITE_BYTES + 1);

        RunnerCallResult result = gateway().writeFile(target, "toolu_1", "a.txt", tooBig);

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.INVALID_INPUT);
        verify(router, never()).call(any(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void writeFileSendsPathAndContent() {
        gateway().writeFile(target, "toolu_1", "src/a.ts", "const x = 1;");

        JsonNode input = capturedInput("write_file");
        assertThat(input.path("path").asText()).isEqualTo("src/a.ts");
        assertThat(input.path("content").asText()).isEqualTo("const x = 1;");
    }

    @Test
    void writeFileBytesSendsPathContentAndOffsetWithTheDepositTimeout() {
        gateway().writeFileBytes(target, "toolu_1", "./.atelier//entrees/x.bin", "QUJD", 512L);

        ArgumentCaptor<JsonNode> input = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<Long> timeout = ArgumentCaptor.forClass(Long.class);
        verify(router).call(org.mockito.ArgumentMatchers.eq(target), anyString(),
                org.mockito.ArgumentMatchers.eq("write_file_bytes"), input.capture(), timeout.capture());
        assertThat(timeout.getValue()).isEqualTo(RunnerToolGateway.DEPOSIT_TIMEOUT_MS);
        assertThat(input.getValue().path("path").asText()).isEqualTo(".atelier/entrees/x.bin");
        assertThat(input.getValue().path("content").asText()).isEqualTo("QUJD");
        assertThat(input.getValue().path("offset").asLong()).isEqualTo(512L);
    }

    @Test
    void writeFileBytesRefusesAPathThatLeavesTheRoot() {
        RunnerCallResult result = gateway().writeFileBytes(target, "toolu_1", "../etc/passwd", "QUJD", 0L);

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.INVALID_INPUT);
        verify(router, never()).call(any(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void searchFilesSendsASingleQuery() {
        gateway().searchFiles(target, "toolu_1", "  TODO  ");

        assertThat(capturedInput("search_files").path("query").asText()).isEqualTo("TODO");
    }

    @Test
    void searchFilesRefusesAnEmptyQuery() {
        RunnerCallResult result = gateway().searchFiles(target, "toolu_1", "   ");

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
        verify(router).call(org.mockito.ArgumentMatchers.eq(target), anyString(),
                org.mockito.ArgumentMatchers.eq("bash"), input.capture(), timeout.capture(), any());
        assertThat(timeout.getValue()).isEqualTo(expectedTimeoutMs);
        return input.getValue();
    }

    @Test
    void bashSendsTheCommandWithTheContractTimeout() {
        bashGateway(bashOk()).bash(target, "toolu_1", "  npm test  ", null,
                RunnerToolGateway.BASH_TIMEOUT_MS, null);

        JsonNode input = capturedBashInput(RunnerToolGateway.BASH_TIMEOUT_MS);
        assertThat(input.path("command").asText()).isEqualTo("npm test");
        assertThat(input.has("cwd")).isFalse();
    }

    @Test
    void bashNormalisesTheWorkingDirectoryBeforeSending() {
        bashGateway(bashOk()).bash(target, "toolu_1", "ls", "./src\\app/",
                RunnerToolGateway.BASH_TIMEOUT_MS, null);

        assertThat(capturedBashInput(RunnerToolGateway.BASH_TIMEOUT_MS).path("cwd").asText())
                .isEqualTo("src/app");
    }

    @Test
    void bashRefusesAnEscapingWorkingDirectoryBeforeSending() {
        RunnerCallResult result = bashGateway(bashOk()).bash(target, "toolu_1", "ls",
                "../ailleurs", RunnerToolGateway.BASH_TIMEOUT_MS, null);

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.INVALID_INPUT);
        verify(router, never()).call(any(), anyString(), anyString(), any(), anyLong(), any());
    }

    @Test
    void bashRefusesAnEmptyOrOversizedCommandBeforeSending() {
        RunnerToolGateway gateway = bashGateway(bashOk());

        assertThat(gateway.bash(target, "toolu_1", "   ", null, 1_000L, null).errorCode())
                .isEqualTo(RunnerErrorCodes.INVALID_INPUT);
        assertThat(gateway.bash(target, "toolu_2",
                "x".repeat(RunnerToolGateway.MAX_COMMAND_CHARS + 1), null, 1_000L, null).errorCode())
                .isEqualTo(RunnerErrorCodes.INVALID_INPUT);
        verify(router, never()).call(any(), anyString(), anyString(), any(), anyLong(), any());
    }

    @Test
    void bashClampsTheTimeoutBetweenTheFloorAndTheWidenedCeiling() {
        bashGateway(bashOk()).bash(target, "toolu_1", "ls", null, 10L, null);
        capturedBashInput(RunnerToolGateway.MIN_BASH_TIMEOUT_MS);

        // F-121 / SF-121-07 : le plafond est élargi à 10 minutes (au lieu de 120 s).
        org.mockito.Mockito.reset(router);
        bashGateway(bashOk()).bash(target, "toolu_2", "ls", null, 3_600_000L, null);
        capturedBashInput(RunnerToolGateway.MAX_BASH_TIMEOUT_MS);
    }

    // -------------------------------------------------- bash en arrière-plan (F-121 / SF-121-07)

    @Test
    void bashBackgroundFlagsTheInputAndUsesAShortTimeout() {
        bashGateway(bashOk()).bashBackground(target, "toolu_1", "  npm run dev  ", null);

        JsonNode input = capturedBashInput(RunnerToolGateway.FILE_TOOL_TIMEOUT_MS);
        assertThat(input.path("command").asText()).isEqualTo("npm run dev");
        assertThat(input.path("background").asBoolean()).isTrue();
    }

    @Test
    void bashBackgroundRefusesAnEmptyCommandBeforeSending() {
        RunnerCallResult result = bashGateway(bashOk()).bashBackground(target, "toolu_1", "   ", null);

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.INVALID_INPUT);
        verify(router, never()).call(any(), anyString(), anyString(), any(), anyLong(), any());
    }

    @Test
    void bashOutputSendsTheIdentifier() {
        when(router.call(any(), anyString(), anyString(), any(), anyLong(), any()))
                .thenReturn(new RunnerCallResult(true, "sortie", false, null, 1L, null, null, null, "", false));
        new RunnerToolGateway(router, objectMapper).bashOutput(target, "toolu_1", "bash_1");

        ArgumentCaptor<JsonNode> input = ArgumentCaptor.forClass(JsonNode.class);
        verify(router).call(org.mockito.ArgumentMatchers.eq(target), anyString(),
                org.mockito.ArgumentMatchers.eq("bash_output"), input.capture(), anyLong(), any());
        assertThat(input.getValue().path("bash_id").asText()).isEqualTo("bash_1");
    }

    @Test
    void killShellSendsTheIdentifier() {
        when(router.call(any(), anyString(), anyString(), any(), anyLong(), any()))
                .thenReturn(new RunnerCallResult(true, "arrêtée", false, null, 1L, null, null, null, "", false));
        new RunnerToolGateway(router, objectMapper).killShell(target, "toolu_1", "bash_2");

        ArgumentCaptor<JsonNode> input = ArgumentCaptor.forClass(JsonNode.class);
        verify(router).call(org.mockito.ArgumentMatchers.eq(target), anyString(),
                org.mockito.ArgumentMatchers.eq("kill_shell"), input.capture(), anyLong(), any());
        assertThat(input.getValue().path("shell_id").asText()).isEqualTo("bash_2");
    }

    @Test
    void bashOutputAndKillShellRefuseABlankIdentifier() {
        RunnerToolGateway gateway = new RunnerToolGateway(router, objectMapper);
        assertThat(gateway.bashOutput(target, "toolu_1", "  ").errorCode())
                .isEqualTo(RunnerErrorCodes.INVALID_INPUT);
        assertThat(gateway.killShell(target, "toolu_2", "  ").errorCode())
                .isEqualTo(RunnerErrorCodes.INVALID_INPUT);
        verify(router, never()).call(any(), anyString(), anyString(), any(), anyLong(), any());
    }

    @Test
    void anUnsupportedBashSaysHowToEnableItOnTheMachine() {
        RunnerCallResult refused = RunnerCallResult.backendError(RunnerErrorCodes.UNSUPPORTED_TOOL);

        RunnerCallResult result = bashGateway(refused).bash(target, "toolu_1", "ls", null,
                RunnerToolGateway.BASH_TIMEOUT_MS, null);

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.UNSUPPORTED_TOOL);
        // Le drapeau nommé doit être celui qui AGIT (SF-38-26, D4) : --allow-bash n'a plus d'effet
        // depuis SF-38-19, et le conseiller renvoyait l'utilisateur vers une relance sans effet.
        assertThat(result.errorMessage()).contains("--no-bash");
        assertThat(result.errorMessage()).doesNotContain("--allow-bash");
    }

    private static RunnerCallResult bashOk() {
        return new RunnerCallResult(true, "", false, 0, 1L, null, null, null, "", false);
    }

    /** F-108 / SF-108-03 : navigation, appel de page et téléchargement ne tiennent pas en 20 s. */
    @Test
    void the_file_tools_get_the_long_teams_timeout() {
        assertThat(RunnerToolGateway.teamsTimeoutFor("teams_list_files"))
                .isEqualTo(RunnerToolGateway.TEAMS_FILES_TIMEOUT_MS);
        assertThat(RunnerToolGateway.teamsTimeoutFor("teams_read_file"))
                .isEqualTo(RunnerToolGateway.TEAMS_FILES_TIMEOUT_MS);
        assertThat(RunnerToolGateway.teamsTimeoutFor("teams_meeting_recording"))
                .isEqualTo(RunnerToolGateway.TEAMS_FILES_TIMEOUT_MS);
        assertThat(RunnerToolGateway.teamsTimeoutFor("teams_upload_file"))
                .isEqualTo(RunnerToolGateway.TEAMS_UPLOAD_TIMEOUT_MS);
        assertThat(RunnerToolGateway.teamsTimeoutFor("teams_replace_version"))
                .isEqualTo(RunnerToolGateway.TEAMS_UPLOAD_TIMEOUT_MS);
        // F-108 / SF-108-06 : une copie prend le long délai d'envoi ; lire un .docx local, le délai fichiers.
        assertThat(RunnerToolGateway.teamsTimeoutFor("teams_copy"))
                .isEqualTo(RunnerToolGateway.TEAMS_UPLOAD_TIMEOUT_MS);
        assertThat(RunnerToolGateway.teamsTimeoutFor("teams_read_docx"))
                .isEqualTo(RunnerToolGateway.TEAMS_FILES_TIMEOUT_MS);
        assertThat(RunnerToolGateway.teamsTimeoutFor("teams_delete"))
                .isEqualTo(RunnerToolGateway.TEAMS_FILES_TIMEOUT_MS);
        assertThat(RunnerToolGateway.teamsTimeoutFor("teams_status"))
                .isEqualTo(RunnerToolGateway.TEAMS_TOOL_TIMEOUT_MS);
    }

    // ------------------------------------------------------------- grep / glob (F-121 / SF-121-01)

    @Test
    void grepRelaysBoundedParamsAndNormalisesTheScope() {
        com.fasterxml.jackson.databind.node.ObjectNode input = objectMapper.createObjectNode();
        input.put("pattern", "const\\s+\\w+");
        input.put("path", "./src//");
        input.put("include", "*.ts");
        input.put("ignore_case", true);
        input.put("output_mode", "count");
        input.put("context", 2);

        gateway().grep(target, "toolu_1", input);

        JsonNode sent = capturedInput("grep");
        assertThat(sent.path("pattern").asText()).isEqualTo("const\\s+\\w+");
        assertThat(sent.path("path").asText()).isEqualTo("src"); // normalisé en relatif (D6)
        assertThat(sent.path("include").asText()).isEqualTo("*.ts");
        assertThat(sent.path("ignore_case").asBoolean()).isTrue();
        assertThat(sent.path("output_mode").asText()).isEqualTo("count");
        assertThat(sent.path("context").asInt()).isEqualTo(2);
    }

    @Test
    void grepRefusesAnEmptyPatternBeforeEmission() {
        RunnerCallResult result = new RunnerToolGateway(router, objectMapper)
                .grep(target, "toolu_1", objectMapper.createObjectNode());

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.INVALID_INPUT);
        verify(router, never()).call(any(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void grepRefusesAnEscapingScopeBeforeEmission() {
        com.fasterxml.jackson.databind.node.ObjectNode input = objectMapper.createObjectNode();
        input.put("pattern", "x");
        input.put("path", "../autre");

        RunnerCallResult result = new RunnerToolGateway(router, objectMapper).grep(target, "toolu_1", input);

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.INVALID_INPUT);
        verify(router, never()).call(any(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void globRelaysThePatternWithTheContractTimeout() {
        com.fasterxml.jackson.databind.node.ObjectNode input = objectMapper.createObjectNode();
        input.put("pattern", "**/*.java");

        gateway().glob(target, "toolu_1", input);

        assertThat(capturedInput("glob").path("pattern").asText()).isEqualTo("**/*.java");
    }

    // ------------------------------------------------------ worktrees `task` (F-150 / SF-150-01)

    private JsonNode capturedWorktreeInput(String expectedTool) {
        ArgumentCaptor<JsonNode> input = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<Long> timeout = ArgumentCaptor.forClass(Long.class);
        verify(router).call(org.mockito.ArgumentMatchers.eq(target), anyString(),
                org.mockito.ArgumentMatchers.eq(expectedTool), input.capture(), timeout.capture());
        assertThat(timeout.getValue()).isEqualTo(RunnerToolGateway.WORKTREE_TIMEOUT_MS);
        return input.getValue();
    }

    @Test
    void worktreeCreateSendsTheTaskIdWithTheWorktreeTimeout() {
        gateway().worktreeCreate(target, "toolu_1", "task-42");

        assertThat(capturedWorktreeInput("worktree_create").path("taskId").asText()).isEqualTo("task-42");
    }

    @Test
    void worktreeRemoveSendsTheTaskId() {
        gateway().worktreeRemove(target, "toolu_1", "task-42");

        assertThat(capturedWorktreeInput("worktree_remove").path("taskId").asText()).isEqualTo("task-42");
    }

    @Test
    void worktreeReapForwardsOnlyValidTaskIdsToKeep() {
        gateway().worktreeReap(target, "toolu_1", java.util.List.of("garde", "in valide", "autre"));

        JsonNode keep = capturedWorktreeInput("worktree_reap").path("keep");
        assertThat(keep.isArray()).isTrue();
        assertThat(keep).hasSize(2);
        assertThat(keep.get(0).asText()).isEqualTo("garde");
        assertThat(keep.get(1).asText()).isEqualTo("autre");
    }

    @Test
    void worktreeFinalizeSendsTaskIdAndMessage() {
        gateway().worktreeFinalize(target, "toolu_1", "task-42", "mon message");

        JsonNode input = capturedWorktreeInput("worktree_finalize");
        assertThat(input.path("taskId").asText()).isEqualTo("task-42");
        assertThat(input.path("message").asText()).isEqualTo("mon message");
    }

    @Test
    void worktreeCreateRefusesAMalformedTaskIdBeforeEmission() {
        RunnerCallResult result = gateway().worktreeCreate(target, "toolu_1", "pas/valide");

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.INVALID_INPUT);
        verify(router, never()).call(any(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void worktreeCreateSurfacesUnsupportedToolFromAnOlderRunner() {
        when(router.call(any(), anyString(), org.mockito.ArgumentMatchers.eq("worktree_create"),
                any(), anyLong()))
                .thenReturn(RunnerCallResult.backendError(RunnerErrorCodes.UNSUPPORTED_TOOL));

        RunnerCallResult result = new RunnerToolGateway(router, objectMapper)
                .worktreeCreate(target, "toolu_1", "task-42");

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.UNSUPPORTED_TOOL);
    }
}
