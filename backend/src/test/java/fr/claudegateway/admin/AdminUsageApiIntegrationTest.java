package fr.claudegateway.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.quota.UsageCounter;
import fr.claudegateway.quota.UsageCounterRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Intégration de la consommation par utilisateur côté admin (F-61 / SF-61-03) : garde ADMIN,
 * distinction entrée/sortie, fenêtre — et la vérification qui compte le plus : <b>aucun nom de
 * projet</b> ne transite par cette route, même quand la base en contient.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminUsageApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UsageCounterRepository usageCounterRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private JwtService jwtService;

    private String adminToken;
    private String userToken;
    private UUID plainId;

    private final LocalDate thisMonth = LocalDate.now().withDayOfMonth(1);
    private final LocalDate lastMonth = LocalDate.now().withDayOfMonth(1).minusMonths(1);

    @BeforeEach
    void setUp() {
        usageCounterRepository.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();

        User admin = userRepository.save(User.builder()
                .email("admin-usage@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
        User plain = userRepository.save(User.builder()
                .email("plain-usage@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        adminToken = jwtService.generateToken(admin);
        userToken = jwtService.generateToken(plain);
        plainId = plain.getId();

        usageCounterRepository.save(UsageCounter.builder()
                .userId(plainId).periodStart(thisMonth)
                .inputTokens(1_000_000L).outputTokens(1_000_000L).build());
        usageCounterRepository.save(UsageCounter.builder()
                .userId(plainId).periodStart(lastMonth)
                .inputTokens(500_000L).outputTokens(100_000L).build());
    }

    @Test
    void adminSeesConsumptionPerUser() throws Exception {
        mockMvc.perform(get("/api/admin/usage").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.users.length()").value(1))
                .andExpect(jsonPath("$.users[0].email").value("plain-usage@example.com"))
                .andExpect(jsonPath("$.users[0].inputTokens").value(1_500_000))
                .andExpect(jsonPath("$.users[0].outputTokens").value(1_100_000))
                .andExpect(jsonPath("$.users[0].share").value(1.0))
                .andExpect(jsonPath("$.users[0].periods.length()").value(2));
    }

    @Test
    void plainUserIsForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/usage").contextPath("/api")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.users").doesNotExist());
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/usage").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void narrowingTheWindowNarrowsTheNumbers() throws Exception {
        mockMvc.perform(get("/api/admin/usage").contextPath("/api")
                        .param("from", thisMonth.toString()).param("to", thisMonth.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[0].inputTokens").value(1_000_000))
                .andExpect(jsonPath("$.users[0].periods.length()").value(1));
    }

    @Test
    void invertedWindowIsRejected() throws Exception {
        mockMvc.perform(get("/api/admin/usage").contextPath("/api")
                        .param("from", thisMonth.toString())
                        .param("to", thisMonth.minusMonths(3).toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void noProjectNameEverReachesTheAdminConsole() throws Exception {
        // Une base qui contient un nom de mission bien reconnaissable : il ne doit apparaître
        // nulle part dans la réponse. Un administrateur voit des volumes, jamais des missions.
        workspaceRepository.save(Workspace.builder()
                .userId(plainId).name("refonte-paie-groupe-x").build());

        String payload = mockMvc.perform(get("/api/admin/usage").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(payload).doesNotContain("refonte-paie-groupe-x");
        assertThat(payload).doesNotContain("hostName");
        assertThat(payload).doesNotContain("workspace");
    }
}
