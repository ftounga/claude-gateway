package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.jarvis.fastbrain.JarvisFastVerdictService.Verdict;
import fr.claudegateway.jarvis.portrait.JarvisAllusionService;
import fr.claudegateway.jarvis.portrait.JarvisPortraitNote;
import fr.claudegateway.jarvis.portrait.JarvisPortraitService;

/**
 * F-252 / SF-252-02 (D2, CA1, CA2) — une réponse provisoire n'a aucun effet avant adoption ; adoptée, tous ses effets
 * ont lieu une fois, avec le texte de référence.
 */
class JarvisFastBrainProvisionalTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-08T09:00:00Z");
    private final UUID alice = UUID.randomUUID();
    private Workspace workspace;
    private JarvisAllusionService allusions;
    private JarvisFastExchangeWriter exchanges;
    private JarvisFastMemory memory;
    private JarvisDelegationRegistry registry;

    @BeforeEach
    void setUp() {
        workspace = Workspace.builder().id(UUID.randomUUID()).userId(alice).name("SSO").hostId(UUID.randomUUID())
                .build();
        allusions = mock(JarvisAllusionService.class);
        when(allusions.recent(any())).thenReturn(List.of());
        exchanges = mock(JarvisFastExchangeWriter.class);
        memory = new JarvisFastMemory(Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
        registry = new JarvisDelegationRegistry(Clock.systemUTC(), null);
    }

    private JarvisFastBrainService service(AgentTurn reply) {
        JarvisPortraitService portraits = mock(JarvisPortraitService.class);
        when(portraits.snapshot(alice)).thenReturn(Optional.of(JarvisPortraitNote.fixture(
                Map.of("GOUTS", "Prépare le semi-marathon de Lyon."), List.of(), NOW.minusDays(30))));
        JarvisUsageService usage = mock(JarvisUsageService.class);
        when(usage.monthFastBrainCostUsd(any())).thenReturn(BigDecimal.ZERO);
        JarvisFastBrainService s = new JarvisFastBrainService(new AiAgentProvider() {
            @Override
            public AgentTurn nextTurn(AgentTurnRequest request) {
                return reply;
            }

            @Override
            public AgentTurn nextTurn(AgentTurnRequest request, AgentTextListener listener) {
                if (!reply.text().isEmpty()) {
                    listener.onTextDelta(reply.text());
                }
                return reply;
            }
        }, new JarvisFastBrainProperties(true, null, null, null, null, null), usage, mock(WorkspaceRepository.class),
                Clock.fixed(Instant.parse("2026-11-08T09:00:00Z"), ZoneOffset.UTC));
        s.setPortraits(portraits);
        s.setAllusions(allusions);
        s.setExchanges(exchanges);
        s.setContext(null, memory, registry);
        return s;
    }

    private JarvisFastBrainService.Request ask(String text) {
        return new JarvisFastBrainService.Request(alice, workspace, null, text, null, null, false);
    }

    private static AgentTurn answer(String text) {
        return new AgentTurn(text, List.of(), true, 100, 10);
    }

    private static AgentTurn delegation() throws Exception {
        return new AgentTurn("", List.of(new AgentToolCall("t1", "deleguer", JSON.readTree("""
                {"demande": "x", "label": "tickets bloqués", "annonce": "Je regarde les tickets bloqués.",
                 "sujet": "", "chantier": null}
                """))), false, 100, 10);
    }

    private void noEffect() {
        verify(exchanges, never()).write(any(), any(), any());
        verify(allusions, never()).record(any(), anyString(), anyString(), any(), anyBoolean());
        assertThat(memory.recall(alice)).isEmpty();
        assertThat(registry.running(alice)).isEmpty();
    }

    @Test
    @DisplayName("CA1 — verdict de rejet : réponse relayée, mais aucun effet")
    void rejectedHasNoEffect() {
        StringBuilder heard = new StringBuilder();
        var outcome = service(answer("✦ humour: le café du lundi\nBonjour, Monsieur."))
                .converseProvisional(ask("Bonjour Jarvis"), heard::append,
                        () -> Optional.of(new Verdict(false, null)));
        assertThat(outcome).isEmpty();
        assertThat(heard.toString()).contains("Bonjour, Monsieur.");
        noEffect();
    }

    @Test
    @DisplayName("CA1 — aucun verdict : aucun effet")
    void noVerdictHasNoEffect() {
        assertThat(service(answer("Bonjour, Monsieur.")).converseProvisional(ask("Bonjour Jarvis"), d -> { },
                Optional::empty)).isEmpty();
        noEffect();
    }

    @Test
    @DisplayName("CA1 — une délégation provisoire jetée ne s'enregistre pas")
    void rejectedDelegationNotRegistered() throws Exception {
        assertThat(service(delegation()).converseProvisional(ask("Regarde les tickets bloqués"), d -> { },
                () -> Optional.of(new Verdict(false, null)))).isEmpty();
        noEffect();
    }

    @Test
    @DisplayName("CA2 — adoptée : les effets ont lieu une fois, avec le texte de référence")
    void adoptedAppliesEffects() {
        var outcome = service(answer("✦ humour: le café du lundi\nBonjour, Monsieur."))
                .converseProvisional(ask("bonjour jarvis"), d -> { },
                        () -> Optional.of(new Verdict(true, "Bonjour Jarvis.")));
        assertThat(outcome).hasValueSatisfying(o -> assertThat(o.spokenText()).isEqualTo("Bonjour, Monsieur."));
        verify(exchanges, times(1)).write(eq(alice), eq("Bonjour Jarvis."), eq("Bonjour, Monsieur."));
        verify(allusions, times(1)).record(eq(alice), eq("HUMOUR"), eq("le café du lundi"), anyString(), eq(true));
        assertThat(memory.recall(alice)).hasSize(1);
        assertThat(memory.recall(alice).get(0).said()).isEqualTo("Bonjour Jarvis.");
    }

    @Test
    @DisplayName("CA2 — une délégation adoptée s'enregistre, et même résultat qu'un appel normal")
    void adoptedDelegationRegistered() throws Exception {
        var provisional = service(delegation()).converseProvisional(ask("Regarde les tickets bloqués"), d -> { },
                () -> Optional.of(new Verdict(true, "Regarde les tickets bloqués")));
        assertThat(provisional).hasValueSatisfying(o -> assertThat(o.kind())
                .isEqualTo(JarvisFastBrainService.Kind.DELEGATE));
        assertThat(registry.running(alice)).extracting(JarvisDelegationRegistry.Running::label)
                .containsExactly("tickets bloqués");
    }
}
