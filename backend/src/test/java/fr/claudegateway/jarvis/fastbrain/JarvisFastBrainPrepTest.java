package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.AiAgentProvider.AgentTextListener;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.jarvis.cards.JarvisCard;
import fr.claudegateway.jarvis.cards.JarvisCardCatalog;
import fr.claudegateway.jarvis.preferences.JarvisSobriety;
import fr.claudegateway.jarvis.state.JarvisStateBlock;
import fr.claudegateway.jarvis.state.JarvisStateTool;

/**
 * F-252 / SF-252-01 — la préparation parallèle ne change rien de ce que reçoit le modèle (CA1), elle est mesurée
 * ({@code prepared}, CA5) et plus rapide (CA2, à l'échelle de la réplique).
 */
class JarvisFastBrainPrepTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-08T09:00:00Z");
    private static final long READ_MS = 80L;

    private final UUID alice = UUID.randomUUID();
    private final UUID host = UUID.randomUUID();
    private Workspace workspace;
    private JarvisUsageService usage;
    private JarvisStateBlock block;
    private JarvisFastContextBuilder context;
    private JarvisSobriety sobriety;
    private final List<AgentTurnRequest> calls = new ArrayList<>();

    private static void pause() {
        try {
            Thread.sleep(READ_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static JarvisCard card(String id) {
        return new JarvisCard(id, "ticket", id, null, List.of(), null, null, "tour", null, List.of(), false);
    }

    @BeforeEach
    void setUp() {
        workspace = Workspace.builder().id(UUID.randomUUID()).userId(alice).name("SSO").hostId(host).build();
        usage = mock(JarvisUsageService.class);
        when(usage.monthFastBrainCostUsd(any())).thenAnswer(inv -> {
            pause();
            return BigDecimal.ZERO;
        });
        block = mock(JarvisStateBlock.class);
        String state = "## État connu — client CAGIP\n- PROJ-123 « Migration SSO » : En revue (vu hier 17:42, tour)";
        when(block.ready(eq(alice), eq(host), any())).thenAnswer(inv -> {
            pause();
            return new JarvisStateBlock.Selected(state, List.of(new JarvisStateBlock.Line(host, null, "JIRA:PROJ-123",
                    "PROJ-123", state, OffsetDateTime.parse("2026-11-07T16:42:00Z"), false, false)), 1000L);
        });
        context = mock(JarvisFastContextBuilder.class);
        when(context.build(eq(alice), any(), any(), any(), anyBoolean())).thenAnswer(inv -> {
            pause();
            JarvisCardCatalog.collect(() -> card("ATTENTE-1"));
            return "## Maintenant\nsamedi 8 novembre 2026, 10:00\nSujet courant : SSO";
        });
        when(context.reference(alice)).thenAnswer(inv -> {
            pause();
            JarvisCardCatalog.collect(() -> card("PERSONNE-1"));
            return "## Univers\nClient CAGIP (poste connecté)\n- Sujet SSO";
        });
        sobriety = mock(JarvisSobriety.class);
        when(sobriety.sober(any(), any())).thenAnswer(inv -> {
            pause();
            return true;
        });
    }

    @AfterEach
    void tearDown() {
        JarvisCardCatalog.close();
    }

    private AiAgentProvider provider(String reply) {
        return new AiAgentProvider() {
            @Override
            public AgentTurn nextTurn(AgentTurnRequest request) {
                return nextTurn(request, d -> { });
            }

            @Override
            public AgentTurn nextTurn(AgentTurnRequest request, AgentTextListener listener) {
                calls.add(request);
                listener.onTextDelta(reply);
                return new AgentTurn(reply, List.of(), true, 100, 10);
            }
        };
    }

    private JarvisFastBrainService service(boolean parallel, String reply) {
        JarvisFastBrainService s = new JarvisFastBrainService(provider(reply), new JarvisFastBrainProperties(true, null,
                null, null, null, null), usage, mock(WorkspaceRepository.class), Clock.fixed(NOW, ZoneOffset.UTC));
        s.setState(block, mock(JarvisStateTool.class));
        s.setContext(context, new JarvisFastMemory(Clock.fixed(NOW, ZoneOffset.UTC)), null);
        s.setPersonality(null, sobriety);
        s.setParallelPrep(parallel);
        return s;
    }

    private record Run(String messages, String system, List<String> cards, String said, List<Long> prepared,
            long ms) {
    }

    private Run run(boolean parallel, String text) throws Exception {
        calls.clear();
        JarvisCardCatalog catalog = JarvisCardCatalog.open();
        List<Long> prepared = new ArrayList<>();
        long started = System.nanoTime();
        JarvisFastBrainService.Outcome outcome = service(parallel, "PROJ-123 est en revue, Monsieur.").converse(
                new JarvisFastBrainService.Request(alice, workspace, null, text, null, null, false),
                new JarvisFastBrainService.Sink() {
                    @Override
                    public void text(String delta) {
                    }

                    @Override
                    public void prepared(long prepMs) {
                        prepared.add(prepMs);
                    }
                });
        long ms = (System.nanoTime() - started) / 1_000_000L;
        JarvisCardCatalog.Event event = catalog.takeIfChanged();
        JarvisCardCatalog.close();
        return new Run(calls.isEmpty() ? "" : JSON.writeValueAsString(calls.get(0).messages()),
                calls.isEmpty() ? "" : calls.get(0).system(),
                event == null ? List.of() : event.cards().stream().map(JarvisCard::id).toList(),
                outcome.spokenText(), prepared, ms);
    }

    @Test
    @DisplayName("CA1 — même message, même consigne, mêmes pancartes, même réponse : avec ou sans parallélisme")
    void sameCallBothWays() throws Exception {
        Run serial = run(false, "Où en est PROJ-123 ?");
        Run parallel = run(true, "Où en est PROJ-123 ?");
        assertThat(parallel.messages()).isEqualTo(serial.messages()).contains("État connu — client CAGIP")
                .contains("Client CAGIP").contains(JarvisSobriety.NOTE.substring(0, 20));
        assertThat(parallel.system()).isEqualTo(serial.system());
        assertThat(parallel.cards()).isEqualTo(serial.cards()).containsExactly("ATTENTE-1", "PERSONNE-1");
        // D8 (F-247) : la garde de fraîcheur a vu les lignes d'état lues sur le fil parallèle — même phrase datée.
        assertThat(parallel.said()).isEqualTo(serial.said());
    }

    @Test
    @DisplayName("CA2/CA5 — la préparation est mesurée une fois, avant l'appel, et plus courte en parallèle")
    void preparedIsMeasuredAndShorter() throws Exception {
        Run serial = run(false, "Où en est PROJ-123 ?");
        Run parallel = run(true, "Où en est PROJ-123 ?");
        assertThat(serial.prepared()).hasSize(1);
        assertThat(parallel.prepared()).hasSize(1);
        // 5 lectures de 80 ms : ≥ 400 ms en série, une seule « lecture » de profondeur en parallèle.
        assertThat(serial.prepared().get(0)).isGreaterThanOrEqualTo(5 * READ_MS);
        assertThat(parallel.prepared().get(0)).isLessThan(3 * READ_MS);
    }

    @Test
    @DisplayName("CA5 — aucun appel au modèle (délégation demandée) : aucune mesure de préparation")
    void noCallNoPrep() throws Exception {
        Run forced = run(true, "Vérifie.");
        assertThat(calls).isEmpty();
        assertThat(forced.prepared()).isEmpty();
    }
}
