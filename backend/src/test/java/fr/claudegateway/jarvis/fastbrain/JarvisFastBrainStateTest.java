package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AiAgentProvider.AgentTextListener;
import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.jarvis.state.JarvisStateBlock;
import fr.claudegateway.jarvis.state.JarvisStateTool;

/** F-247 / SF-247-10 — Jarvis sait : bloc « État connu » prêt dans le message, outil etat ancré, garde D8, présentation. */
class JarvisFastBrainStateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-05T08:00:00Z");

    private final UUID alice = UUID.randomUUID();
    private final UUID host = UUID.randomUUID();
    private Workspace workspace;
    private JarvisUsageService usage;
    private JarvisStateBlock block;
    private JarvisStateTool tool;
    private final List<AgentTurnRequest> calls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        workspace = Workspace.builder().id(UUID.randomUUID()).userId(alice).name("SSO").hostId(host).build();
        usage = mock(JarvisUsageService.class);
        when(usage.monthFastBrainCostUsd(any())).thenReturn(BigDecimal.ZERO);
        block = mock(JarvisStateBlock.class);
        tool = mock(JarvisStateTool.class);
    }

    private JarvisFastBrainService service(AiAgentProvider provider) {
        JarvisFastBrainService s = new JarvisFastBrainService(provider, new JarvisFastBrainProperties(true, null, null,
                null, null, null), usage, mock(WorkspaceRepository.class), Clock.fixed(NOW, ZoneOffset.UTC));
        s.setState(block, tool);
        return s;
    }

    private static JarvisStateBlock.Line line(String key, String label, String text, OffsetDateTime at) {
        return new JarvisStateBlock.Line(UUID.randomUUID(), null, key, label, text, at, false, false);
    }

    /** Un fournisseur qui répond par étapes : d'abord {@code first}, ensuite le texte {@code then}. */
    private AiAgentProvider steps(AgentTurn first, String then) {
        AtomicInteger n = new AtomicInteger();
        return new AiAgentProvider() {
            @Override
            public AgentTurn nextTurn(AgentTurnRequest request) {
                return nextTurn(request, d -> { });
            }

            @Override
            public AgentTurn nextTurn(AgentTurnRequest request, AgentTextListener listener) {
                calls.add(request);
                if (n.getAndIncrement() == 0 && first != null) {
                    if (first.text() != null && !first.text().isBlank()) {
                        listener.onTextDelta(first.text());
                    }
                    return first;
                }
                listener.onTextDelta(then);
                return new AgentTurn(then, List.of(), true, 100, 10);
            }
        };
    }

    private JarvisFastBrainService.Request ask(String text, boolean presentation) {
        return new JarvisFastBrainService.Request(alice, workspace, null, text, null, null, presentation);
    }

    @Test
    @DisplayName("le bloc prêt est joint au MESSAGE (jamais à la consigne) ; la consigne et les outils ne bougent pas")
    void blockGoesInTheMessage() throws Exception {
        String text = "## État connu — client CAGIP\nPoste déconnecté depuis hier 18:05 (arrêt propre).\n"
                + "- PROJ-123 « Migration SSO » : En revue (vu hier 17:42, tour)";
        when(block.ready(eq(alice), eq(host), any())).thenReturn(new JarvisStateBlock.Selected(text,
                List.of(line("JIRA:PROJ-123", "PROJ-123", text, OffsetDateTime.parse("2026-11-04T16:42:00Z"))), 1000L));
        service(steps(null, "PROJ-123 est en revue, relevé hier à 17 h 42.")).converse(ask("Où en est PROJ-123 ?",
                false), null);
        String sent = JSON.writeValueAsString(calls.get(0).messages());
        assertThat(sent).contains("État connu — client CAGIP").contains("PROJ-123");
        assertThat(calls.get(0).system()).doesNotContain("Migration SSO").doesNotContain("vu hier 17:42");
        assertThat(JarvisFastBrainPrompt.VERSION).isEqualTo("20");
    }

    @Test
    @DisplayName("en présentation : ni bloc, ni lecture ; l'outil etat dit « mode présentation »")
    void nothingInPresentation() throws Exception {
        when(tool.read(eq(alice), eq(host), any(), eq(true))).thenReturn(new JarvisStateTool.Read(
                JarvisStateTool.PRESENTATION, List.of()));
        AgentTurn etat = new AgentTurn("", List.of(new AgentToolCall("e1", "etat",
                JSON.readTree("{\"cle\":\"PROJ-123\",\"famille\":\"\",\"client\":\"\"}"))), false, 100, 10);
        service(steps(etat, "Je n'en dirai rien ici, Monsieur.")).converse(ask("Où en est PROJ-123 ?", true), null);
        verify(block, never()).ready(any(), any(), any());
        assertThat(JSON.writeValueAsString(calls.get(0).messages())).doesNotContain("État connu");
        assertThat(JSON.writeValueAsString(calls.get(1).messages())).contains("MODE PRÉSENTATION");
    }

    @Test
    @DisplayName("outil etat : lu une fois, rendu au modèle, ancré ; D8 : un fait de plus d'1 h dit sans heure est daté")
    void etatToolAndFreshness() throws Exception {
        when(block.ready(any(), any(), any())).thenReturn(JarvisStateBlock.Selected.EMPTY);
        OffsetDateTime old = OffsetDateTime.parse("2026-11-04T16:42:00Z");
        String result = JarvisStateTool.OPEN + "\n- MR grp/proj!42 « SSO » : opened, pipeline failed (vu hier 17:42, "
                + "tour)\n" + JarvisStateTool.CLOSE;
        when(tool.read(eq(alice), eq(host), any(), anyBoolean())).thenReturn(new JarvisStateTool.Read(result,
                List.of(line("GITLAB:grp/proj!42", "MR grp/proj!42", result, old))));
        AgentTurn etat = new AgentTurn("", List.of(new AgentToolCall("e1", "etat",
                JSON.readTree("{\"cle\":\"grp/proj!42\",\"famille\":\"\",\"client\":\"\"}"))), false, 100, 10);
        StringBuilder heard = new StringBuilder();
        JarvisFastBrainService.Outcome out = service(steps(etat, "La MR grp/proj!42 est ouverte, pipeline failed."))
                .converse(ask("La MR 42 est passée ?", false), heard::append);
        assertThat(calls).hasSize(2);
        assertThat(JSON.writeValueAsString(calls.get(1).messages())).contains("pipeline failed");
        assertThat(out.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(out.spokenText()).contains("(relevé d'hier 17:42)");
        assertThat(heard.toString()).contains("relevé d'hier 17:42");
    }
}
