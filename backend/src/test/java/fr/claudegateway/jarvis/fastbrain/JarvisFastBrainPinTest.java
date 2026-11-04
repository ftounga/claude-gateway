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

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.AiAgentProvider.AgentTextListener;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.jarvis.portrait.JarvisPortraitNote;
import fr.claudegateway.jarvis.portrait.JarvisPortraitService;

/**
 * F-241 / SF-241-05 — le portrait vivant : 📌 relayé (jamais dit), retenir / oublier sans délégation, une seule
 * proposition et jamais avec une confirmation, rien en présentation, relance au bon moment seulement.
 */
class JarvisFastBrainPinTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-02T08:00:00Z");
    private final UUID alice = UUID.randomUUID();
    private final List<AgentTurnRequest> calls = new ArrayList<>();
    private JarvisPortraitService portraits;
    private JarvisFastMemory memory;
    private JarvisDelegationRegistry delegations;

    @BeforeEach
    void setUp() {
        portraits = mock(JarvisPortraitService.class);
        JarvisPortraitService.Snapshot p = JarvisPortraitNote.fixture(Map.of("GOUTS", "Le jazz manouche."),
                List.of("Prépare le semi-marathon de Lyon en avril", "Aime le café serré"), NOW.minusDays(120));
        when(portraits.snapshot(alice)).thenReturn(Optional.of(p));
        memory = mock(JarvisFastMemory.class);
        when(memory.recall(any())).thenReturn(List.of());
        delegations = mock(JarvisDelegationRegistry.class);
        when(delegations.running(any())).thenReturn(List.of());
    }

    private JarvisFastBrainService service(AiAgentProvider provider) {
        JarvisUsageService usage = mock(JarvisUsageService.class);
        when(usage.monthFastBrainCostUsd(any())).thenReturn(BigDecimal.ZERO);
        JarvisFastBrainService s = new JarvisFastBrainService(provider,
                new JarvisFastBrainProperties(true, null, null, null, null, null), usage,
                mock(WorkspaceRepository.class), Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneOffset.UTC));
        s.setPortraits(portraits);
        s.setContext(null, memory, delegations);
        return s;
    }

    private AiAgentProvider answering(String reply) {
        return new AiAgentProvider() {
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
        };
    }

    private AiAgentProvider delegating() {
        return request -> {
            calls.add(request);
            try {
                return new AgentTurn("", List.of(new AgentToolCall("t1", "deleguer", new ObjectMapper().readTree(
                        "{\"demande\":\"retenir\",\"label\":\"\",\"annonce\":\"Je m'en occupe.\",\"sujet\":\"\"}"))),
                        false, 100, 10);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private JarvisFastBrainService.Request ask(String text, boolean presentation) {
        return new JarvisFastBrainService.Request(alice, null, null, text, null, null, presentation);
    }

    private static String lastMessage(AgentTurnRequest r) {
        return ((AgentContentBlock.Text) r.messages().get(r.messages().size() - 1).content().get(0)).text();
    }

    @Test
    void anExplicitRememberIsNotedNeverDelegated() {
        StringBuilder heard = new StringBuilder();
        JarvisFastBrainService.Outcome o = service(delegating())
                .converse(ask("Retiens que je pars en vacances le 20 décembre.", false), heard::append);
        assertThat(o.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(o.delegation()).isNull();
        assertThat(o.guards().pins()).extracting(JarvisPortraitNote.Pin::action, JarvisPortraitNote.Pin::text)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("noter", "je pars en vacances le 20 décembre"));
        assertThat(heard.toString()).contains("C'est noté").doesNotContain("📌");
    }

    @Test
    void theModelsOwnPinIsRelayedAndNeverSaid() {
        StringBuilder heard = new StringBuilder();
        JarvisFastBrainService.Outcome o = service(answering(
                "C'est noté : vacances le 20 décembre.\n📌 noter: Vacances à partir du 20 décembre"))
                .converse(ask("Retiens que je pars en vacances le 20 décembre.", false), heard::append);
        assertThat(o.spokenText()).isEqualTo("C'est noté : vacances le 20 décembre.");
        assertThat(heard.toString()).doesNotContain("📌");
        assertThat(o.guards().pins()).singleElement().extracting(JarvisPortraitNote.Pin::text)
                .isEqualTo("Vacances à partir du 20 décembre");
    }

    @Test
    void aProposalIsRelayedOnceAndNeverWithAConfirmation() {
        JarvisFastBrainService.Outcome o = service(answering(
                "Félicitations, Monsieur. Voulez-vous que je retienne que votre femme accouche en mars ?\n"
                        + "📌 retenir: Sa compagne accouche en mars\n📌 retenir: Un garçon attendu"))
                .converse(ask("Ma femme accouche en mars.", false), null);
        assertThat(o.guards().pins()).singleElement().extracting(JarvisPortraitNote.Pin::action).isEqualTo("retenir");
        JarvisFastBrainService.Outcome withConfirm = service(answering(
                "Je prépare le mail. Vous confirmez ?\n📌 retenir: Sa compagne accouche en mars"))
                .converse(ask("Ma femme accouche en mars.", false), null);
        assertThat(withConfirm.guards().pins()).isEmpty();
    }

    @Test
    void forgetTargetsTheRightSouvenirWithoutDelegation() {
        JarvisFastBrainService.Outcome o = service(delegating())
                .converse(ask("Oublie que je prépare le semi-marathon.", false), null);
        assertThat(o.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(o.spokenText()).isEqualTo(JarvisPortraitNote.FORGOTTEN);
        assertThat(o.guards().pins()).singleElement().extracting(JarvisPortraitNote.Pin::number).isEqualTo(1);
        JarvisFastBrainService.Outcome none = service(delegating())
                .converse(ask("Oublie que j'ai un chat.", false), null);
        assertThat(none.spokenText()).isEqualTo(JarvisPortraitNote.NOTHING_TO_FORGET);
        assertThat(none.guards().pins()).isEmpty();
    }

    @Test
    void inPresentationNothingIsRememberedOrRelayed() {
        JarvisFastBrainService.Outcome o = service(answering("C'est noté.\n📌 noter: La crèche lundi"))
                .converse(ask("Retiens que ma fille entre à la crèche lundi.", true), null);
        assertThat(o.guards().pins()).isEmpty();
        assertThat(o.spokenText()).isEqualTo(JarvisPortraitNote.PRIVATE); // rien n'est retenu : on le dit
        JarvisFastBrainService.Outcome delegated = service(delegating())
                .converse(ask("Retiens que ma fille entre à la crèche lundi.", true), null);
        assertThat(delegated.spokenText()).isEqualTo(JarvisPortraitNote.PRIVATE);
        assertThat(delegated.guards().pins()).isEmpty();
    }

    @Test
    void theCheckinIsOfferedOnlyInCalmSmallTalkAndDatedWhenAsked() {
        when(memory.recall(alice)).thenReturn(List.of(new JarvisFastMemory.Exchange(Instant.now(), "Bonjour",
                "Bonjour, Monsieur.")));
        service(answering("Calme, en effet. Du nouveau depuis notre dernier entretien ?"))
                .converse(ask("Calme, la matinée.", false), null);
        assertThat(lastMessage(calls.get(0))).contains(JarvisPortraitNote.CHECKIN_NOTE);
        verify(portraits).markCheckin(alice);
    }

    @Test
    void noCheckinOnTheFirstGreetingNorDuringATask() {
        service(answering("Bonjour, Monsieur.")).converse(ask("Bonjour Jarvis.", false), null);
        assertThat(lastMessage(calls.get(0))).doesNotContain("relance du portrait");
        when(memory.recall(alice)).thenReturn(List.of(new JarvisFastMemory.Exchange(Instant.now(), "Bonjour",
                "Bonjour, Monsieur.")));
        service(answering("Le chantier 1 en est à la recette.")).converse(ask("Où en est le chantier 1 ?", false),
                null);
        assertThat(lastMessage(calls.get(1))).doesNotContain("relance du portrait");
        verify(portraits, never()).markCheckin(any());
    }

    @Test
    void benchMarksAreCheckedWithoutAModel() {
        JarvisConversationBench.Turn noter = new JarvisConversationBench.Turn("Retiens que…", "repondre", "noter");
        assertThat(JarvisConversationBench.markDefect(noter, List.of(), "C'est noté.")).contains("noter absente");
        assertThat(JarvisConversationBench.markDefect(noter,
                List.of(new JarvisPortraitNote.Pin("noter", "x", null)), "C'est noté.")).isNull();
        JarvisConversationBench.Turn none = new JarvisConversationBench.Turn("x", "any", "aucune");
        assertThat(JarvisConversationBench.markDefect(none,
                List.of(new JarvisPortraitNote.Pin("retenir", "x", null)), "")).contains("inattendue");
        JarvisConversationBench.Turn task = new JarvisConversationBench.Turn("x", "any", "sans_relance");
        assertThat(JarvisConversationBench.markDefect(task, List.of(),
                "Du nouveau depuis notre dernier entretien ?")).isEqualTo("relance pendant une tâche");
        assertThat(JarvisConversationBench.load().scenarios()).hasSize(59);
    }
}
