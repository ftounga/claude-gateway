package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.quota.QuotaExceededException;
import fr.claudegateway.quota.QuotaService;

/** F-235 / SF-235-01 — le cerveau rapide : répondre, déléguer, déléguer d'office, s'effacer. */
class JarvisFastBrainServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final UUID alice = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();
    private Workspace workspace;
    private JarvisUsageService usage;
    private WorkspaceRepository workspaces;
    private final List<AgentTurnRequest> calls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        workspace = Workspace.builder().id(subject).userId(alice).name("Kestrel").build();
        usage = mock(JarvisUsageService.class);
        when(usage.monthFastBrainCostUsd(any())).thenReturn(BigDecimal.ZERO);
        workspaces = mock(WorkspaceRepository.class);
    }

    private JarvisFastBrainService service(AiAgentProvider provider, String budgetEur, Duration timeout) {
        JarvisFastBrainProperties props = new JarvisFastBrainProperties(true, null, null, null,
                budgetEur == null ? null : new BigDecimal(budgetEur), timeout);
        return new JarvisFastBrainService(provider, props, usage, workspaces,
                Clock.fixed(Instant.parse("2026-11-01T13:32:00Z"), ZoneOffset.UTC));
    }

    private AiAgentProvider answering(String text) {
        return new AiAgentProvider() {
            @Override
            public AgentTurn nextTurn(AgentTurnRequest request) {
                calls.add(request);
                return new AgentTurn(text, List.of(), true, 100, 10);
            }

            @Override
            public AgentTurn nextTurn(AgentTurnRequest request, AgentTextListener listener) {
                calls.add(request);
                listener.onTextDelta(text);
                return new AgentTurn(text, List.of(), true, 100, 10);
            }
        };
    }

    private AiAgentProvider delegating(String json) {
        return request -> {
            calls.add(request);
            try {
                return new AgentTurn("", List.of(new AgentToolCall("t1", "deleguer", JSON.readTree(json))), false,
                        100, 10);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private JarvisFastBrainService.Request ask(String text) {
        return new JarvisFastBrainService.Request(alice, workspace, 3, text, null);
    }

    @Test
    void answersWithItsOwnTextAndStreamsIt() {
        StringBuilder heard = new StringBuilder();
        JarvisFastBrainService.Outcome outcome = service(answering("Le chantier 3 en est aux tests."), null, null)
                .converse(ask("Où en est le chantier 3 ?"), heard::append);

        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(outcome.spokenText()).isEqualTo("Le chantier 3 en est aux tests.");
        assertThat(heard).hasToString("Le chantier 3 en est aux tests.");
        verify(usage).addFastBrain(eq(alice), any());
    }

    @Test
    void sendsOnlyTheDelegateToolWithReasoningOffAndACompactShape() {
        service(answering("Bonjour."), null, null).converse(ask("Bonjour"), null);

        AgentTurnRequest request = calls.get(0);
        assertThat(request.model()).isEqualTo("claude-haiku-5-5");
        assertThat(request.tools()).extracting(t -> t.name()).containsExactly("deleguer");
        assertThat(request.reasoning().disabled()).isTrue();
        assertThat(request.reasoning().effort()).isEqualTo("low");
        assertThat(request.shape().maxTokens()).isEqualTo(600);
        assertThat(request.shape().serverTools()).isFalse();
        assertThat(request.shape().strictTools()).isTrue();
        // Le volatil (heure, sujet, parole) est dans le message, jamais dans la consigne système.
        assertThat(request.system()).isEqualTo(JarvisFastBrainPrompt.system());
        assertThat(request.system()).doesNotContain("Kestrel").doesNotContain("2026");
        String message = ((fr.claudegateway.agent.AgentContentBlock.Text) request.messages().get(0).content().get(0))
                .text();
        assertThat(message).contains("Kestrel").contains("Chantier courant : 3").contains("Bonjour");
    }

    @Test
    void systemPromptIsIdenticalFromOneCallToTheNext() {
        JarvisFastBrainService service = service(answering("Oui."), null, null);
        service.converse(ask("Un"), null);
        service.converse(new JarvisFastBrainService.Request(alice, workspace, null, "Deux", null), null);

        assertThat(calls.get(0).system()).isEqualTo(calls.get(1).system());
        assertThat(calls.get(0).tools()).isEqualTo(calls.get(1).tools());
    }

    @Test
    void delegatesWithTheModelsReformulationOnTheCurrentSubject() {
        JarvisFastBrainService.Outcome outcome = service(delegating("""
                {"demande": "Lister les tickets bloqués d'Edenred", "label": "tickets bloqués d'Edenred",
                 "annonce": "Je regarde les tickets bloqués d'Edenred, Monsieur.", "sujet": "", "chantier": null}
                """), null, null).converse(ask("Regarde les tickets bloqués"), null);

        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.DELEGATE);
        assertThat(outcome.delegation().automatic()).isFalse();
        assertThat(outcome.delegation().subjectId()).isEqualTo(subject);
        assertThat(outcome.delegation().chantier()).isEqualTo(3);
        assertThat(outcome.delegation().label()).isEqualTo("tickets bloqués d'Edenred");
        assertThat(outcome.delegation().annonce()).startsWith("Je regarde les tickets bloqués");
    }

    @Test
    void anotherAccountsSubjectIsNeverTargeted() {
        UUID bobs = UUID.randomUUID();
        when(workspaces.findByIdAndUserId(bobs, alice)).thenReturn(Optional.empty());

        JarvisFastBrainService.Outcome outcome = service(delegating("""
                {"demande": "x", "label": "y", "annonce": "Je regarde.", "sujet": "%s", "chantier": null}
                """.formatted(bobs)), null, null).converse(ask("Regarde chez Bob"), null);

        assertThat(outcome.delegation().subjectId()).isEqualTo(subject);
    }

    @Test
    void aNamedSubjectOfTheSameAccountIsTargeted() {
        UUID other = UUID.randomUUID();
        when(workspaces.findByIdAndUserId(other, alice))
                .thenReturn(Optional.of(Workspace.builder().id(other).userId(alice).name("AGENOR").build()));

        JarvisFastBrainService.Outcome outcome = service(delegating("""
                {"demande": "x", "label": "y", "annonce": "Sur AGENOR, je regarde.", "sujet": "%s", "chantier": null}
                """.formatted(other)), null, null).converse(ask("Sur AGENOR, regarde"), null);

        assertThat(outcome.delegation().subjectId()).isEqualTo(other);
        assertThat(outcome.delegation().chantier()).isNull();
    }

    @Test
    void aRefusalBecomesAnAutomaticDelegationOfTheOriginalWords() {
        AiAgentProvider refusing = request -> new AgentTurn("", List.of(), true, 10, 0, false, List.of(), 0, 0, 0,
                true, "cyber");

        JarvisFastBrainService.Outcome outcome = service(refusing, null, null).converse(ask("Fais ceci"), null);

        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.DELEGATE);
        assertThat(outcome.delegation().automatic()).isTrue();
        assertThat(outcome.delegation().reason()).isEqualTo("refusal");
        assertThat(outcome.delegation().demande()).isEqualTo("Fais ceci");
    }

    @Test
    void anErrorBecomesAnAutomaticDelegation() {
        AiAgentProvider failing = request -> {
            throw new IllegalStateException("panne");
        };

        JarvisFastBrainService.Outcome outcome = service(failing, null, null).converse(ask("Bonjour"), null);

        assertThat(outcome.delegation().automatic()).isTrue();
        assertThat(outcome.delegation().reason()).isEqualTo("error");
    }

    @Test
    void noFirstTokenWithinTheDelayBecomesAnAutomaticDelegation() {
        AiAgentProvider slow = request -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new AgentTurn("Trop tard.", List.of(), true, 10, 2);
        };

        JarvisFastBrainService.Outcome outcome = service(slow, null, Duration.ofMillis(100)).converse(ask("Vite"),
                null);

        assertThat(outcome.delegation().automatic()).isTrue();
        assertThat(outcome.delegation().reason()).isEqualTo("timeout");
    }

    @Test
    void anEmptyAnswerBecomesAnAutomaticDelegation() {
        JarvisFastBrainService.Outcome outcome = service(answering("  "), null, null).converse(ask("Euh"), null);

        assertThat(outcome.delegation().reason()).isEqualTo("empty");
    }

    @Test
    void stepsAsideWhenTheMonthlyBudgetIsSpent() {
        when(usage.monthFastBrainCostUsd(alice)).thenReturn(new BigDecimal("25"));

        JarvisFastBrainService.Outcome outcome = service(answering("Oui."), "20", null).converse(ask("Bonjour"),
                null);

        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.FALLBACK);
        assertThat(outcome.reason()).isEqualTo("budget");
        assertThat(calls).isEmpty();
    }

    @Test
    void stepsAsideWhenTheAccountQuotaIsReached() {
        JarvisFastBrainService service = service(answering("Oui."), null, null);
        QuotaService quota = mock(QuotaService.class);
        org.mockito.Mockito.doThrow(new QuotaExceededException("quota")).when(quota).assertWithinQuota(alice);
        service.setAccounting(null, null, null, quota);

        JarvisFastBrainService.Outcome outcome = service.converse(ask("Bonjour"), null);

        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.FALLBACK);
        assertThat(outcome.reason()).isEqualTo("quota");
    }
}
