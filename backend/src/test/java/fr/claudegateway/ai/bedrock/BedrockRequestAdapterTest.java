package fr.claudegateway.ai.bedrock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** F-277 / SF-277-01 : ce que le canal Bedrock garde, retire et traduit. */
class BedrockRequestAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final BedrockRequestAdapter adapter = new BedrockRequestAdapter(BedrockProperties.defaults(),
            (uri, headers, body) -> Map.of());

    private ObjectNode clean(String json, List<String> betas) throws Exception {
        return adapter.clean((ObjectNode) MAPPER.readTree(json), betas);
    }

    @Test
    void removesWhatBedrockRefusesAndAddsItsVersion() throws Exception {
        ObjectNode out = clean("""
                {"model":"claude-opus-5-5","stream":true,"max_tokens":10,
                 "diagnostics":{"previous_message_id":null},"fallbacks":"default",
                 "thinking":{"type":"adaptive","display":"updates",
                             "block_binding":{"prefix_mismatch_behavior":"drop_block"}},
                 "output_config":{"effort":"high"},
                 "context_management":{"edits":[]},
                 "messages":[{"role":"user","content":"bonjour"}]}
                """, List.of("context-management-2025-06-27"));

        assertThat(out.has("model")).isFalse();
        assertThat(out.has("stream")).isFalse();
        assertThat(out.has("diagnostics")).isFalse();
        assertThat(out.has("fallbacks")).isFalse();
        assertThat(out.path("thinking").has("block_binding")).isFalse();
        assertThat(out.path("thinking").path("display").asText()).isEqualTo("updates");
        assertThat(out.path("output_config").path("effort").asText()).isEqualTo("high");
        assertThat(out.has("context_management")).isTrue();
        assertThat(out.path("anthropic_version").asText()).isEqualTo("bedrock-2023-05-31");
        assertThat(out.path("anthropic_beta").get(0).asText()).isEqualTo("context-management-2025-06-27");
        assertThat(out.path("messages").get(0).path("content").asText()).isEqualTo("bonjour");
    }

    @Test
    void dropsServerToolsAndKeepsTheCacheMarkerOnTheLastClientTool() throws Exception {
        ObjectNode out = clean("""
                {"model":"m","tools":[
                   {"name":"run","description":"d","input_schema":{"type":"object"}},
                   {"type":"web_search_20260209","name":"web_search"},
                   {"type":"web_fetch_20260209","name":"web_fetch","cache_control":{"type":"ephemeral","ttl":"1h"}}],
                 "messages":[{"role":"user","content":"x"}]}
                """, List.of());

        assertThat(out.path("tools")).hasSize(1);
        assertThat(out.path("tools").get(0).path("name").asText()).isEqualTo("run");
        assertThat(out.path("tools").get(0).path("cache_control").path("ttl").asText()).isEqualTo("1h");
        assertThat(out.has("anthropic_beta")).isFalse();
    }

    @Test
    void removesAllToolsWhenOnlyServerToolsWereDeclared() throws Exception {
        ObjectNode out = clean("""
                {"tools":[{"type":"web_search_20260209","name":"web_search"}],
                 "messages":[{"role":"user","content":"x"}]}
                """, List.of());

        assertThat(out.has("tools")).isFalse();
    }

    @Test
    void stripsReplayedServerBlocksAndCitations() throws Exception {
        ObjectNode out = clean("""
                {"messages":[
                  {"role":"user","content":"cherche"},
                  {"role":"assistant","content":[
                     {"type":"thinking","thinking":"","signature":"sig"},
                     {"type":"server_tool_use","id":"srv_1","name":"web_search","input":{"query":"q"}},
                     {"type":"web_search_tool_result","tool_use_id":"srv_1","content":[]},
                     {"type":"text","text":"Voici","citations":[{"type":"web_search_result_location"}]}]},
                  {"role":"assistant","content":[
                     {"type":"server_tool_use","id":"srv_2","name":"web_fetch","input":{}}]},
                  {"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"ok"}]}]}
                """, List.of());

        var first = out.path("messages").get(1).path("content");
        assertThat(first).hasSize(2);
        assertThat(first.get(0).path("type").asText()).isEqualTo("thinking");
        assertThat(first.get(1).has("citations")).isFalse();
        var second = out.path("messages").get(2).path("content");
        assertThat(second).hasSize(1);
        assertThat(second.get(0).path("text").asText()).isEqualTo(BedrockRequestAdapter.SERVER_BLOCKS_OMITTED);
        assertThat(out.path("messages").get(3).path("content").get(0).path("type").asText())
                .isEqualTo("tool_result");
    }

    /** SF-277-03 : la consigne d'effort (contenu vide + output_config) traverse le canal intacte. */
    @Test
    void keepsTheEffortDirectiveEmpty() throws Exception {
        ObjectNode out = clean("""
                {"model":"claude-opus-5-5","messages":[
                  {"role":"user","content":[{"type":"text","text":"bonjour"}]},
                  {"role":"system","content":[],"output_config":{"effort":"high"}},
                  {"role":"assistant","content":[{"type":"text","text":"salut"}]},
                  {"role":"system","content":[],"output_config":{"effort":"low"}},
                  {"role":"user","content":[{"type":"text","text":"suite"}]}]}
                """, List.of());
        for (int i : new int[] {1, 3}) {
            assertThat(out.path("messages").get(i).path("content").isArray()).isTrue();
            assertThat(out.path("messages").get(i).path("content")).isEmpty();
            assertThat(out.path("messages").get(i).path("output_config").path("effort").asText()).isNotBlank();
        }
        assertThat(out.toString()).doesNotContain(BedrockRequestAdapter.SERVER_BLOCKS_OMITTED);
    }

    @Test
    void replacesFilesApiSourcesAndKeepsBase64Ones() throws Exception {
        ObjectNode out = clean("""
                {"messages":[{"role":"user","content":[
                   {"type":"text","text":"lis"},
                   {"type":"document","source":{"type":"file","file_id":"file_1"}},
                   {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AA=="}},
                   {"type":"tool_result","tool_use_id":"t","content":[
                      {"type":"image","source":{"type":"url","url":"https://x"}}]}]}]}
                """, List.of());

        var content = out.path("messages").get(0).path("content");
        assertThat(content.get(1).path("type").asText()).isEqualTo("text");
        assertThat(content.get(1).path("text").asText()).isEqualTo(BedrockRequestAdapter.ATTACHMENT_UNAVAILABLE);
        assertThat(content.get(2).path("source").path("type").asText()).isEqualTo("base64");
        assertThat(content.get(3).path("content").get(0).path("type").asText()).isEqualTo("text");
    }

    @Test
    void mapsModelIdsToGlobalInferenceProfiles() {
        BedrockProperties properties = BedrockProperties.defaults();

        assertThat(properties.profileFor("claude-opus-5-5")).isEqualTo("global.anthropic.claude-opus-5-5");
        assertThat(properties.profileFor("claude-haiku-5-5")).isEqualTo("global.anthropic.claude-haiku-5-5");
        assertThat(properties.profileFor("claude-haiku-4-5"))
                .isEqualTo("global.anthropic.claude-haiku-4-5-20251001-v1:0");
        assertThat(properties.profileFor("eu.anthropic.claude-opus-5")).isEqualTo("eu.anthropic.claude-opus-5");
        assertThat(properties.baseUrl()).isEqualTo("https://bedrock-runtime.eu-west-3.amazonaws.com");
    }

    @Test
    void anUnknownModeMeansOff() {
        assertThat(BedrockProperties.defaults().failoverMode()).isEqualTo(BedrockProperties.Mode.OFF);
        assertThat(BedrockProperties.defaults().withMode("AUTO").failoverMode())
                .isEqualTo(BedrockProperties.Mode.AUTO);
        assertThat(BedrockProperties.defaults().withMode("false").failoverMode())
                .isEqualTo(BedrockProperties.Mode.OFF);
    }

    @Test
    void translatesAwsErrorsIntoAnthropicOnes() {
        assertThat(BedrockRequestAdapter.translate(400, "ValidationException", "Input is too long for requested model."))
                .isEqualTo(new BedrockRequestAdapter.Translated(400, "invalid_request_error",
                        "prompt is too long: Input is too long for requested model."));
        assertThat(BedrockRequestAdapter.translate(400, "ValidationException", "messages: roles must alternate").type())
                .isEqualTo("invalid_request_error");
        assertThat(BedrockRequestAdapter.translate(429, "ThrottlingException", "slow down").status()).isEqualTo(429);
        assertThat(BedrockRequestAdapter.translate(503, "ServiceUnavailableException", "busy").status()).isEqualTo(529);
        assertThat(BedrockRequestAdapter.translate(403, "AccessDeniedException", "no").type())
                .isEqualTo("permission_error");
        assertThat(BedrockRequestAdapter.translate(500, "InternalServerException", "boom").status()).isEqualTo(500);
    }
}
