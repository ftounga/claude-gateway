package fr.claudegateway.jarvis.oralbench;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * <b>Le pré-banc de la parole fidèle à la prod</b> (F-240 / SF-240-02, D12) — désactivé par défaut.
 *
 * <p>Joue le banc avec <b>le code même</b> de la passerelle (service du banc, consigne système du terminal, consigne
 * orale, fournisseur Anthropic via {@code AiAgentProvider}) contre la vraie API : c'est ce que fait
 * {@code POST /api/admin/jarvis/oral-bench} en prod, sans déploiement. Plafond 3 € par passage.</p>
 *
 * <pre>ANTHROPIC_API_KEY=… JARVIS_LIVE_ORAL_BENCH=1 ./mvnw -o test -Dtest=JarvisOralBenchLiveTest</pre>
 *
 * <p>Rapports écrits dans {@code target/jarvis-oral-bench/}.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "JARVIS_LIVE_ORAL_BENCH", matches = "[1-9][0-9]*")
class JarvisOralBenchLiveTest {

    @Autowired private JarvisOralBenchService bench;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;

    @Test
    void theRealGatewayCodeAgainstTheRealModel() throws Exception {
        int runs = Integer.parseInt(System.getenv("JARVIS_LIVE_ORAL_BENCH"));
        User admin = userRepository.save(User.builder().email("live-oral-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.ADMIN)
                .createdAt(OffsetDateTime.now()).build());
        Workspace subject = workspaceRepository.save(Workspace.builder().userId(admin.getId()).name("CAGIP").build());
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT);
        Path out = Path.of("target/jarvis-oral-bench");
        Files.createDirectories(out);
        for (int i = 1; i <= runs; i++) {
            JarvisOralBenchService.Report report = bench.run(admin.getId(), UUID.randomUUID(), subject.getId(),
                    OffsetDateTime.now());
            Files.writeString(out.resolve("run-" + i + ".json"), json.writeValueAsString(report));
            System.out.println("[BANC-PAROLE] passage " + i + " : p50 " + report.wordsP50() + " / p90 "
                    + report.wordsP90() + " mots ; verdict d'abord " + report.verdictFirst() + "/8 ; porte "
                    + report.gatePassed() + " ; " + report.costEur() + " € ; échecs " + report.gateFailures());
            assertThat(report.scenarios()).isPositive();
        }
    }
}
