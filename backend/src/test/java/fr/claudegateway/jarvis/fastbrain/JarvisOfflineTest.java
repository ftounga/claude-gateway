package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.AtelierProperties;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.jarvis.state.JarvisStateTurnMeter;

/** F-253 / SF-253-05 — le poste éteint : dit, puis Opus en lecture seule ; sinon la délégation intacte. */
class JarvisOfflineTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID host = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final JarvisStateTurnMeter meter = mock(JarvisStateTurnMeter.class);
    private final List<AgentTurnRequest> calls = new ArrayList<>();
    private final List<String> said = new ArrayList<>();
    private final JarvisFastBrainService.Sink sink = new JarvisFastBrainService.Sink() {
        @Override
        public void text(String delta) {
            said.add(delta);
        }
    };

    private JarvisFastBrainService service(AiAgentProvider provider) {
        JarvisFastBrainService s = new JarvisFastBrainService(provider,
                new JarvisFastBrainProperties(true, null, null, null, null, null), mock(JarvisUsageService.class),
                workspaces, Clock.fixed(Instant.parse("2026-11-09T09:00:00Z"), ZoneOffset.UTC));
        AtelierProperties atelier = mock(AtelierProperties.class);
        when(atelier.model()).thenReturn("claude-opus-5-5");
        s.setOffline(meter, atelier, null);
        return s;
    }

    private AiAgentProvider answering(String text) {
        return request -> {
            calls.add(request);
            AgentTurn turn = mock(AgentTurn.class);
            when(turn.text()).thenReturn(text);
            when(turn.toolCalls()).thenReturn(List.of());
            return turn;
        };
    }

    private JarvisFastBrainService.Request ask() {
        return new JarvisFastBrainService.Request(alice, null, null, "Où en est le chantier ?", null, null, false);
    }

    private JarvisFastBrainService.Outcome delegated() {
        return new JarvisFastBrainService.Outcome(JarvisFastBrainService.Kind.DELEGATE, "",
                new JarvisFastBrainService.Delegation(UUID.randomUUID(), "demande", "", "annonce", subject, null, false, null),
                null, "m", 100L, null, JarvisFastBrainService.Guards.NONE, null);
    }

    private Workspace owned(UUID hostId) {
        Workspace w = mock(Workspace.class);
        when(w.getHostId()).thenReturn(hostId);
        when(workspaces.findByIdAndUserId(subject, alice)).thenReturn(Optional.of(w));
        return w;
    }

    @Test
    void anOfflineHostAnswersReadOnlyInsteadOfDelegating() {
        Workspace w = owned(host);
        when(meter.hostOffline(alice, w)).thenReturn(true);
        JarvisFastBrainService.Outcome out = service(answering("Le chantier 3 avance, relevé hier.")).offlineInstead(ask(),
                delegated(), sink);
        assertThat(out.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(out.reason()).isEqualTo(JarvisFastBrainService.REASON_OFFLINE);
        assertThat(said.get(0)).startsWith("Le poste est éteint");
        assertThat(String.join("", said)).contains("Le chantier 3 avance");
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).tools()).extracting(AgentTool::name)
                .containsExactlyInAnyOrder("pieces", "agenda", "etat", "conversations");
    }

    @Test
    void aConnectedHostOrHostedSubjectKeepsTheDelegation() {
        Workspace w = owned(host);
        when(meter.hostOffline(alice, w)).thenReturn(false);
        JarvisFastBrainService.Outcome outcome = delegated();
        assertThat(service(answering("x")).offlineInstead(ask(), outcome, sink)).isSameAs(outcome);
        owned(null);
        assertThat(service(answering("x")).offlineInstead(ask(), outcome, sink)).isSameAs(outcome);
        assertThat(calls).isEmpty();
    }

    @Test
    void anotherAccountsSubjectKeepsTheDelegationAndCallsNoModel() {
        when(workspaces.findByIdAndUserId(eq(subject), any())).thenReturn(Optional.empty());
        JarvisFastBrainService.Outcome outcome = delegated();
        assertThat(service(answering("x")).offlineInstead(ask(), outcome, sink)).isSameAs(outcome);
        assertThat(calls).isEmpty();
        assertThat(said).isEmpty();
    }

    @Test
    void aFailingModelGivesTheFallbackSentence() {
        Workspace w = owned(host);
        when(meter.hostOffline(alice, w)).thenReturn(true);
        AtomicBoolean called = new AtomicBoolean();
        AiAgentProvider failing = request -> {
            called.set(true);
            throw new IllegalStateException("panne");
        };
        JarvisFastBrainService.Outcome out = service(failing).offlineInstead(ask(), delegated(), sink);
        assertThat(called).isTrue();
        assertThat(out.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(out.spokenText()).contains(JarvisFastBrainService.OFFLINE_FAILED);
    }

    @Test
    void noSinkMeansNoDiversion() {
        JarvisFastBrainService.Outcome outcome = delegated();
        assertThat(service(answering("x")).offlineInstead(ask(), outcome, null)).isSameAs(outcome);
    }
}
