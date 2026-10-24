package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Captures de preuve (F-212 / SF-212-01) : la session {@code proof_run} sous un shell POSIX réel. */
@DisabledOnOs(OS.WINDOWS)
class ProofRunTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ShellElection POSIX_SHELL =
            ShellElection.elect(OperatingSystem.LINUX, System.getenv(), Files::isExecutable);

    @TempDir
    Path root;

    private BashTool bash(boolean enabled) {
        return new BashTool(new PathResolver(root), enabled, POSIX_SHELL);
    }

    private static ObjectNode input(String... commands) {
        ObjectNode input = MAPPER.createObjectNode();
        ArrayNode list = input.putArray("commands");
        for (String command : commands) {
            list.add(command);
        }
        return input;
    }

    private JsonNode run(String... commands) throws Exception {
        ToolOutcome outcome = bash(true).proofRun(input(commands), ToolContext.none());
        assertTrue(outcome.ok(), () -> outcome.errorCode() + " " + outcome.errorMessage());
        return MAPPER.readTree(outcome.content());
    }

    @Test
    void lesCommandesPartagentLeMemeShellDossierEtVariables() throws Exception {
        Files.createDirectories(root.resolve("sous"));
        JsonNode report = run("cd sous", "export PREUVE=ok", "pwd; echo \"v=$PREUVE\"");
        JsonNode third = report.path("commands").get(2);
        assertEquals("done", third.path("status").asText());
        assertTrue(third.path("stdout").asText().endsWith("sous\nv=ok\n"), third.path("stdout").asText());
        assertEquals(root.resolve("sous").toRealPath().toString(), Path.of(third.path("cwd").asText()).toRealPath().toString());
        assertEquals("posix", report.path("shell").asText());
        assertEquals(root.toAbsolutePath().normalize().toString(), report.path("cwd").asText());
        assertEquals("linux", report.path("os").asText());
        assertFalse(report.path("user").asText().isBlank());
        assertFalse(report.path("host").asText().contains("."));
    }

    @Test
    void releveParCommandeSortiesDistinctesCodeEtDuree() throws Exception {
        JsonNode report = run("echo bonjour", "echo oups >&2; exit_code() { return 3; }; exit_code",
                "printf 'sans fin de ligne'");
        JsonNode first = report.path("commands").get(0);
        assertEquals(1, first.path("index").asInt());
        assertEquals("echo bonjour", first.path("command").asText());
        assertEquals("bonjour\n", first.path("stdout").asText());
        assertEquals("", first.path("stderr").asText());
        assertEquals(0, first.path("exitCode").asInt());
        assertTrue(first.path("durationMs").asLong() >= 0);
        assertFalse(first.path("startedAt").asText().isBlank());

        JsonNode second = report.path("commands").get(1);
        assertEquals("", second.path("stdout").asText());
        assertEquals("oups\n", second.path("stderr").asText());
        assertEquals(3, second.path("exitCode").asInt());

        assertEquals("sans fin de ligne", report.path("commands").get(2).path("stdout").asText());
    }

    @Test
    void unExitTermineLaSessionLesSuivantesNeSontPasExecutees() throws Exception {
        JsonNode report = run("echo avant", "exit 4", "echo jamais");
        JsonNode commands = report.path("commands");
        assertEquals("done", commands.get(0).path("status").asText());
        assertEquals("interrupted", commands.get(1).path("status").asText());
        assertEquals(4, commands.get(1).path("exitCode").asInt());
        assertEquals("not_run", commands.get(2).path("status").asText());
        assertTrue(commands.get(2).path("exitCode").isNull());
    }

    @Test
    void uneSortieQuiImiteUneBorneSansLeJetonResteDuContenu() throws Exception {
        JsonNode report = run("echo '@@CGP-faux:E:1:0:/'", "echo suite");
        assertEquals("@@CGP-faux:E:1:0:/\n", report.path("commands").get(0).path("stdout").asText());
        assertEquals("suite\n", report.path("commands").get(1).path("stdout").asText());
    }

    @Test
    void uneSortieLongueEstBorneeTeteEtFinAvecMention() throws Exception {
        JsonNode report = run("i=0; while [ $i -lt 4000 ]; do echo \"ligne $i ...........\"; i=$((i+1)); done");
        JsonNode first = report.path("commands").get(0);
        String stdout = first.path("stdout").asText();
        assertTrue(first.path("truncated").asBoolean());
        assertTrue(stdout.startsWith("ligne 0 "));
        assertTrue(stdout.endsWith("ligne 3999 ...........\n"), stdout.substring(stdout.length() - 40));
        assertTrue(stdout.contains("caractères omis"));
        assertTrue(stdout.length() <= ProofRun.HEAD_CHARS + ProofRun.TAIL_CHARS + 80);
    }

    @Test
    void entreesInvalidesRefusees() {
        BashTool bash = bash(true);
        assertEquals("invalid_input", bash.proofRun(MAPPER.createObjectNode(), ToolContext.none()).errorCode());
        assertEquals("invalid_input", bash.proofRun(input(), ToolContext.none()).errorCode());
        assertEquals("invalid_input",
                bash.proofRun(input("a", "b", "c", "d", "e", "f", "g", "h", "i"), ToolContext.none()).errorCode());
        assertEquals("invalid_input", bash.proofRun(input("  "), ToolContext.none()).errorCode());
        assertEquals("invalid_input",
                bash.proofRun(input("x".repeat(8_193)), ToolContext.none()).errorCode());
    }

    @Test
    void refuseSousNoBash() {
        assertEquals("unsupported_tool", bash(false).proofRun(input("echo x"), ToolContext.none()).errorCode());
    }

    @Test
    void leRouteurAiguilleProofRunEtAnnonceLaCapacite() throws Exception {
        PathResolver paths = new PathResolver(root);
        ToolRouter router = new ToolRouter(new FileTools(paths), new BashTool(paths, true, POSIX_SHELL));
        assertTrue(router.capabilities().contains(ProofRun.CAPABILITY));
        ToolOutcome outcome = router.execute(ProofRun.TOOL, input("echo routé"), ToolContext.none());
        assertTrue(outcome.ok());
        assertEquals("routé\n", MAPPER.readTree(outcome.content()).path("commands").get(0).path("stdout").asText());

        ToolRouter noBash = new ToolRouter(new FileTools(paths), new BashTool(paths, false, POSIX_SHELL));
        assertFalse(noBash.capabilities().contains(ProofRun.CAPABILITY));
    }

    @Test
    void scriptsPowerShellEtCmdPortentLesBornesEtLeDotSource() {
        String ps = ProofRun.script(ShellElection.Kind.POWERSHELL, "abc", List.of("Set-Location sous", "Get-Date"));
        assertTrue(ps.contains("[Console]::Out.WriteLine('@@CGP-abc:B:1')"));
        assertTrue(ps.contains("try { . {\nSet-Location sous\n}"));
        assertTrue(ps.contains("'@@CGP-abc:E:2:' + $__cgpS + ':' + (Get-Location).Path"));
        String cmd = ProofRun.script(ShellElection.Kind.CMD, "abc", List.of("dir"));
        assertTrue(cmd.startsWith("@echo off\r\n"));
        assertTrue(cmd.contains("echo @@CGP-abc:E:1:%ERRORLEVEL%:%CD%\r\n"));
        assertTrue(cmd.contains("echo @@CGP-abc:B:1 1>&2\r\n"));
    }

    @Test
    void leJetonChangeAChaqueSession() {
        assertFalse(ProofRun.newNonce().equals(ProofRun.newNonce()));
    }

    @Test
    void leShellDeLUtilisateurExecuteLaSessionQuandIlParlePosix() {
        ShellElection elected = POSIX_SHELL;
        ProofHost zsh = ProofHost.detect(OperatingSystem.MACOS, java.util.Map.of("SHELL", "/bin/zsh"), "ana",
                "/Users/ana", "MacBook-Pro.local", elected, p -> true);
        assertEquals("macos", zsh.os());
        assertEquals("MacBook-Pro", zsh.host());
        assertEquals("zsh", zsh.interactiveShell());
        assertEquals("zsh", zsh.execShell());
        assertEquals(List.of("/bin/zsh", "-c", "x"), zsh.commandLine(elected, "x"));

        ProofHost fish = ProofHost.detect(OperatingSystem.LINUX, java.util.Map.of("SHELL", "/usr/bin/fish"), "bob",
                "/home/bob", "pc", elected, p -> true);
        assertEquals("fish", fish.interactiveShell());
        assertEquals(elected.commandLine("x"), fish.commandLine(elected, "x"));

        ProofHost win = ProofHost.detect(OperatingSystem.WINDOWS, java.util.Map.of("COMPUTERNAME", "PC-CAGIP"), "x",
                "C:\\Users\\x", "", elected, p -> true);
        assertEquals("windows", win.os());
        assertEquals("PC-CAGIP", win.host());
    }
}
