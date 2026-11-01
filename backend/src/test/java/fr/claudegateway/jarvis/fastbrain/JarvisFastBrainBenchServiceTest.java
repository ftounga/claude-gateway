package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
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
import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.JarvisUsageService;

/** F-235 / SF-235-08 (D16) — le banc : à blanc, jugé, porte, sous {@code user_id}. */
class JarvisFastBrainBenchServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final UUID alice = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();
    private final List<AgentTurnRequest> calls = new ArrayList<>();
    private AtelierMessageRepository messages;
    private WorkspaceRepository workspaces;
    private JarvisFastExchangeWriter writer;
    private JarvisFastMemory memory;
    private JarvisDelegationRegistry registry;

    @BeforeEach
    void setUp() {
        messages = mock(AtelierMessageRepository.class);
        workspaces = mock(WorkspaceRepository.class);
        writer = mock(JarvisFastExchangeWriter.class);
        memory = mock(JarvisFastMemory.class);
        registry = mock(JarvisDelegationRegistry.class);
        when(workspaces.findByIdAndUserId(subject, alice))
                .thenReturn(Optional.of(Workspace.builder().id(subject).userId(alice).name("Kestrel").build()));
        List<AtelierMessage> voice = List.of(
                msg("Où en est Kestrel ?"), msg("Envoie le compte rendu à Paul"));
        when(messages.findTop60ByUserIdAndViaAndRoleOrderByCreatedAtDesc(alice, AtelierMessage.VIA_JARVIS, "USER"))
                .thenReturn(voice);
    }

    private AtelierMessage msg(String text) {
        return AtelierMessage.builder().id(UUID.randomUUID()).workspaceId(subject).userId(alice).role("USER")
                .content(text).via(AtelierMessage.VIA_JARVIS).build();
    }

    /** Fournisseur simulé : le cerveau rapide (modèle candidat) puis le juge (outil « noter »). */
    private AiAgentProvider provider(String fastAnswer, String verdictJson) {
        return new AiAgentProvider() {
            @Override
            public AgentTurn nextTurn(AgentTurnRequest request) {
                return nextTurn(request, d -> { });
            }

            @Override
            public AgentTurn nextTurn(AgentTurnRequest request, AgentTextListener listener) {
                calls.add(request);
                try {
                    if (request.tools().stream().anyMatch(t -> "noter".equals(t.name()))) {
                        return new AgentTurn("", List.of(new AgentToolCall("j", "noter", JSON.readTree(verdictJson))),
                                false, 100, 20);
                    }
                    listener.onTextDelta(fastAnswer);
                    return new AgentTurn(fastAnswer, List.of(), true, 100, 10);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private JarvisFastBrainBenchService bench(AiAgentProvider provider) {
        JarvisUsageService usage = mock(JarvisUsageService.class);
        when(usage.monthFastBrainCostUsd(any())).thenReturn(new BigDecimal("1000")); // plafond dépassé : sans effet
        JarvisFastBrainService fast = new JarvisFastBrainService(provider,
                new JarvisFastBrainProperties(false, null, null, null, null, null), usage, workspaces,
                Clock.systemUTC());
        fast.setContext(null, memory, registry);
        fast.setExchanges(writer);
        return new JarvisFastBrainBenchService(fast, provider, messages, workspaces, Clock.systemUTC(),
                "claude-opus-5-5");
    }

    private static final String GOOD = """
            {"route_attendue":"repondre","fait_non_ancre":false,"action_non_deleguee":false,"creuse":false,
             "touches_humour":0,"vouvoiement":true,"imitation":false,"commentaire":"ok"}""";

    @Test
    void replaysTheAccountsVoiceRequestsDryOnTheCandidateAndJudgesThem() {
        JarvisFastBrainBenchService bench = bench(provider("Kestrel avance bien.", GOOD));

        JarvisFastBrainBenchService.Report report = bench.run(alice, UUID.randomUUID(), "REAL", "claude-haiku-5-5",
                OffsetDateTime.now());

        assertThat(report.cases()).isEqualTo(2);
        assertThat(report.judged()).isEqualTo(2);
        assertThat(calls).filteredOn(r -> r.tools().stream().anyMatch(t -> "deleguer".equals(t.name())))
                .extracting(AgentTurnRequest::model).containsOnly("claude-haiku-5-5");
        assertThat(calls).filteredOn(r -> r.tools().stream().anyMatch(t -> "noter".equals(t.name())))
                .extracting(AgentTurnRequest::model).containsOnly("claude-opus-5-5");
        // À blanc : ni trace dans le fil, ni mémoire, ni registre — et le plafond ne bloque pas le banc.
        verifyNoInteractions(writer, memory, registry);
        // Le rapport ne recopie pas les paroles du compte.
        assertThat(report.toString()).doesNotContain("compte rendu à Paul").doesNotContain("Où en est");
        assertThat(report.gatePassed()).isTrue();
        assertThat(report.routeAgreement()).isEqualTo(1.0);
    }

    @Test
    void anActionAnsweredWithoutDelegatingFailsTheGate() {
        JarvisFastBrainBenchService bench = bench(provider("Kestrel avance bien.", """
                {"route_attendue":"deleguer","fait_non_ancre":false,"action_non_deleguee":true,"creuse":false,
                 "touches_humour":0,"vouvoiement":true,"imitation":false,"commentaire":"action"}"""));

        JarvisFastBrainBenchService.Report report = bench.run(alice, UUID.randomUUID(), "REAL", "claude-haiku-5-5",
                OffsetDateTime.now());

        assertThat(report.gatePassed()).isFalse();
        assertThat(report.actionsNotDelegated()).isEqualTo(2);
        assertThat(report.gateFailures()).anyMatch(f -> f.contains("action"));
    }

    @Test
    void thePersonalitySetRunsTheTwentySyntheticQuestions() {
        JarvisFastBrainBenchService bench = bench(provider("Avec plaisir, Monsieur.", GOOD));

        JarvisFastBrainBenchService.Report report = bench.run(alice, UUID.randomUUID(), "PERSONALITY",
                "claude-sonnet-5-5", OffsetDateTime.now());

        assertThat(report.cases()).isEqualTo(20);
        // Les questions d'action n'ont pas été déléguées par ce faux cerveau : la porte personnalité le voit.
        assertThat(report.gateFailures()).anyMatch(f -> f.contains("action non déléguée"));
        verifyNoInteractions(messages);
    }

    @Test
    void onlyKnownCandidatesAndSetsAndOnlyTheOwnersReport() {
        JarvisFastBrainBenchService bench = bench(provider("x", GOOD));
        assertThatThrownBy(() -> bench.start(alice, "REAL", "gpt-5")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bench.start(alice, "TOUT", null)).isInstanceOf(IllegalArgumentException.class);
        UUID run = bench.start(alice, null, null);
        assertThat(bench.report(alice, run)).isPresent();
        assertThat(bench.report(UUID.randomUUID(), run)).isEmpty();
    }
}
