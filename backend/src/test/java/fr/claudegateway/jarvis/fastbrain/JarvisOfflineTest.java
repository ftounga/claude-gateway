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

    /**
     * F-262 / SF-262-01 — un faux fournisseur <b>fidèle à l'API</b> : Opus 5.5 refuse toute réflexion éteinte (400 réel
     * « "thinking.type.disabled" is not supported for this model »), comme le fait le fournisseur.
     */
    private AiAgentProvider answering(String text) {
        return request -> {
            calls.add(request);
            if ("claude-opus-5-5".equals(request.model()) && request.reasoning() != null
                    && request.reasoning().disabled()) {
                throw new fr.claudegateway.ai.AIProviderException("Échec de l'appel au fournisseur IA.",
                        new IllegalStateException("400 \"thinking.type.disabled\" is not supported for this model"));
            }
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
        assertThat(said.get(0)).startsWith("Le poste est éteint ; je ne peux pas y agir");
        assertThat(String.join("", said)).contains("Le chantier 3 avance");
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).tools()).extracting(AgentTool::name)
                .containsExactlyInAnyOrder("pieces", "agenda", "etat", "conversations");
        // F-262 / SF-262-01 — Opus 5.5 n'accepte aucune extinction : adaptative, effort du cerveau rapide, place de
        // réflexion dans le plafond.
        assertThat(calls.get(0).reasoning().disabled()).isFalse();
        assertThat(calls.get(0).reasoning().adaptive()).isTrue();
        assertThat(calls.get(0).reasoning().effort()).isEqualTo("low");
        assertThat(calls.get(0).shape().maxTokens()).isEqualTo(600 + JarvisFastBrainService.THINKING_ROOM_TOKENS);
    }

    @Test
    void theToolLoopReplaysItsReasoningAndReadsTheTargetHostState() {
        Workspace w = owned(host);
        when(meter.hostOffline(alice, w)).thenReturn(true);
        fr.claudegateway.jarvis.state.JarvisStateTool state = mock(fr.claudegateway.jarvis.state.JarvisStateTool.class);
        when(state.read(eq(alice), eq(host), any(), eq(false)))
                .thenReturn(new fr.claudegateway.jarvis.state.JarvisStateTool.Read("<etat>PROJ-1 en revue</etat>", List.of()));
        fr.claudegateway.agent.AgentContentBlock thought = new fr.claudegateway.agent.AgentContentBlock.Reasoning("", "sig");
        AiAgentProvider twice = request -> {
            calls.add(request);
            AgentTurn turn = mock(AgentTurn.class);
            if (calls.size() == 1) {
                when(turn.toolCalls()).thenReturn(List.of(new fr.claudegateway.agent.AgentToolCall("t1", "etat",
                        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode())));
                when(turn.reasoning()).thenReturn(List.of(thought));
            } else {
                when(turn.text()).thenReturn("PROJ-1 était en revue, relevé hier.");
                when(turn.toolCalls()).thenReturn(List.of());
            }
            return turn;
        };
        JarvisFastBrainService s = service(twice);
        s.setState(null, state);

        JarvisFastBrainService.Outcome out = s.offlineInstead(ask(), delegated(), sink);

        assertThat(out.spokenText()).contains("PROJ-1 était en revue");
        assertThat(calls).hasSize(2);
        fr.claudegateway.agent.AgentMessage assistant = calls.get(1).messages().get(1);
        assertThat(assistant.content().get(0)).isSameAs(thought);
        assertThat(assistant.content().get(1)).isInstanceOf(fr.claudegateway.agent.AgentContentBlock.ToolUse.class);
    }

    @Test
    void aFailingModelSaysTheTargetHostsRecordedFacts() {
        Workspace w = owned(host);
        when(meter.hostOffline(alice, w)).thenReturn(true);
        fr.claudegateway.jarvis.state.JarvisStateTool state = mock(fr.claudegateway.jarvis.state.JarvisStateTool.class);
        List<fr.claudegateway.jarvis.state.JarvisStateBlock.Line> lines = List.of(
                new fr.claudegateway.jarvis.state.JarvisStateBlock.Line(host, null, "PROJ-1", "PROJ-1",
                        "- PROJ-1 : statut en revue (vu hier à 17 h 42, Jira)", null, false, false),
                new fr.claudegateway.jarvis.state.JarvisStateBlock.Line(host, null, "mr!4", "MR 4",
                        "- MR 4 : fusionnée (vu hier à 18 h, GitLab)", null, false, false),
                new fr.claudegateway.jarvis.state.JarvisStateBlock.Line(host, null, "x", "x", "- x", null, false, false));
        when(state.read(eq(alice), eq(host), any(), eq(false)))
                .thenReturn(new fr.claudegateway.jarvis.state.JarvisStateTool.Read("", lines));
        AiAgentProvider failing = request -> {
            throw new IllegalStateException("panne");
        };
        JarvisFastBrainService s = service(failing);
        s.setState(null, state);

        JarvisFastBrainService.Outcome out = s.offlineInstead(ask(), delegated(), sink);

        assertThat(out.spokenText()).contains("Dernier relevé : PROJ-1 : statut en revue (vu hier à 17 h 42, Jira) ; "
                + "MR 4 : fusionnée (vu hier à 18 h, GitLab).").doesNotContain("- x");
    }

    @Test
    void aSubjectlessDelegationIsNeverDivertedSoTheSubjectIsAsked() {
        JarvisFastBrainService.Outcome outcome = new JarvisFastBrainService.Outcome(JarvisFastBrainService.Kind.DELEGATE,
                "", new JarvisFastBrainService.Delegation(UUID.randomUUID(), "demande", "", "annonce", null, null, false,
                        null), null, "m", 100L, null, JarvisFastBrainService.Guards.NONE, null);
        when(meter.hostOffline(eq(alice), any())).thenReturn(true);
        assertThat(service(answering("x")).offlineInstead(ask(), outcome, sink)).isSameAs(outcome);
        assertThat(calls).isEmpty();
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
    // ---- F-262 / SF-262-02 — les demandes générales ont une réponse ----

    private JarvisFastBrainService.Outcome general() {
        return new JarvisFastBrainService.Outcome(JarvisFastBrainService.Kind.DELEGATE, "",
                new JarvisFastBrainService.Delegation(UUID.randomUUID(), "Faire le tour des clients", "tour",
                        "Je fais le tour de vos clients, Monsieur.", JarvisFastBrainService.JARVIS_THREAD_TARGET, null,
                        false, null),
                null, "m", 100L, null, JarvisFastBrainService.Guards.NONE, null);
    }

    private static String firstUserText(AgentTurnRequest request) {
        return ((fr.claudegateway.agent.AgentContentBlock.Text) request.messages().get(0).content().get(0)).text();
    }

    @Test
    void f262AGeneralRequestIsAnsweredReadOnlyAfterItsAnnouncement() {
        JarvisFastBrainService.Outcome out = service(answering("Edenred attend votre relecture, relevé ce matin."))
                .generalInstead(ask(), general(), sink);

        assertThat(out.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(out.reason()).isEqualTo(JarvisFastBrainService.REASON_GENERAL);
        assertThat(out.delegation()).isNull();
        assertThat(said.get(0)).isEqualTo("Je fais le tour de vos clients, Monsieur. ");
        assertThat(String.join("", said)).contains("Edenred attend votre relecture");
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).reasoning().adaptive()).isTrue();
        assertThat(calls.get(0).tools()).extracting(AgentTool::name)
                .containsExactlyInAnyOrder("pieces", "agenda", "etat", "conversations");
    }

    @Test
    void f262TheSituationIsInTheMessageAndTheSystemPromptIsTheSameForBothCases() {
        Workspace w = owned(host);
        when(meter.hostOffline(alice, w)).thenReturn(true);
        JarvisFastBrainService s = service(answering("Rien de neuf."));

        s.offlineInstead(ask(), delegated(), sink);
        s.generalInstead(ask(), general(), sink);

        assertThat(calls).hasSize(2);
        assertThat(firstUserText(calls.get(0))).contains(JarvisOfflinePrompt.SITUATION_OFFLINE)
                .doesNotContain(JarvisOfflinePrompt.SITUATION_GENERAL);
        assertThat(firstUserText(calls.get(1))).contains(JarvisOfflinePrompt.SITUATION_GENERAL)
                .doesNotContain(JarvisOfflinePrompt.SITUATION_OFFLINE);
        assertThat(calls.get(0).system()).isEqualTo(calls.get(1).system()).isEqualTo(JarvisOfflinePrompt.SYSTEM);
    }

    @Test
    void f262AFailingModelOnAGeneralRequestStillSaysSomething() {
        AiAgentProvider failing = request -> {
            calls.add(request);
            throw new fr.claudegateway.ai.AIProviderException("Échec de l'appel au fournisseur IA.",
                    new IllegalStateException("panne"));
        };
        JarvisFastBrainService.Outcome out = service(failing).generalInstead(ask(), general(), sink);

        assertThat(out.reason()).isEqualTo(JarvisFastBrainService.REASON_GENERAL);
        assertThat(String.join("", said)).contains(JarvisFastBrainService.GENERAL_FAILED);
    }

    @Test
    void f262ADelegationToASubjectOrWithoutSinkIsNotAGeneralRequest() {
        JarvisFastBrainService s = service(answering("x"));
        JarvisFastBrainService.Outcome toSubject = delegated();
        JarvisFastBrainService.Outcome toJarvis = general();

        assertThat(s.generalInstead(ask(), toSubject, sink)).isSameAs(toSubject);
        assertThat(s.generalInstead(ask(), toJarvis, null)).isSameAs(toJarvis);
        assertThat(calls).isEmpty();
        assertThat(said).isEmpty();
    }
    @Test
    void f262TheFullNoticeIsSaidOncePerHostThenTwoWordsAndTheTargetIsNamed() {
        Workspace w = owned(host);
        when(w.getName()).thenReturn("data-platform");
        when(meter.hostOffline(alice, w)).thenReturn(true);
        JarvisFastBrainService s = service(answering("Rien de relevé sur data-platform."));

        s.offlineInstead(ask(), delegated(), sink);
        String first = said.get(0);
        said.clear();
        s.offlineInstead(ask(), delegated(), sink);

        assertThat(first).startsWith("Le poste est éteint ; je ne peux pas y agir");
        assertThat(said.get(0)).isEqualTo(JarvisFastBrainService.OFFLINE_AGAIN + " ");
        assertThat(firstUserText(calls.get(1))).contains("Sujet visé : data-platform.");
        // Rien de volatil dans la consigne : la même aux deux appels.
        assertThat(calls.get(1).system()).isEqualTo(calls.get(0).system());
    }

    @Test
    void f262TheClientNameIsElided() {
        assertThat(JarvisFastBrainService.ofName("EDENRED")).isEqualTo("d'EDENRED");
        assertThat(JarvisFastBrainService.ofName("CAGIP")).isEqualTo("de CAGIP");
    }
}
