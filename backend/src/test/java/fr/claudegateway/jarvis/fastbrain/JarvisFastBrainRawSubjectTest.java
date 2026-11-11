package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.JarvisUsageService;

/**
 * F-255 / SF-255-04 — quand une délégation n'a pas de sujet cible ({@code no_subject}), la valeur brute de « sujet »
 * rendue par le modèle est écrite au journal avec sa catégorie ; la réponse reste la question du sujet.
 */
@ExtendWith(OutputCaptureExtension.class)
class JarvisFastBrainRawSubjectTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final UUID alice = UUID.randomUUID();
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);

    private JarvisFastBrainService service(String sujet) {
        JarvisUsageService usage = mock(JarvisUsageService.class);
        when(usage.monthFastBrainCostUsd(any())).thenReturn(BigDecimal.ZERO);
        AiAgentProvider provider = request -> {
            try {
                return new AgentTurn("", List.of(new AgentToolCall("t1", "deleguer", JSON.readTree(
                        "{\"demande\": \"x\", \"label\": \"recherche\", \"annonce\": \"Je cherche.\", \"sujet\": "
                                + JSON.writeValueAsString(sujet) + ", \"chantier\": null}"))), false, 100, 10);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        return new JarvisFastBrainService(provider, new JarvisFastBrainProperties(true, null, null, null, null, null),
                usage, workspaces, Clock.fixed(Instant.parse("2026-11-09T16:24:00Z"), ZoneOffset.UTC));
    }

    private JarvisFastBrainService.Outcome ask(String sujet) {
        return service(sujet).converse(new JarvisFastBrainService.Request(alice, null, null,
                "Fais une recherche approfondie", null), null);
    }

    @Test
    @DisplayName("un nom au lieu d'un identifiant : « illisible », valeur brute au journal, question du sujet")
    void aNameIsTraced(CapturedOutput output) {
        JarvisFastBrainService.Outcome outcome = ask("data-platform");
        assertThat(outcome.spokenText()).isEqualTo(JarvisFastBrainService.ASK_SUBJECT);
        assertThat(output.getOut()).contains("Jarvis no_subject : user=" + alice + " sujet brut=illisible "
                + "« data-platform »");
    }

    @Test
    @DisplayName("vide, ou identifiant hors du compte : sa catégorie")
    void emptyOrUnknown(CapturedOutput output) {
        ask("");
        assertThat(output.getOut()).contains("sujet brut=vide «  »");
        UUID other = UUID.randomUUID();
        when(workspaces.findByIdAndUserId(other, alice)).thenReturn(Optional.empty());
        ask(other.toString());
        assertThat(output.getOut()).contains("sujet brut=inconnu « " + other + " »");
    }

    @Test
    @DisplayName("un sujet du compte : aucune trace, la délégation part")
    void aKnownSubjectIsNotTraced(CapturedOutput output) {
        UUID mine = UUID.randomUUID();
        when(workspaces.findByIdAndUserId(mine, alice)).thenReturn(Optional.of(
                Workspace.builder().id(mine).userId(alice).name("waf").build()));
        JarvisFastBrainService.Outcome outcome = ask(mine.toString());
        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.DELEGATE);
        assertThat(output.getOut()).doesNotContain("no_subject");
    }

    @Test
    @DisplayName("valeur brute : une ligne, 80 caractères au plus")
    void rawValueIsBounded() {
        assertThat(JarvisFastBrainService.rawSubjectForLog("a\nb\tc")).isEqualTo("a b c");
        assertThat(JarvisFastBrainService.rawSubjectForLog("x".repeat(200))).hasSize(81);
        assertThat(JarvisFastBrainService.rawSubjectForLog(null)).isEmpty();
    }
}
