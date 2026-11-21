package fr.claudegateway.atelier.veille;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/** F-273 / SF-273-01 — une sonde en échec dit la vraie raison : poste muet ≠ script absent, extrait masqué. */
class JarvisWatchProbeRunnerTest {

    private static final String SCRIPT = "#!/usr/bin/env python3\nprint('[]')\n";
    private RunnerToolGateway gateway;
    private JarvisWatchProbeRunner runner;
    private JarvisWatch watch;
    private final Workspace subject = Workspace.builder().id(UUID.randomUUID()).userId(UUID.randomUUID())
            .hostId(UUID.randomUUID()).projectPath("secrets-azure-devops").build();

    @BeforeEach
    void setUp() {
        gateway = mock(RunnerToolGateway.class);
        runner = new JarvisWatchProbeRunner(gateway);
        watch = JarvisWatch.builder().id(UUID.randomUUID()).userId(subject.getUserId()).workspaceId(subject.getId())
                .name("Relevé Jira").probeCommand("python3 .atelier/releve/jira.py")
                .probeScriptPath(".atelier/releve/jira.py").probeScriptHash(JarvisWatchProbeRunner.sha256(SCRIPT))
                .purpose(JarvisWatch.PURPOSE_STATE).build();
    }

    private void script(RunnerCallResult result) {
        when(gateway.readFile(any(), anyString(), eq(".atelier/releve/jira.py"))).thenReturn(result);
    }

    private void bash(Integer exit, String streamed) {
        when(gateway.bashLow(any(), anyString(), anyString(), anyLong()))
                .thenReturn(new RunnerCallResult(true, "", false, exit, 10L, null, null, null, streamed, false));
    }

    @Test
    void aDisconnectedRunnerIsReportedAsOffline() {
        script(RunnerCallResult.backendError("runner_unavailable"));
        JarvisWatchProbeRunner.Outcome out = runner.run(watch, subject);
        assertThat(out.errorCode()).isEqualTo(JarvisWatchProbeRunner.ERR_OFFLINE);
        assertThat(out.errorMessage()).contains("ne répond pas").contains("pas en cause");
    }

    @Test
    void aSilentRunnerIsATransportFailureNotAMissingScript() {
        script(RunnerCallResult.backendError("runner_timeout"));
        JarvisWatchProbeRunner.Outcome out = runner.run(watch, subject);
        assertThat(out.errorCode()).isEqualTo(JarvisWatchProbeRunner.ERR_TRANSPORT);
        assertThat(out.errorMessage()).contains("n'a pas répondu").doesNotContain("introuvable");
        assertThat(JarvisWatchProbeRunner.isHostSide(out.errorCode())).isTrue();
    }

    @Test
    void onlyTheRunnerItselfCanSayTheScriptIsMissing() {
        script(new RunnerCallResult(false, "", false, null, 1L, null, "not_found",
                "Fichier introuvable : .atelier/releve/jira.py", "", false));
        JarvisWatchProbeRunner.Outcome out = runner.run(watch, subject);
        assertThat(out.errorCode()).isEqualTo(JarvisWatchProbeRunner.ERR_SCRIPT_MISSING);
        assertThat(out.errorMessage()).contains("introuvable").contains("jira.py");
        assertThat(JarvisWatchProbeRunner.isHostSide(out.errorCode())).isFalse();
    }

    @Test
    void aNonZeroExitShowsTheLastLineWithSecretsMasked() {
        script(new RunnerCallResult(true, SCRIPT, false, null, 1L, null, null, null, "", false));
        bash(1, "Traceback (most recent call last):\n  File \"jira.py\"\n"
                + "urllib.error.HTTPError: HTTP Error 401 Authorization: Bearer eyJhbGciOi.x\n");
        JarvisWatchProbeRunner.Outcome out = runner.run(watch, subject);
        assertThat(out.errorCode()).isEqualTo(JarvisWatchProbeRunner.ERR_EXIT);
        assertThat(out.errorMessage()).contains("code 1").contains("HTTP Error 401").contains("***")
                .doesNotContain("eyJhbGciOi");
    }

    @Test
    void aNonZeroExitWithoutOutputKeepsTheGenericHint() {
        script(new RunnerCallResult(true, SCRIPT, false, null, 1L, null, null, null, "", false));
        bash(2, "");
        assertThat(runner.run(watch, subject).errorMessage()).contains("code 2").contains("sans rien écrire");
    }

    @Test
    void anUnreadableOutputShowsItsBeginning() {
        script(new RunnerCallResult(true, SCRIPT, false, null, 1L, null, null, null, "", false));
        bash(0, "Usage: jira.py --token=s3cr3t-value");
        JarvisWatchProbeRunner.Outcome out = runner.run(watch, subject);
        assertThat(out.errorCode()).isEqualTo(JarvisWatchProbeRunner.ERR_OUTPUT);
        assertThat(out.errorMessage()).contains("Début de la sortie").contains("Usage: jira.py")
                .doesNotContain("s3cr3t-value");
    }

    @Test
    void excerptsAreBoundedAndMasked() {
        assertThat(JarvisWatchProbeRunner.excerpt("x".repeat(10) + " " + "y ".repeat(300))).hasSizeLessThanOrEqualTo(
                JarvisWatchProbeRunner.EXCERPT + 1);
        assertThat(JarvisWatchProbeRunner.excerpt("password: hunter2 done")).isEqualTo("password: *** done");
        assertThat(JarvisWatchProbeRunner.excerpt("key ABCDEFGHIJKLMNOPQRSTUVWXYZ012345")).isEqualTo("key ***");
        assertThat(JarvisWatchProbeRunner.lastLine("a\nb\n\n")).isEqualTo("b");
        assertThat(JarvisWatchProbeRunner.bounded("z".repeat(600))).hasSize(JarvisWatchProbeRunner.MAX_ERROR);
    }
}
