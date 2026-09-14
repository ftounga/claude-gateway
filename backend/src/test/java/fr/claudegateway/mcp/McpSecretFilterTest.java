package fr.claudegateway.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires du filtre de secrets de sortie MCP (F-112 / SF-112-04, garde §6.4).
 */
class McpSecretFilterTest {

    private final McpSecretFilter filter = new McpSecretFilter();

    @Test
    void masksAnthropicKey() {
        assertThat(filter.maskString("clé sk-ant-api03-abcDEF123456789xyz"))
                .doesNotContain("sk-ant-api03").contains(McpSecretFilter.MASK);
    }

    @Test
    void masksGithubToken() {
        assertThat(filter.maskString("token=ghp_abcdefghijklmnopqrstuvwxyz0123456789"))
                .doesNotContain("ghp_abcdefghij").contains(McpSecretFilter.MASK);
    }

    @Test
    void masksPersonalGatewayToken() {
        assertThat(filter.maskString("cgmcp_abcdef1234567890ABCDEF"))
                .doesNotContain("cgmcp_abcdef").contains(McpSecretFilter.MASK);
    }

    @Test
    void masksJwt() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.SflKxwRJSMeKKF2QT4fwpM";
        assertThat(filter.maskString("Authorization value " + jwt))
                .doesNotContain("eyJhbGci").contains(McpSecretFilter.MASK);
    }

    @Test
    void masksAwsAccessKey() {
        assertThat(filter.maskString("AKIAIOSFODNN7EXAMPLE"))
                .doesNotContain("AKIAIOSFODNN7EXAMPLE").contains(McpSecretFilter.MASK);
    }

    @Test
    void masksBearerHeader() {
        assertThat(filter.maskString("Bearer abcdef0123456789ghijkl"))
                .contains(McpSecretFilter.MASK);
    }

    @Test
    void leavesOrdinaryTextUntouched() {
        String text = "Poste d'Alice, connecté, runner 1.4.2, 3 projets actifs.";
        assertThat(filter.maskString(text)).isEqualTo(text);
    }

    @Test
    void masksRecursivelyInNestedStructures() {
        Object masked = filter.mask(Map.of(
                "name", "poste",
                "notes", List.of("ok", "ghp_abcdefghijklmnopqrstuvwxyz0123456789"),
                "nested", Map.of("secret", "sk-ant-api03-abcDEF123456789xyz")));
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) masked;
        assertThat(map.get("name")).isEqualTo("poste");
        @SuppressWarnings("unchecked")
        List<String> notes = (List<String>) map.get("notes");
        assertThat(notes.get(0)).isEqualTo("ok");
        assertThat(notes.get(1)).contains(McpSecretFilter.MASK).doesNotContain("ghp_");
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) map.get("nested");
        assertThat(nested.get("secret").toString()).contains(McpSecretFilter.MASK);
    }

    @Test
    void handlesNullAndEmpty() {
        assertThat(filter.maskString(null)).isNull();
        assertThat(filter.maskString("")).isEmpty();
    }
}
