package fr.claudegateway.jarvis.oralbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;


import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.AtelierChatService;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.JarvisOralNote;
import fr.claudegateway.quota.ProviderCostCalculator;
import fr.claudegateway.quota.TurnCost;

/** Le banc de la parole (F-240 / SF-240-02, D12) : appel sans outils, consigne réelle, juge, plafond. */
class JarvisOralBenchServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final UUID alice = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();
    private final List<AgentTurnRequest> calls = new ArrayList<>();

    private AiAgentProvider provider(String reply, String verdictJson) {
        return new AiAgentProvider() {
            @Override
            public AgentTurn nextTurn(AgentTurnRequest request) {
                return nextTurn(request, d -> { });
            }

            @Override
            public AgentTurn nextTurn(AgentTurnRequest request, AgentTextListener listener) {
                calls.add(request);
                try {
                    if (request.tools().stream().anyMatch(t -> JarvisOralBenchService.JUDGE_TOOL.equals(t.name()))) {
                        return new AgentTurn("", List.of(new AgentToolCall("j", JarvisOralBenchService.JUDGE_TOOL,
                                JSON.readTree(verdictJson))), false, 100, 20);
                    }
                    return new AgentTurn(reply, List.of(), true, 30_000, 300);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private JarvisOralBenchService service(AiAgentProvider provider, String cap) {
        AtelierChatService chat = mock(AtelierChatService.class);
        when(chat.terminalSystemPrompt(alice, subject)).thenReturn("CONSIGNE SYSTEME DU TERMINAL");
        return new JarvisOralBenchService(provider, chat, mock(WorkspaceRepository.class), Clock.systemUTC(),
                "claude-opus-5-5", "claude-opus-5-5", new BigDecimal(cap));
    }

    @Test
    void eachScenarioIsPlayedWithoutToolsWithTheRealPromptsThenJudged() {
        JarvisOralBenchService bench = service(provider("""
                🎙 Le déploiement est terminé et sain, Monsieur.
                ↪ Il y a aussi un point sur Helios, je vous le dis ?
                <<essentiel>>
                - Terminé
                - Sain
                - Helios : certificat
                <</essentiel>>
                Détail""", "{\"verdict_d_abord\":true,\"chemin_parcouru\":false,\"commentaire\":\"ok\"}"), "3");
        JarvisOralBenchService.Report report = bench.run(alice, UUID.randomUUID(), subject, OffsetDateTime.now());

        assertThat(report.status()).isEqualTo("DONE");
        assertThat(report.scenarios()).isEqualTo(10);
        assertThat(report.wordsP90()).isEqualTo(7);
        assertThat(report.verdictFirst()).isEqualTo(10);
        // La réponse invite partout, même sur la confirmation, et ne demande pas confirmation : la porte le voit.
        assertThat(report.gatePassed()).isFalse();
        assertThat(report.gateFailures()).contains("O7 : invitation sur une confirmation",
                "O7 : confirmation non demandée");
        List<AgentTurnRequest> opus = calls.stream().filter(c -> c.tools().isEmpty()).toList();
        assertThat(opus).hasSize(10);
        assertThat(opus.get(0).system()).isEqualTo("CONSIGNE SYSTEME DU TERMINAL");
        assertThat(opus.get(0).model()).isEqualTo("claude-opus-5-5");
        String message = opus.get(0).messages().get(0).toString();
        assertThat(message).contains("Fais-moi le point sur CAGIP.").contains(JarvisOralNote.TEXT.substring(0, 60));
    }

    @Test
    void theRunStopsBeforeTheCap() {
        JarvisOralBenchService bench = service(provider("🎙 C'est fait.",
                "{\"verdict_d_abord\":true,\"chemin_parcouru\":false,\"commentaire\":\"ok\"}"), "1");
        ProviderCostCalculator costs = mock(ProviderCostCalculator.class);
        when(costs.calculate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new TurnCost(new BigDecimal("0.40"), TurnCost.Source.CALCULATED, "m", "v", false));
        bench.setAccounting(null, null, costs, null);
        JarvisOralBenchService.Report report = bench.run(alice, UUID.randomUUID(), subject, OffsetDateTime.now());
        // 0,40 $ × 0,92 × 2 (réponse + juge) ≈ 0,74 € par scénario : le 2e dépasserait 1 €.
        assertThat(report.scenarios()).isEqualTo(1);
        assertThat(report.gateFailures()).anyMatch(f -> f.startsWith("plafond de 1 € atteint avant O2"));
        assertThat(report.costEur()).isLessThanOrEqualTo(new BigDecimal("1"));
    }

    @Test
    void aJudgeWithoutVerdictFailsTheGate() {
        JarvisOralBenchService bench = service(provider("🎙 C'est fait.", "{}"), "3");
        JarvisOralBenchService.Report report = bench.run(alice, UUID.randomUUID(), subject, OffsetDateTime.now());
        assertThat(report.verdictFirst()).isZero();
        assertThat(report.gatePassed()).isFalse();
    }
}
