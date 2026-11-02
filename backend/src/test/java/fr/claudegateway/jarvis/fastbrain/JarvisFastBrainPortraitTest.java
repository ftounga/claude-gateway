package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import fr.claudegateway.agent.AiAgentProvider.AgentTextListener;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.jarvis.portrait.JarvisPortraitNote;
import fr.claudegateway.jarvis.portrait.JarvisPortraitService;

/**
 * F-241 / SF-241-03 — le portrait dans le cerveau rapide : fin de consigne système (identique à l'octet près sans
 * portrait), jamais en présentation, relu d'un appel à l'autre, interdits filtrés (D12 a), ancrage des faits.
 */
class JarvisFastBrainPortraitTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final List<AgentTurnRequest> calls = new ArrayList<>();
    private JarvisPortraitService portraits;
    private JarvisPortraitService.Snapshot alicePortrait;

    @BeforeEach
    void setUp() {
        portraits = mock(JarvisPortraitService.class);
        alicePortrait = JarvisPortraitNote.fixture(Map.of(
                "ENTOURAGE", "Sa compagne Claire ; leur fils Léo est né en septembre 2026.",
                "INTERDITS", "Le divorce."), List.of("Prépare le semi-marathon de Lyon"),
                OffsetDateTime.parse("2026-10-01T09:00:00Z"));
        when(portraits.snapshot(alice)).thenReturn(Optional.of(alicePortrait));
        when(portraits.snapshot(bob)).thenReturn(Optional.empty());
    }

    private JarvisFastBrainService service(String reply) {
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
        return s;
    }

    private static JarvisFastBrainService.Request ask(UUID user, String text, boolean presentation) {
        return new JarvisFastBrainService.Request(user, null, null, text, null, null, presentation);
    }

    private static String lastMessage(AgentTurnRequest r) {
        return ((AgentContentBlock.Text) r.messages().get(r.messages().size() - 1).content().get(0)).text();
    }

    @Test
    void withoutPortraitTheSystemPromptIsByteIdenticalToBefore() {
        service("Bonjour, Monsieur.").converse(ask(bob, "Bonjour", false), null);
        assertThat(calls.get(0).system()).isEqualTo(JarvisFastBrainPrompt.system());
    }

    @Test
    void portraitGoesAtTheEndOfTheSystemPromptAndStaysIdenticalBetweenCalls() {
        JarvisFastBrainService s = service("Bonjour, Monsieur.");
        s.converse(ask(alice, "Bonjour", false), null);
        s.converse(ask(alice, "Merci", false), null);
        String system = calls.get(0).system();
        assertThat(system).startsWith(JarvisFastBrainPrompt.system())
                .endsWith(JarvisPortraitNote.block(alicePortrait))
                .contains(JarvisPortraitNote.RULES).contains("Léo").contains("mis à jour en octobre 2026")
                .contains("S1 (noté en octobre 2026) : Prépare le semi-marathon de Lyon");
        assertThat(calls.get(1).system()).isEqualTo(system);
        // Rien du portrait dans le message (il n'est pas volatil) ; l'heure, elle, y reste.
        assertThat(lastMessage(calls.get(0))).doesNotContain("Léo");
    }

    @Test
    void presentationGivesTheBasePromptAndTheFrozenNoteNeverThePortrait() {
        service("Nous en parlerons en privé, Monsieur.").converse(ask(alice, "Comment va mon fils ?", true), null);
        assertThat(calls.get(0).system()).isEqualTo(JarvisFastBrainPrompt.system());
        assertThat(lastMessage(calls.get(0))).contains(JarvisPortraitNote.PRESENTATION_NOTE).doesNotContain("Léo");
        verify(portraits, never()).snapshot(alice);
    }

    @Test
    void aForbiddenTopicNotRaisedByThePoIsNeverSaid() {
        JarvisFastBrainService.Outcome o = service("Le week-end idéal pour oublier le divorce. Un peu de jazz ?")
                .converse(ask(alice, "Qu'est-ce que je fais ce week-end ?", false), null);
        assertThat(o.spokenText()).doesNotContain("divorce").contains("jazz");
        assertThat(o.guards().portraitBlocked()).isEqualTo(1);
    }

    @Test
    void aForbiddenTopicRaisedByThePoIsAnsweredSoberly() {
        JarvisFastBrainService.Outcome o = service("Le divorce avance, Monsieur ?")
                .converse(ask(alice, "Mon avocat m'a écrit pour le divorce.", false), null);
        assertThat(o.guards().portraitBlocked()).isZero();
        assertThat(o.spokenText()).contains("divorce");
    }

    @Test
    void aFactFromThePortraitIsGroundedNotDelegated() {
        JarvisFastBrainService.Outcome o = service("Léo vous fait des nuits courtes, Monsieur.")
                .converse(ask(alice, "Je suis crevé, le petit ne dort pas.", false), null);
        assertThat(o.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(o.spokenText()).contains("Léo");
    }

    @Test
    void anotherAccountsPortraitIsNeverLoaded() {
        service("Bonjour.").converse(ask(bob, "Bonjour", false), null);
        verify(portraits, never()).snapshot(alice);
        assertThat(calls.get(0).system()).doesNotContain("Léo");
    }

    @Test
    void benchUsesOnlyTheFixture() {
        JarvisFastBrainService s = service("Bonjour.");
        s.benchWithPortrait(ask(alice, "Bonjour", false), "claude-sonnet-5-5", "Maintenant : lundi", null);
        assertThat(calls.get(0).system()).isEqualTo(JarvisFastBrainPrompt.system());
        verify(portraits, never()).snapshot(any());
        s.benchWithPortrait(ask(alice, "Bonjour", false), "claude-sonnet-5-5", "Maintenant : lundi", alicePortrait);
        assertThat(calls.get(1).system()).endsWith(JarvisPortraitNote.block(alicePortrait));
    }

    @Test
    void switchedOffOrUnreadablePortraitGivesTheBasePrompt() {
        when(portraits.snapshot(alice)).thenThrow(new IllegalStateException("scellé dormant"));
        service("Bonjour.").converse(ask(alice, "Bonjour", false), null);
        assertThat(calls.get(0).system()).isEqualTo(JarvisFastBrainPrompt.system());
    }
}
