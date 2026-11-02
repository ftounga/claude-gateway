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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * <b>Le pré-banc de conversation fidèle à la prod</b> (F-239 / SF-239-13) — désactivé par défaut.
 *
 * <p>Exécute le jeu {@code CONVERSATION} avec <b>le code même</b> de la passerelle (service du banc, cerveau rapide,
 * consigne, gardes, contexte, fournisseur Anthropic), contre la vraie API : c'est ce que fait
 * {@code POST /api/admin/jarvis/fast-brain/bench} en prod, sans déploiement. Remplace l'ancien pré-banc JavaScript, qui
 * imitait la passerelle (ni gardes, ni forme d'appel identique) et a surestimé le résultat (94,1 % contre 82,4 %).</p>
 *
 * <pre>ANTHROPIC_API_KEY=… JARVIS_LIVE_BENCH=2 ./mvnw -o test -Dtest=JarvisConversationBenchLiveTest</pre>
 *
 * <p>{@code JARVIS_LIVE_BENCH} = nombre de passages (mesure de la variance) ; {@code JARVIS_LIVE_BENCH_MODEL}
 * (défaut {@code claude-sonnet-5-5}) ; rapports écrits dans {@code target/jarvis-live-bench/}.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "JARVIS_LIVE_BENCH", matches = "[1-9][0-9]*")
class JarvisConversationBenchLiveTest {

    @Autowired private JarvisFastBrainBenchService bench;
    @Autowired private UserRepository userRepository;

    @Test
    void theRealGatewayCodeAgainstTheRealModel() throws Exception {
        int runs = Integer.parseInt(System.getenv("JARVIS_LIVE_BENCH"));
        String model = System.getenv().getOrDefault("JARVIS_LIVE_BENCH_MODEL", "claude-sonnet-5-5");
        User admin = userRepository.save(User.builder().email("live-bench-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.ADMIN)
                .createdAt(OffsetDateTime.now()).build());
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT);
        Path out = Path.of("target/jarvis-live-bench");
        Files.createDirectories(out);
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= runs; i++) {
            JarvisFastBrainBenchService.Report report = bench.run(admin.getId(), UUID.randomUUID(),
                    JarvisFastBrainBenchService.SET_CONVERSATION, model, OffsetDateTime.now());
            Files.writeString(out.resolve("run-" + i + ".json"), json.writeValueAsString(report));
            JarvisFastBrainBenchService.ConversationReport c = report.conversation();
            String line = "passage " + i + " : " + c.passRate() + " % ; porte " + report.gatePassed() + " ; "
                    + c.costPerExchangeEur() + " €/échange ; délégation " + c.delegationRate() + " ; échecs "
                    + report.gateFailures();
            lines.add(line);
            System.out.println("[PRE-BANC] " + line);
        }
        Files.write(out.resolve("summary.txt"), lines);
        assertThat(lines).hasSize(runs);
    }

    @Autowired private JarvisFastBrainService fastBrain;

    /** Sonde : une parole du jeu, rejouée N fois, avec la raison d'une délégation d'office et ce qui a été dit. */
    @Test
    @EnabledIfEnvironmentVariable(named = "JARVIS_LIVE_PROBE", matches = ".+")
    void probe() {
        String said = System.getenv("JARVIS_LIVE_PROBE");
        String model = System.getenv().getOrDefault("JARVIS_LIVE_BENCH_MODEL", "claude-sonnet-5-5");
        User admin = userRepository.save(User.builder().email("live-probe-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.ADMIN)
                .createdAt(OffsetDateTime.now()).build());
        // JARVIS_LIVE_PROBE_PREV = « parole||réponse » : un échange précédent du scénario, comme au banc.
        String prev = System.getenv("JARVIS_LIVE_PROBE_PREV");
        List<String[]> previous = prev == null || !prev.contains("||") ? List.of()
                : List.<String[]>of(prev.split("\\|\\|", 2));
        String context = JarvisConversationBench.contextFor(JarvisConversationBench.load().context(), previous, null);
        for (int i = 0; i < 5; i++) {
            StringBuilder streamed = new StringBuilder();
            JarvisFastBrainService.Outcome o = fastBrain.decideForProbe(new JarvisFastBrainService.Request(
                    admin.getId(), null, null, said, null), model, context, streamed);
            System.out.println("[SONDE] " + o.kind() + " raison=" + o.reason() + " gardes=" + o.guards()
                    + " dit=« " + o.spokenText() + " » brut=« " + streamed + " »");
        }
    }
}
