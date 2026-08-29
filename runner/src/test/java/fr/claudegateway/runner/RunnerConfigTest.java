package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class RunnerConfigTest {

    @TempDir
    Path workspace;

    private String[] baseArgs(String... extra) {
        String[] base = {
                "--gateway", "https://portal.example.com/api",
                "--workspace", workspace.toString(),
                "--code", "AB2C3D4E"
        };
        String[] all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return all;
    }

    @Test
    void resolves_from_cli_only() {
        RunnerConfig cfg = RunnerConfig.resolve(baseArgs("--label", " poste-dev "), Map.of());
        assertEquals("https://portal.example.com/api", cfg.gatewayBaseUrl());
        assertEquals(workspace.toAbsolutePath().normalize(), cfg.workspaceRoot());
        assertEquals("AB2C3D4E", cfg.pairingCode());
        assertEquals("poste-dev", cfg.label());
        assertEquals(30, cfg.heartbeatInterval().toSeconds());
    }

    @Test
    void resolves_from_env_when_cli_absent() {
        RunnerConfig cfg = RunnerConfig.resolve(new String[] {}, Map.of(
                "CLAUDE_RUNNER_GATEWAY", "https://g.example.com/api",
                "CLAUDE_RUNNER_WORKSPACE", workspace.toString(),
                "CLAUDE_RUNNER_CODE", "zzzz1111"));
        assertEquals("https://g.example.com/api", cfg.gatewayBaseUrl());
        assertEquals("ZZZZ1111", cfg.pairingCode());
    }

    @Test
    void cli_takes_precedence_over_env() {
        RunnerConfig cfg = RunnerConfig.resolve(baseArgs(), Map.of(
                "CLAUDE_RUNNER_GATEWAY", "https://ignored.example.com/api",
                "CLAUDE_RUNNER_CODE", "IGNORED0"));
        assertEquals("https://portal.example.com/api", cfg.gatewayBaseUrl());
        assertEquals("AB2C3D4E", cfg.pairingCode());
    }

    @Test
    void trailing_slash_is_stripped_from_gateway() {
        RunnerConfig cfg = RunnerConfig.resolve(new String[] {
                "--gateway", "https://portal.example.com/api/",
                "--workspace", workspace.toString(),
                "--code", "AB2C3D4E"
        }, Map.of());
        assertEquals("https://portal.example.com/api", cfg.gatewayBaseUrl());
    }

    @Test
    void derives_pair_url() {
        RunnerConfig cfg = RunnerConfig.resolve(baseArgs(), Map.of());
        assertEquals("https://portal.example.com/api/runner/pair", cfg.pairUrl());
    }

    @Test
    void derives_wss_uri_from_https() {
        RunnerConfig cfg = RunnerConfig.resolve(baseArgs(), Map.of());
        assertEquals("wss://portal.example.com/api/runner/ws?token=abc123",
                cfg.webSocketUri("abc123").toString());
    }

    @Test
    void derives_ws_uri_from_http_with_port() {
        RunnerConfig cfg = RunnerConfig.resolve(new String[] {
                "--gateway", "http://localhost:8080/api",
                "--workspace", workspace.toString(),
                "--code", "AB2C3D4E"
        }, Map.of());
        assertEquals("ws://localhost:8080/api/runner/ws?token=tok",
                cfg.webSocketUri("tok").toString());
    }

    @Test
    void code_optional_when_absent() {
        RunnerConfig cfg = RunnerConfig.resolve(new String[] {
                "--gateway", "https://portal.example.com/api",
                "--workspace", workspace.toString()
        }, Map.of());
        assertNull(cfg.pairingCode());
    }

    @Test
    void rejects_missing_gateway() {
        RunnerConfig.ConfigException ex = assertThrows(RunnerConfig.ConfigException.class,
                () -> RunnerConfig.resolve(new String[] {
                        "--workspace", workspace.toString(), "--code", "AB2C3D4E"
                }, Map.of()));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("--gateway"));
    }

    @Test
    void rejects_missing_workspace() {
        assertThrows(RunnerConfig.ConfigException.class,
                () -> RunnerConfig.resolve(new String[] {
                        "--gateway", "https://portal.example.com/api", "--code", "AB2C3D4E"
                }, Map.of()));
    }

    @Test
    void rejects_nonexistent_workspace() {
        assertThrows(RunnerConfig.ConfigException.class,
                () -> RunnerConfig.resolve(new String[] {
                        "--gateway", "https://portal.example.com/api",
                        "--workspace", workspace.resolve("does-not-exist").toString(),
                        "--code", "AB2C3D4E"
                }, Map.of()));
    }

    @Test
    void rejects_non_http_gateway() {
        assertThrows(RunnerConfig.ConfigException.class,
                () -> RunnerConfig.resolve(new String[] {
                        "--gateway", "ftp://portal.example.com",
                        "--workspace", workspace.toString(),
                        "--code", "AB2C3D4E"
                }, Map.of()));
    }

    @Test
    void rejects_label_over_100_chars() {
        String longLabel = "x".repeat(101);
        assertThrows(RunnerConfig.ConfigException.class,
                () -> RunnerConfig.resolve(baseArgs("--label", longLabel), Map.of()));
    }

    @Test
    void rejects_non_numeric_heartbeat() {
        assertThrows(RunnerConfig.ConfigException.class,
                () -> RunnerConfig.resolve(baseArgs("--heartbeat-interval", "soon"), Map.of()));
    }

    @Test
    void rejects_non_positive_heartbeat() {
        assertThrows(RunnerConfig.ConfigException.class,
                () -> RunnerConfig.resolve(baseArgs("--heartbeat-interval", "0"), Map.of()));
    }

    @Test
    void custom_heartbeat_interval_applied() {
        RunnerConfig cfg = RunnerConfig.resolve(baseArgs("--heartbeat-interval", "10"), Map.of());
        assertEquals(10, cfg.heartbeatInterval().toSeconds());
    }
}
