package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.AiAgentProvider.AgentTextListener;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.jarvis.portrait.JarvisAllusionService;
import fr.claudegateway.jarvis.portrait.JarvisPortraitNote;
import fr.claudegateway.jarvis.portrait.JarvisPortraitService;

/**
 * F-241 / SF-241-04 — avoir l'information ne veut pas dire s'en servir : ✦ jamais dit et journalisé, allusion non
 * déclarée rattrapée, une spontanée par jour (D12 b), jamais un sujet récent (D12 c), une allusion amenée n'est ni
 * écartée ni spontanée, la trace part dans le message (jamais la consigne), isolation.
 */
class JarvisFastBrainAllusionTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-02T08:00:00Z");
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final List<AgentTurnRequest> calls = new ArrayList<>();
    private JarvisAllusionService allusions;

    @BeforeEach
    void setUp() {
        allusions = mock(JarvisAllusionService.class);
        when(allusions.recent(any())).thenReturn(List.of());
    }

    private JarvisFastBrainService service(String reply) {
        JarvisPortraitService portraits = mock(JarvisPortraitService.class);
        JarvisPortraitService.Snapshot p = JarvisPortraitNote.fixture(Map.of(
                "ENTOURAGE", "Sa compagne Nadia ; leur fils Léo est né en septembre 2026.",
                "GOUTS", "Prépare le semi-marathon de Lyon."), List.of(), NOW.minusDays(30));
        when(portraits.snapshot(alice)).thenReturn(Optional.of(p));
        when(portraits.snapshot(bob)).thenReturn(Optional.empty());
        JarvisUsageService usage = mock(JarvisUsageService.class);
        when(usage.monthFastBrainCostUsd(any())).thenReturn(BigDecimal.ZERO);
        JarvisFastBrainService s = new JarvisFastBrainService(new AiAgentProvider() {
            @Override
            public AgentTurn nextTurn(AgentTurnRequest request) {
                calls.add(request);
                return new AgentTurn(reply, List.of(), true, 100, 10);
            }

            @Override
            public AgentTurn nextTurn(AgentTurnRequest request, AgentTextListener listener) {
                calls.add(request);
                listener.onTextDelta(reply);
                return new AgentTurn(reply, List.of(), true, 100, 10);
            }
        }, new JarvisFastBrainProperties(true, null, null, null, null, null), usage, mock(WorkspaceRepository.class),
                Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneOffset.UTC));
        s.setPortraits(portraits);
        s.setAllusions(allusions);
        return s;
    }

    private JarvisFastBrainService.Request ask(UUID user, String text) {
        return new JarvisFastBrainService.Request(user, null, null, text, null, null, false);
    }

    private static String lastMessage(AgentTurnRequest r) {
        return ((AgentContentBlock.Text) r.messages().get(r.messages().size() - 1).content().get(0)).text();
    }

    @Test
    void aDeclarationIsNeverSaidAndIsJournaled() {
        StringBuilder heard = new StringBuilder();
        JarvisFastBrainService.Outcome o = service("✦ humour: le café du lundi\nUn lundi bien serré, Monsieur.")
                .converse(ask(alice, "Bonjour Jarvis"), heard::append);
        assertThat(o.spokenText()).isEqualTo("Un lundi bien serré, Monsieur.").doesNotContain("✦");
        assertThat(heard.toString()).doesNotContain("✦");
        verify(allusions).record(eq(alice), eq("HUMOUR"), eq("le café du lundi"), anyString(), eq(true));
    }

    @Test
    void anUndeclaredAllusionIsCaughtAndMeasuredAsSpontaneous() {
        JarvisFastBrainService.Outcome o = service("Bonjour, Monsieur. Léo vous laisse dormir ?")
                .converse(ask(alice, "Bonjour Jarvis"), null);
        assertThat(o.spokenText()).contains("Léo");
        assertThat(o.guards().personalAllusion()).isEqualTo("SPONTANEE");
        verify(allusions).record(eq(alice), eq("ALLUSION"), eq("leo"), anyString(), eq(true));
    }

    @Test
    void aSecondSpontaneousAllusionOfTheDayIsNotSaid() {
        when(allusions.recent(alice)).thenReturn(List.of(new JarvisAllusionService.Entry(NOW.minusHours(1),
                "ALLUSION", "le semi-marathon", null, true)));
        JarvisFastBrainService.Outcome o = service("Bonjour, Monsieur. Léo vous laisse dormir ?")
                .converse(ask(alice, "Bonjour Jarvis"), null);
        assertThat(o.spokenText()).isEqualTo("Bonjour, Monsieur.");
        assertThat(o.guards().allusionsBlocked()).isEqualTo(1);
        assertThat(lastMessage(calls.get(0))).contains("déjà dit récemment")
                .contains("allusion spontanée déjà faite aujourd'hui : oui");
        assertThat(calls.get(0).system()).doesNotContain("« le semi-marathon »"); // la trace, volatile, jamais dans la consigne
    }

    @Test
    void aRecentTopicIsNotRepeated() {
        when(allusions.recent(alice)).thenReturn(List.of(new JarvisAllusionService.Entry(NOW.minusDays(3),
                "ALLUSION", "Léo et ses nuits", null, false)));
        JarvisFastBrainService.Outcome o = service("Bonjour, Monsieur. Léo vous laisse dormir ?")
                .converse(ask(alice, "Bonjour Jarvis"), null);
        assertThat(o.spokenText()).isEqualTo("Bonjour, Monsieur.");
        assertThat(o.guards().allusionsBlocked()).isEqualTo(1);
    }

    @Test
    void anAllusionBroughtByThePoIsNeitherBlockedNorSpontaneous() {
        when(allusions.recent(alice)).thenReturn(List.of(new JarvisAllusionService.Entry(NOW.minusHours(1),
                "ALLUSION", "Léo", null, true)));
        JarvisFastBrainService.Outcome o = service("Léo grandit vite, Monsieur.")
                .converse(ask(alice, "Je suis crevé, le petit ne dort pas."), null);
        assertThat(o.spokenText()).isEqualTo("Léo grandit vite, Monsieur.");
        assertThat(o.guards().allusionsBlocked()).isZero();
        assertThat(o.guards().personalAllusion()).isEqualTo("REPONSE");
        verify(allusions).record(eq(alice), eq("ALLUSION"), eq("leo"), anyString(), eq(false));
    }

    @Test
    void anotherAccountsTraceIsNeverRead() {
        service("Bonjour.").converse(ask(bob, "Bonjour"), null);
        verify(allusions, never()).recent(alice);
        verify(allusions, never()).recent(bob); // sans portrait, aucune trace lue
        verify(allusions, never()).record(any(), anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void benchUsesTheFictiveTraceOnly() {
        JarvisPortraitService.Snapshot p = JarvisPortraitNote.fixture(Map.of("ENTOURAGE", "Leur fils Léo."), List.of(),
                NOW);
        JarvisFastBrainService.Outcome o = service("Bonjour, Monsieur. Léo dort mieux ?").benchWithPortrait(
                ask(alice, "Bonjour"), "claude-sonnet-5-5", "Maintenant : lundi", p,
                List.of("aujourd'hui: le semi-marathon"));
        assertThat(o.spokenText()).isEqualTo("Bonjour, Monsieur.");
        assertThat(lastMessage(calls.get(0))).contains("« le semi-marathon »");
        verify(allusions, never()).recent(any());
        verify(allusions, never()).record(any(), anyString(), anyString(), any(), anyBoolean());
    }
}
