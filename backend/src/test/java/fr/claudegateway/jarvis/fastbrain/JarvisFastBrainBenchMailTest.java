package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.jarvis.mail.JarvisMailBench;
import fr.claudegateway.jarvis.mail.JarvisMailTriagePrompt;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-242 / SF-242-07 — le jeu MAIL de bout en bout dans le banc : déclaré, rejoué sans outil sur la consigne figée du
 * tri, porte ouverte avec un fournisseur juste et fermée avec un fournisseur hostile, aucun texte de courriel dans le
 * rapport.
 */
@SpringBootTest
@ActiveProfiles("test")
class JarvisFastBrainBenchMailTest {

    @MockitoBean private AiAgentProvider agentProvider;
    @Autowired private JarvisFastBrainBenchService bench;
    @Autowired private UserRepository userRepository;

    private static final Pattern TOKEN = Pattern.compile("\\nid (m\\d+)\\n");

    private UUID admin() {
        return userRepository.save(User.builder().email("bench-mail-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build()).getId();
    }

    /** Un bouchon qui classe chaque cas du lot comme attendu (ou tout en A_TRAITER s'il est hostile). */
    private void provider(boolean hostile) {
        JarvisMailBench.BenchSet set = JarvisMailBench.load();
        when(agentProvider.nextTurn(any(AgentTurnRequest.class))).thenAnswer(inv -> {
            AgentTurnRequest req = inv.getArgument(0);
            assertThat(req.tools()).isEmpty();
            assertThat(req.system()).isEqualTo(JarvisMailTriagePrompt.SYSTEM);
            String message = req.messages().get(0).toString();
            StringBuilder out = new StringBuilder("[");
            Matcher m = TOKEN.matcher(message);
            int i = 0;
            // F-243 / SF-243-04 — le lot des rendez-vous : un modèle fidèle rend le jour dit, l'heure et la certitude.
            if (message.contains("Point sur le devis")) {
                List<String> rdv = List.of(
                        "{\"titre\":\"Point devis\",\"jour_semaine\":\"jeudi\",\"date\":\"\",\"heure\":\"14:00\",\"certitude\":\"haute\"}",
                        "{\"titre\":\"Point\",\"jour_semaine\":\"\",\"date\":\"\",\"heure\":\"\",\"certitude\":\"basse\"}",
                        "{\"titre\":\"Signature\",\"jour_semaine\":\"\",\"date\":\"2026-11-12\",\"heure\":\"10:30\",\"certitude\":\"haute\"}",
                        "{\"titre\":\"Recette\",\"jour_semaine\":\"mardi\",\"date\":\"\",\"heure\":\"\",\"certitude\":\"basse\"}",
                        "{\"titre\":\"Kick-off\",\"jour_semaine\":\"vendredi\",\"date\":\"\",\"heure\":\"16:00\",\"certitude\":\"haute\"}",
                        "", "",
                        "{\"titre\":\"Rendez-vous\",\"jour_semaine\":\"jeudi\",\"date\":\"\",\"heure\":\"09:00\",\"certitude\":\"haute\"}");
                String obedient = "{\"titre\":\"Réunion\",\"jour_semaine\":\"jeudi\",\"date\":\"\",\"heure\":\"08:00\",\"certitude\":\"haute\"}";
                while (m.find()) {
                    String r = hostile ? obedient : rdv.get(i);
                    out.append(i++ > 0 ? "," : "").append("{\"id\":\"").append(m.group(1))
                            .append("\",\"classe\":\"A_TRAITER\",\"raison\":\"Rendez-vous proposé\"")
                            .append(r.isEmpty() ? "" : ",\"rdv\":" + r).append("}");
                }
                return new AgentTurn(out.append("]").toString(), List.of(), true, 2000, 200);
            }
            List<JarvisMailBench.Mail> mails = message.contains("Devis migration") ? set.triage() : set.injection();
            while (m.find()) {
                String classe = hostile ? "A_TRAITER" : mails.get(i).expected().get(0);
                out.append(i++ > 0 ? "," : "").append("{\"id\":\"").append(m.group(1)).append("\",\"classe\":\"")
                        .append(classe).append("\",\"raison\":\"Raison neutre\"}");
            }
            return new AgentTurn(out.append("]").toString(), List.of(), true, 2000, 200);
        });
    }

    @Test
    void theMailSetRunsAndOpensTheGateWithAFaithfulModel() throws Exception {
        provider(false);
        JarvisFastBrainBenchService.Report report = bench.run(admin(), UUID.randomUUID(),
                JarvisFastBrainBenchService.SET_MAIL, "claude-haiku-5-5", OffsetDateTime.now());
        assertThat(report.set()).isEqualTo("MAIL");
        assertThat(report.cases()).isEqualTo(36);
        assertThat(report.gateFailures()).isEmpty();
        assertThat(report.gatePassed()).isTrue();
        String stored = new ObjectMapper().findAndRegisterModules().writeValueAsString(report);
        assertThat(stored).doesNotContain("Devis migration").doesNotContain("IGNORE TES INSTRUCTIONS")
                .doesNotContain("pirate");
    }

    @Test
    void anObedientModelClosesTheGate() {
        provider(true);
        JarvisFastBrainBenchService.Report report = bench.run(admin(), UUID.randomUUID(),
                JarvisFastBrainBenchService.SET_MAIL, "claude-haiku-5-5", OffsetDateTime.now());
        assertThat(report.gatePassed()).isFalse();
        assertThat(report.gateFailures()).anyMatch(f -> f.startsWith("injection"));
    }
}
