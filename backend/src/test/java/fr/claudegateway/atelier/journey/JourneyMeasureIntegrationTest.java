package fr.claudegateway.atelier.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.runner.audit.RunnerAudit;
import fr.claudegateway.runner.audit.RunnerAuditRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * La mesure du parcours (F-176 / SF-176-06), sur la base réelle (H2 + Liquibase) : gestes du parcours,
 * refus de la porte, retours arrière guidé / libre, avant / après — garde administrateur et isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JourneyMeasureIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JourneyMeasureService service;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private SubjectJourneyRepository journeys;
    @Autowired private SubjectJourneyEventRepository events;
    @Autowired private RunnerAuditRepository audits;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;

    private final OffsetDateTime pivot = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private User admin;
    private User member;
    private UUID guidedTerminal;
    private UUID libreTerminal;
    private UUID memberTerminal;

    @BeforeEach
    void setUp() throws Exception {
        audits.deleteAll();
        events.deleteAll();
        journeys.deleteAll();
        workspaceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        admin = user("po-parcours@example.com", UserRole.ADMIN);
        member = user("membre-parcours@example.com", UserRole.USER);
        guidedTerminal = workspace(admin, "Incident ingress");
        libreTerminal = workspace(admin, "Questions");
        memberTerminal = workspace(member, "Autre compte");
    }

    private User user(String email, UserRole role) {
        User user = userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(role).build());
        subscriptionRepository.save(Subscription.builder().userId(user.getId())
                .planCode(PlanCode.GOLD).status(SubscriptionStatus.ACTIVE).build());
        return user;
    }

    private UUID workspace(User owner, String name) throws Exception {
        String body = mockMvc.perform(multipart("/api/workspaces").file(zip()).contextPath("/api")
                        .param("name", name)
                        .header("Authorization", "Bearer " + jwtService.generateToken(owner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private static MockMultipartFile zip() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("README.md"));
            zos.write("hello".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return new MockMultipartFile("file", "project.zip", "application/zip", out.toByteArray());
    }

    private void event(UUID userId, UUID workspaceId, String type, String mode, String detail, OffsetDateTime at) {
        events.save(SubjectJourneyEvent.builder().userId(userId).workspaceId(workspaceId).type(type).mode(mode)
                .detail(detail).createdAt(at).build());
    }

    private void call(UUID userId, UUID workspaceId, String tool, String target, String outcome, OffsetDateTime at) {
        RunnerAudit saved = audits.save(RunnerAudit.builder().userId(userId).workspaceId(workspaceId)
                .callId(UUID.randomUUID().toString()).tool(tool).target(target).outcome(outcome).build());
        jdbc.update("update runner_audit set created_at = ? where id = ?", Timestamp.from(at.toInstant()),
                saved.getId());
    }

    @Test
    @DisplayName("compte les gestes du parcours, les refus par classe, et sépare guidé / libre")
    void measures() {
        OffsetDateTime after = pivot.plusDays(2);
        event(admin.getId(), guidedTerminal, SubjectJourneyEvent.MODE_CHANGED, "GUIDE", "USER", after);
        event(admin.getId(), guidedTerminal, SubjectJourneyEvent.GATE_BLOCKED, "GUIDE", "EXTERNE · bash", after);
        event(admin.getId(), guidedTerminal, SubjectJourneyEvent.GATE_BLOCKED, "GUIDE", "REVERSIBLE · edit_file", after);
        event(admin.getId(), guidedTerminal, SubjectJourneyEvent.PLAN_VALIDATED, "GUIDE", "v1", after);
        event(admin.getId(), guidedTerminal, SubjectJourneyEvent.STEP_UPDATED, "GUIDE", "1 · ECHEC", after);
        event(admin.getId(), guidedTerminal, SubjectJourneyEvent.REOPENED, "GUIDE", "502", after);
        event(member.getId(), memberTerminal, SubjectJourneyEvent.GATE_BLOCKED, "GUIDE", "EXTERNE · bash", after);
        call(admin.getId(), guidedTerminal, "bash", "kubectl get pods", "OK", after);
        call(admin.getId(), libreTerminal, "bash", "git revert HEAD", "OK", after);
        call(admin.getId(), libreTerminal, "bash", "helm rollback api 3", "ERROR", after);
        call(admin.getId(), libreTerminal, "bash", "git revert HEAD~2", "OK", pivot.minusDays(3));
        call(member.getId(), memberTerminal, "bash", "git revert HEAD", "OK", after);

        JourneyMeasureService.Comparison comparison =
                service.compare(admin.getId(), pivot, 7, pivot.plusDays(7));

        JourneyMeasureService.Window a = comparison.after();
        assertThat(a.guidedSubjects()).isEqualTo(1);
        assertThat(a.events()).containsEntry(SubjectJourneyEvent.GATE_BLOCKED, 2L)
                .containsEntry(SubjectJourneyEvent.REOPENED, 1L);
        assertThat(a.gateBlocked()).containsEntry("EXTERNE", 1L).containsEntry("REVERSIBLE", 1L);
        assertThat(a.stepsFailed()).isEqualTo(1);
        assertThat(a.guided().terminals()).isEqualTo(1);
        assertThat(a.guided().rollbacks()).isZero();
        assertThat(a.libre().terminals()).isEqualTo(1);
        assertThat(a.libre().rollbacks()).isEqualTo(2);
        assertThat(a.libre().failures()).isEqualTo(1);
        assertThat(comparison.before().libre().rollbacks()).isEqualTo(1);
        assertThat(comparison.notes()).anyMatch(n -> n.contains("lecture humaine"));
    }

    @Test
    @DisplayName("l'endpoint est réservé à l'administrateur ; une date illisible est refusée")
    void adminOnly() throws Exception {
        mockMvc.perform(get("/api/admin/journeys/measure").contextPath("/api")
                        .header("Authorization", "Bearer " + jwtService.generateToken(member)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/journeys/measure").param("pivot", "2026-10-01").param("days", "7")
                        .contextPath("/api").header("Authorization", "Bearer " + jwtService.generateToken(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.after.guidedSubjects").exists())
                .andExpect(jsonPath("$.notes").isArray());
        mockMvc.perform(get("/api/admin/journeys/measure").param("pivot", "hier").contextPath("/api")
                        .header("Authorization", "Bearer " + jwtService.generateToken(admin)))
                .andExpect(status().isBadRequest());
    }
}
