package fr.claudegateway.runner.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.relay.RunnerCallRouter;

/**
 * F-88 / SF-88-03 — le relais des outils de <b>lecture</b> Teams.
 *
 * <p>La gateway <b>relaie</b> : elle ne réinterprète ni la période, ni le plafond, ni la
 * conversation demandée — seul le runner sait ce que « from » veut dire, et une seconde lecture ici
 * créerait une seconde vérité. Ce que la façade fait quand même, parce que rien d'autre ne le
 * ferait : <b>borner</b> avant émission, et poser le bon <b>délai</b>.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RunnerTeamsRelayTest {

    @Mock private RunnerCallRouter router;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RunnerTarget target =
            new RunnerTarget(UUID.randomUUID(), UUID.randomUUID(), "projet");

    private RunnerToolGateway gateway() {
        when(router.call(any(), anyString(), anyString(), any(), anyLong()))
                .thenReturn(new RunnerCallResult(true, "{}", false, null, 1L, null, null, null, "",
                        false));
        return new RunnerToolGateway(router, objectMapper);
    }

    private ObjectNode input() {
        return objectMapper.createObjectNode();
    }

    private JsonNode captured(String tool, long expectedTimeout) {
        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<Long> timeout = ArgumentCaptor.forClass(Long.class);
        verify(router).call(eq(target), anyString(), eq(tool), payload.capture(),
                timeout.capture());
        assertThat(timeout.getValue()).isEqualTo(expectedTimeout);
        return payload.getValue();
    }

    @Test
    @DisplayName("Les paramètres sont relayés tels quels : la gateway ne réinterprète pas la demande")
    void parameters_travel_untouched() {
        RunnerToolGateway gateway = gateway();

        gateway.teamsRead(target, "toolu_1", "teams_read_conversation",
                input().put("conversation_id", "19:x@thread.v2").put("from", "21d")
                        .put("max_messages", 1500));

        JsonNode sent = captured("teams_read_conversation",
                RunnerToolGateway.TEAMS_SCROLLING_TIMEOUT_MS);
        assertThat(sent.path("conversation_id").asText()).isEqualTo("19:x@thread.v2");
        assertThat(sent.path("from").asText()).isEqualTo("21d");
        assertThat(sent.path("max_messages").asInt()).isEqualTo(1500);
    }

    @Test
    @DisplayName("Lire un fil et chercher font DÉFILER : leur délai est plus long")
    void scrolling_tools_get_a_longer_deadline() {
        assertThat(RunnerToolGateway.teamsTimeoutFor("teams_read_conversation"))
                .isEqualTo(RunnerToolGateway.TEAMS_SCROLLING_TIMEOUT_MS);
        assertThat(RunnerToolGateway.teamsTimeoutFor("teams_search"))
                .isEqualTo(RunnerToolGateway.TEAMS_SCROLLING_TIMEOUT_MS);
        // Un teams_status qui mettrait une minute à dire « navigateur non détecté » serait une
        // régression de SF-87-03 : observer est court, parcourir ne l'est pas.
        assertThat(RunnerToolGateway.teamsTimeoutFor("teams_status"))
                .isEqualTo(RunnerToolGateway.TEAMS_TOOL_TIMEOUT_MS);
        assertThat(RunnerToolGateway.teamsTimeoutFor("teams_mentions"))
                .isEqualTo(RunnerToolGateway.TEAMS_TOOL_TIMEOUT_MS);
    }

    @Test
    @DisplayName("Une requête démesurée est bornée AVANT émission")
    void an_oversized_query_is_bounded_before_it_travels() {
        RunnerToolGateway gateway = gateway();

        gateway.teamsRead(target, "toolu_1", "teams_search",
                input().put("query", "x".repeat(50_000)));

        assertThat(captured("teams_search", RunnerToolGateway.TEAMS_SCROLLING_TIMEOUT_MS)
                .path("query").asText().length()).isLessThanOrEqualTo(1_024);
    }

    @Test
    @DisplayName("Seuls des scalaires traversent : un objet quelconque n'est pas recopié")
    void only_scalars_travel() {
        RunnerToolGateway gateway = gateway();
        ObjectNode ask = input().put("query", "MFA");
        ask.putObject("bizarre").put("jeton", "SECRET");
        ask.putArray("liste").add("SECRET");
        ask.putNull("from");

        gateway.teamsRead(target, "toolu_1", "teams_search", ask);

        JsonNode sent = captured("teams_search", RunnerToolGateway.TEAMS_SCROLLING_TIMEOUT_MS);
        assertThat(sent.toString()).doesNotContain("SECRET");
        assertThat(sent.has("from")).isFalse();
        assertThat(sent.path("query").asText()).isEqualTo("MFA");
    }

    @Test
    @DisplayName("Un nom d'outil qui n'est pas du volet Teams n'est jamais émis")
    void a_tool_outside_the_panel_never_travels() {
        RunnerToolGateway gateway = gateway();

        RunnerCallResult result = gateway.teamsRead(target, "toolu_1", "rm_rf", input());

        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.INVALID_INPUT);
        verify(router, never()).call(any(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("Une demande sans paramètre est émise avec un objet vide, pas refusée")
    void a_parameterless_call_still_travels() {
        RunnerToolGateway gateway = gateway();

        gateway.teamsRead(target, "toolu_1", "teams_mentions", null);

        assertThat(captured("teams_mentions", RunnerToolGateway.TEAMS_TOOL_TIMEOUT_MS).isObject())
                .isTrue();
    }
}
