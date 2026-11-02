package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
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
 * <b>Le pré-banc des pièces fidèle à la prod</b> (F-237 / SF-237-15) — désactivé par défaut.
 *
 * <p>Exécute le jeu {@code PIECES} avec <b>le code même</b> de la passerelle (service du banc, cerveau rapide, consigne,
 * outils, gardes, fournisseur Anthropic), contre la vraie API : c'est ce que fait
 * {@code POST /api/admin/jarvis/fast-brain/bench} (jeu {@code PIECES}) en prod, sans déploiement.</p>
 *
 * <pre>ANTHROPIC_API_KEY=… JARVIS_PIECES_LIVE_BENCH=2 ./mvnw -o test -Dtest=JarvisPiecesBenchLiveTest</pre>
 *
 * <p>{@code JARVIS_PIECES_LIVE_BENCH} = nombre de passages ; {@code JARVIS_LIVE_BENCH_MODEL} (défaut
 * {@code claude-sonnet-5-5}) ; rapports dans {@code target/jarvis-pieces-live-bench/}. Sonde :
 * {@code JARVIS_PIECES_LIVE_PROBE=S01,P06} rejoue ces cas 3 fois avec la raison, les gardes, les ordres d'écran et le
 * texte brut du modèle.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class JarvisPiecesBenchLiveTest {

    @Autowired private JarvisFastBrainBenchService bench;
    @Autowired private JarvisFastBrainService fastBrain;
    @Autowired private UserRepository userRepository;

    private User admin() {
        return userRepository.save(User.builder().email("pieces-live-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.ADMIN)
                .createdAt(OffsetDateTime.now()).build());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "JARVIS_PIECES_LIVE_BENCH", matches = "[1-9][0-9]*")
    void theRealGatewayCodeAgainstTheRealModel() throws Exception {
        int runs = Integer.parseInt(System.getenv("JARVIS_PIECES_LIVE_BENCH"));
        String model = System.getenv().getOrDefault("JARVIS_LIVE_BENCH_MODEL", "claude-sonnet-5-5");
        User admin = admin();
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT);
        Path out = Path.of("target/jarvis-pieces-live-bench");
        Files.createDirectories(out);
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= runs; i++) {
            JarvisFastBrainBenchService.Report report = bench.run(admin.getId(), UUID.randomUUID(),
                    JarvisFastBrainBenchService.SET_PIECES, model, OffsetDateTime.now());
            Files.writeString(out.resolve("run-" + i + ".json"), json.writeValueAsString(report));
            StringBuilder line = new StringBuilder("passage " + i + " : porte " + report.gatePassed() + " ; accord "
                    + report.routeAgreement() + " ; échecs " + report.gateFailures());
            for (String category : List.of("SHOW", "NAVIGATE", "PROOF", "NEUTRAL")) {
                List<JarvisFastBrainBenchService.Verdict> of = report.verdicts().stream()
                        .filter(v -> category.equals(v.category())).toList();
                long ok = of.stream().filter(JarvisFastBrainBenchService.Verdict::routeAgreed).count();
                line.append(" ; ").append(category).append(' ').append(ok).append('/').append(of.size());
            }
            report.verdicts().stream().filter(v -> !v.routeAgreed())
                    .forEach(v -> line.append("\n    ").append(v.caseId()).append(" ").append(v.fastRoute())
                            .append(" — ").append(v.comment()));
            lines.add(line.toString());
            System.out.println("[PRE-BANC PIECES] " + line);
        }
        Files.write(out.resolve("summary.txt"), lines);
        assertThat(lines).hasSize(runs);
    }

    /** Sonde : les cas nommés, rejoués 3 fois, avec la raison d'une délégation et le texte brut du modèle. */
    @Test
    @EnabledIfEnvironmentVariable(named = "JARVIS_PIECES_LIVE_PROBE", matches = ".+")
    void probe() {
        List<String> ids = Arrays.asList(System.getenv("JARVIS_PIECES_LIVE_PROBE").split(","));
        String model = System.getenv().getOrDefault("JARVIS_LIVE_BENCH_MODEL", "claude-sonnet-5-5");
        int times = Integer.parseInt(System.getenv().getOrDefault("JARVIS_PIECES_LIVE_PROBE_TIMES", "3"));
        User admin = admin();
        JarvisPiecesBench.Set set = JarvisPiecesBench.load();
        for (JarvisPiecesBench.Case c : set.cases()) {
            if (!ids.contains(c.id())) {
                continue;
            }
            for (int i = 0; i < times; i++) {
                StringBuilder raw = new StringBuilder();
                fastBrain.probeRaw = raw;
                JarvisFastBrainService.Outcome o;
                try {
                    o = fastBrain.benchWithPieces(new JarvisFastBrainService.Request(admin.getId(), null, null,
                            c.text(), null), model, JarvisPiecesBench.contextFor(set, c), set.pieces());
                } finally {
                    fastBrain.probeRaw = null;
                }
                System.out.println("[SONDE " + c.id() + "] " + o.kind() + " raison=" + o.reason() + " 1erJeton="
                        + o.firstTokenMs() + "ms gardes=" + o.guards() + " écran=" + o.shows() + " dit=« "
                        + o.spokenText() + " » délégation=" + (o.delegation() == null ? "-"
                                : "« " + o.delegation().annonce() + " » / « " + o.delegation().demande() + " »")
                        + " verdict=" + JarvisPiecesBench.evaluate(c, o).comment() + "\n    brut=« "
                        + raw.toString().replace("\n", "⏎") + " »");
            }
        }
    }
}
