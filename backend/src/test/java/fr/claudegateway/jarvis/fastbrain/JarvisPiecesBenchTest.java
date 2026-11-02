package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.JarvisUsageService;

/** F-237 / SF-237-13 — le banc « Jarvis montre les pièces » : jeu, jugement déterministe, porte. */
class JarvisPiecesBenchTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JarvisShowCommand show(String json) throws Exception {
        return JarvisShowCommand.parse(JSON.readTree(json), 3).command();
    }

    private static JarvisFastBrainService.Outcome answer(String said, JarvisShowCommand... shows) {
        return new JarvisFastBrainService.Outcome(JarvisFastBrainService.Kind.ANSWER, said, null, null, "m", 10L, null,
                JarvisFastBrainService.Guards.NONE, null, List.of(shows));
    }

    private static JarvisFastBrainService.Outcome delegated() {
        return new JarvisFastBrainService.Outcome(JarvisFastBrainService.Kind.DELEGATE, "", new JarvisFastBrainService
                .Delegation(UUID.randomUUID(), "générer la preuve", "preuve", "Je génère la preuve.", null, 5, false,
                        null), null, "m", 10L, null);
    }

    private static JarvisPiecesBench.Case find(JarvisPiecesBench.Set set, String id) {
        return set.cases().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void theSetHasAtLeastThirtyCasesAcrossTheFourCategories() {
        JarvisPiecesBench.Set set = JarvisPiecesBench.load();
        assertThat(set.cases()).hasSizeGreaterThanOrEqualTo(30);
        assertThat(set.cases()).extracting(JarvisPiecesBench.Case::category)
                .contains("SHOW", "NAVIGATE", "PROOF", "NEUTRAL");
        assertThat(set.context()).contains("## Pièces").doesNotContain("## Écran");
        // Un cas de navigation porte son écran ; un cas de preuve consentie porte l'échange précédent.
        assertThat(JarvisPiecesBench.contextFor(set, find(set, "N01"))).contains("## Écran\nGalerie : Vidéos");
        assertThat(JarvisPiecesBench.contextFor(set, find(set, "P07"))).contains("Voulez-vous que je la génère");
    }

    @Test
    void aProactiveGalleryWithTheRightScopePasses() throws Exception {
        JarvisPiecesBench.Set set = JarvisPiecesBench.load();
        JarvisFastBrainBenchService.Verdict ok = JarvisPiecesBench.evaluate(find(set, "P01"), answer(
                "Oui, voici les preuves du chantier 3.", show("""
                        {"action":"galerie","portee":{"chantier":3,"categorie":"preuves"},"raison":"appui"}""")));
        assertThat(ok.routeAgreed()).isTrue();
        JarvisFastBrainBenchService.Verdict wrongScope = JarvisPiecesBench.evaluate(find(set, "P01"), answer(
                "Voici.", show("""
                        {"action":"galerie","portee":{"chantier":3,"categorie":"entrees"},"raison":"appui"}""")));
        assertThat(wrongScope.routeAgreed()).isFalse();
        assertThat(wrongScope.comment()).contains("ordre inattendu");
        JarvisFastBrainBenchService.Verdict nothing = JarvisPiecesBench.evaluate(find(set, "S01"),
                answer("Le chantier 3 est terminé."));
        assertThat(nothing.comment()).contains("aucun affichage");
    }

    @Test
    void navigationIsJudgedOnTheIndexOfTheScreen() throws Exception {
        JarvisPiecesBench.Set set = JarvisPiecesBench.load();
        assertThat(JarvisPiecesBench.evaluate(find(set, "N01"), answer("Voici le cours sur Terraform.", show("""
                {"action":"selection","index":2,"raison":"demande"}"""))).routeAgreed()).isTrue();
        assertThat(JarvisPiecesBench.evaluate(find(set, "N01"), answer("Voici.", show("""
                {"action":"selection","index":3,"raison":"demande"}"""))).routeAgreed()).isFalse();
    }

    @Test
    void missingProofIsProposedNeverGeneratedWithoutYes() {
        JarvisPiecesBench.Set set = JarvisPiecesBench.load();
        assertThat(JarvisPiecesBench.evaluate(find(set, "P05"), answer(
                "Le chantier 5 est marqué terminé, mais je n'ai aucune preuve en image. Voulez-vous que je la génère ?"))
                .routeAgreed()).isTrue();
        JarvisFastBrainBenchService.Verdict rushed = JarvisPiecesBench.evaluate(find(set, "P05"), delegated());
        assertThat(rushed.actionNotDelegated()).as("délégation sans consentement").isTrue();
        assertThat(JarvisPiecesBench.evaluate(find(set, "P07"), delegated()).routeAgreed()).isTrue();
    }

    @Test
    void theGateCountsNeutralDisplaysConsentAndRates() throws Exception {
        JarvisPiecesBench.Set set = JarvisPiecesBench.load();
        List<JarvisFastBrainBenchService.Verdict> verdicts = new ArrayList<>();
        verdicts.add(JarvisPiecesBench.evaluate(find(set, "Z01"), answer("Avec plaisir.", show("""
                {"action":"galerie","portee":{"chantier":3},"raison":"appui"}"""))));
        verdicts.add(JarvisPiecesBench.evaluate(find(set, "P05"), delegated()));
        verdicts.add(JarvisPiecesBench.evaluate(find(set, "S01"), answer("Le chantier 3 est terminé.")));
        List<String> failures = JarvisPiecesBench.gate(verdicts);
        assertThat(failures).anyMatch(f -> f.contains("phrase neutre"))
                .anyMatch(f -> f.contains("sans « oui »"))
                .anyMatch(f -> f.startsWith("SHOW"));
        assertThat(JarvisPiecesBench.gate(List.of(JarvisPiecesBench.evaluate(find(set, "Z01"),
                answer("Avec plaisir."))))).isEmpty();
    }

    @Test
    void benchReadsTheFixedPiecesNeverTheDatabase() throws Exception {
        List<AgentTurnRequest> calls = new ArrayList<>();
        AgentTurn[] turns = {
            new AgentTurn("", List.of(new AgentToolCall("p1", "pieces", JSON.readTree("""
                    {"sujet":"","chantier":3,"categorie":"","type":"video","recherche":"deploiement"}"""))), false,
                    10, 5),
            new AgentTurn("Voici la démo du déploiement sur EKS.", List.of(new AgentToolCall("s1", "afficher",
                    JSON.readTree("""
                            {"action":"piece","pieces":["video:c7@v2"],"raison":"demande"}"""))), true, 10, 5) };
        AiAgentProvider provider = new AiAgentProvider() {
            @Override
            public AgentTurn nextTurn(AgentTurnRequest request) {
                return nextTurn(request, d -> { });
            }

            @Override
            public AgentTurn nextTurn(AgentTurnRequest request, AgentTextListener listener) {
                calls.add(request);
                AgentTurn t = turns[Math.min(calls.size() - 1, 1)];
                if (!t.text().isEmpty()) {
                    listener.onTextDelta(t.text());
                }
                return t;
            }
        };
        JarvisUsageService usage = mock(JarvisUsageService.class);
        when(usage.monthFastBrainCostUsd(any())).thenReturn(BigDecimal.ZERO);
        JarvisFastBrainService service = new JarvisFastBrainService(provider, new JarvisFastBrainProperties(true,
                null, null, null, null, null), usage, mock(WorkspaceRepository.class),
                Clock.fixed(Instant.parse("2026-11-03T09:15:00Z"), ZoneOffset.UTC));
        JarvisPiecesBench.Set set = JarvisPiecesBench.load();
        JarvisPiecesBench.Case c = find(set, "S13");

        JarvisFastBrainService.Outcome outcome = service.benchWithPieces(new JarvisFastBrainService.Request(
                UUID.randomUUID(), null, null, c.text(), null), "claude-haiku-5-5", JarvisPiecesBench.contextFor(set,
                        c), set.pieces());

        AgentContentBlock.ToolResult result = (AgentContentBlock.ToolResult) calls.get(1).messages()
                .get(calls.get(1).messages().size() - 1).content().get(0);
        assertThat(result.content()).isEqualTo(set.pieces());
        assertThat(JarvisPiecesBench.evaluate(c, outcome).routeAgreed()).isTrue();
    }
}
