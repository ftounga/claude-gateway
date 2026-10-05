package fr.claudegateway.governance.map.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.governance.map.HostMapFile;
import fr.claudegateway.governance.map.HostMapFileRepository;
import fr.claudegateway.runner.audit.RunnerAudit;
import fr.claudegateway.runner.audit.RunnerAuditRepository;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * La mesure d'avant et d'après (F-174 / SF-174-07), sur la base réelle (H2 + Liquibase) : fouilles de la
 * carte lues dans {@code runner_audit}, consultations dans {@code host_map_lookups}, verdict, garde
 * administrateur et isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HostMapMeasureIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private HostMapMeasureService service;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private JwtService jwtService;
    @Autowired private RunnerAuditRepository audits;
    @Autowired private HostMapFileRepository files;
    @Autowired private HostMapLookupRepository lookups;

    private final OffsetDateTime pivot = OffsetDateTime.of(2026, 10, 5, 0, 0, 0, 0, ZoneOffset.UTC);
    private final UUID host = UUID.randomUUID();
    private User admin;
    private User member;

    @BeforeEach
    void setUp() {
        audits.deleteAll();
        lookups.deleteAll();
        files.deleteAll();
        workspaceRepository.deleteAll();
        hosts.deleteAll();
        userRepository.deleteAll();
        admin = userRepository.save(User.builder().email("po-mesure@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
        member = userRepository.save(User.builder().email("membre-mesure@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        files.save(HostMapFile.builder().userId(admin.getId()).hostId(host).path("plateformes.md")
                .content("x").digest("d").facts(1).observedAt(OffsetDateTime.now()).build());
    }

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private void dig(UUID userId, String tool, String target, OffsetDateTime at) {
        RunnerAudit saved = audits.save(RunnerAudit.builder().userId(userId).hostId(host)
                .callId(UUID.randomUUID().toString()).tool(tool).target(target).outcome("OK").createdAt(at).build());
        // `created_at` est posé par @CreationTimestamp (l'horloge du test) : on le recale sur la date voulue,
        // sinon le test dépend du jour où il tourne (il a cassé le 2026-10-05, jour du pivot).
        jdbc.update("update runner_audit set created_at = ? where id = ?",
                java.sql.Timestamp.from(at.toInstant()), saved.getId());
    }

    private void turn(UUID userId, String strategy, int facts, OffsetDateTime at) {
        lookups.save(HostMapLookup.builder().userId(userId).hostId(host).kind("TURN").strategy(strategy)
                .factsCount(facts).chars(facts * 100).createdAt(at).build());
    }

    @Test
    @DisplayName("compte les fouilles de la carte et les faits joints, avant / après, et conclut au gain")
    void comparesBeforeAndAfter() {
        OffsetDateTime before = pivot.minusDays(2);
        OffsetDateTime after = pivot.plusDays(2);
        dig(admin.getId(), "bash", "grep -n VPC plateformes.md", before);
        dig(admin.getId(), "read_file", "plateformes.md", before);
        dig(admin.getId(), "bash", "ls /tmp", before); // Pas une fouille de la carte.
        dig(member.getId(), "bash", "grep x plateformes.md", before); // Autre compte.
        turn(admin.getId(), "LEXICAL", 3, before);
        turn(admin.getId(), "HYBRID", 12, after);
        turn(admin.getId(), "HYBRID", 8, after);
        lookups.save(HostMapLookup.builder().userId(admin.getId()).hostId(host).kind("TOOL").strategy("HYBRID")
                .factsCount(5).chars(500).createdAt(after).build());

        HostMapMeasureService.Comparison comparison =
                service.compare(admin.getId(), pivot, 7, pivot.plusDays(7));

        assertThat(comparison.before().mapDigs()).isEqualTo(2);
        assertThat(comparison.before().turns()).isEqualTo(1);
        assertThat(comparison.after().mapDigs()).isZero();
        assertThat(comparison.after().hybridTurns()).isEqualTo(2);
        assertThat(comparison.after().factsJoined()).isEqualTo(20);
        assertThat(comparison.after().toolCalls()).isEqualTo(1);
        assertThat(comparison.verdict()).isEqualTo("GAIN");
    }

    @Test
    @DisplayName("si les fouilles ne baissent pas, le verdict propose le coupe-circuit")
    void suggestsRollback() {
        dig(admin.getId(), "bash", "grep a plateformes.md", pivot.minusDays(1));
        dig(admin.getId(), "bash", "grep b plateformes.md", pivot.plusDays(1));
        dig(admin.getId(), "bash", "grep c plateformes.md", pivot.plusDays(1));
        turn(admin.getId(), "LEXICAL", 3, pivot.minusDays(1));
        turn(admin.getId(), "NONE", 0, pivot.plusDays(1));

        HostMapMeasureService.Comparison comparison =
                service.compare(admin.getId(), pivot, 7, pivot.plusDays(7));

        assertThat(comparison.verdict()).isEqualTo("RETOUR_ARRIERE_A_ENVISAGER");
        assertThat(comparison.notes()).anyMatch(n -> n.contains("APP_MAP_INDEX_ENABLED=false"));
    }

    @Test
    @DisplayName("l'endpoint est réservé à l'administrateur ; une date illisible est refusée")
    void adminOnly() throws Exception {
        mockMvc.perform(get("/api/admin/map-index/measure").contextPath("/api")
                        .header("Authorization", "Bearer " + jwtService.generateToken(member)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/map-index/measure").param("pivot", "2026-10-05").param("days", "7")
                        .contextPath("/api").header("Authorization", "Bearer " + jwtService.generateToken(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").exists());
        mockMvc.perform(get("/api/admin/map-index/measure").param("pivot", "hier").contextPath("/api")
                        .header("Authorization", "Bearer " + jwtService.generateToken(admin)))
                .andExpect(status().isBadRequest());
    }
}
