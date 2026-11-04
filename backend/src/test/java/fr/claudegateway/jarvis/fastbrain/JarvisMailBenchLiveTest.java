package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * <b>Le pré-banc MAIL fidèle à la prod</b> (F-245 / SF-245-06) — désactivé par défaut.
 *
 * <p>Exécute le jeu {@code MAIL} (tri, injection, bref) avec le code même de la passerelle contre la vraie API, comme
 * {@code POST /api/admin/jarvis/fast-brain/bench} (jeu {@code MAIL}) en prod, sans déploiement. Aucun texte de
 * courriel n'est imprimé : seuls la porte, l'accord et les identifiants des cas échoués.</p>
 *
 * <pre>ANTHROPIC_API_KEY=… JARVIS_MAIL_LIVE_BENCH=1 ./mvnw -o test -Dtest=JarvisMailBenchLiveTest</pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "JARVIS_MAIL_LIVE_BENCH", matches = "[1-9][0-9]*")
class JarvisMailBenchLiveTest {

    @Autowired private JarvisFastBrainBenchService bench;
    @Autowired private UserRepository userRepository;

    @Test
    void theRealGatewayCodeAgainstTheRealModel() throws Exception {
        int runs = Integer.parseInt(System.getenv("JARVIS_MAIL_LIVE_BENCH"));
        String model = System.getenv().getOrDefault("JARVIS_LIVE_BENCH_MODEL", "claude-sonnet-5-5");
        User admin = userRepository.save(User.builder().email("mail-live-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.ADMIN)
                .createdAt(OffsetDateTime.now()).build());
        Path out = Path.of("target/jarvis-mail-live-bench");
        Files.createDirectories(out);
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= runs; i++) {
            JarvisFastBrainBenchService.Report report = bench.run(admin.getId(), UUID.randomUUID(),
                    JarvisFastBrainBenchService.SET_MAIL, model, OffsetDateTime.now());
            StringBuilder line = new StringBuilder("passage " + i + " : porte " + report.gatePassed() + " ; accord "
                    + report.routeAgreement() + " ; échecs " + report.gateFailures());
            report.verdicts().stream().filter(v -> !v.routeAgreed())
                    .forEach(v -> line.append("\n    ").append(v.caseId()).append(" ").append(v.category())
                            .append(" -> ").append(v.fastRoute()));
            lines.add(line.toString());
            System.out.println("[PRE-BANC MAIL] " + line);
        }
        Files.write(out.resolve("summary.txt"), lines);
        assertThat(lines).hasSize(runs);
    }
}
