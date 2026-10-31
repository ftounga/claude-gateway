package fr.claudegateway.quota.billing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.quota.TurnCost;
import fr.claudegateway.quota.UsageTurn;
import fr.claudegateway.quota.UsageTurnRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-224 / SF-224-02 — « facturé par le fournisseur » à côté de « calculé », par jour et par clé ; administrateur seul ;
 * sans clé, l'écran dit comment la créer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProviderCostReconciliationApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwtService;
    @Autowired private UsageTurnRepository usageTurns;
    @Autowired private ProviderCostReportRepository reports;
    @MockitoBean private ProviderBillingReport billing;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        reports.deleteAll();
        usageTurns.deleteAll();
        User admin = users.save(User.builder().email("admin-recon-" + UUID.randomUUID() + "@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
        User ordinary = users.save(User.builder().email("user-recon-" + UUID.randomUUID() + "@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        adminToken = jwtService.generateToken(admin);
        userToken = jwtService.generateToken(ordinary);
    }

    @Test
    @DisplayName("sans clé : inactif, l'écran dit comment la créer ; jamais pour un non-administrateur")
    void withoutKeyTheScreenExplains() throws Exception {
        when(billing.configured()).thenReturn(false);
        mockMvc.perform(get("/api/admin/cost/reconciliation").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.setupHint").value(org.hamcrest.Matchers.containsString("APP_ANTHROPIC_ADMIN_KEY")))
                .andExpect(jsonPath("$.days.length()").value(30));
        mockMvc.perform(get("/api/admin/cost/reconciliation").contextPath("/api")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/cost/reconciliation/refresh").contextPath("/api")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("relecture : facturé, calculé, écart par jour ; la consommation de chaque clé, nommée")
    void reconcilesBilledAndCalculated() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        when(billing.configured()).thenReturn(true);
        when(billing.billedCosts(any(), any())).thenReturn(List.of(
                new ProviderBillingReport.BilledDay(today, new BigDecimal("80.00"))));
        when(billing.usageByKey(any(), any())).thenReturn(List.of(
                new ProviderBillingReport.KeyUsage(today, "apikey_gw", "claude-opus-5-5", 1_000_000L, 0L, 0L, 0L),
                new ProviderBillingReport.KeyUsage(today, "apikey_lc", "claude-opus-5-5", 2_000_000L, 0L, 0L, 0L)));
        when(billing.keyLabels()).thenReturn(Map.of("apikey_gw", "gateway (…owXY)"));
        usageTurns.save(UsageTurn.builder().userId(UUID.randomUUID()).inputTokens(1L).outputTokens(1L)
                .providerCostUsd(new BigDecimal("50.25")).costSource(TurnCost.Source.CALCULATED)
                .model("claude-opus-5-5").occurredAt(OffsetDateTime.now(ZoneOffset.UTC)).build());

        mockMvc.perform(post("/api/admin/cost/reconciliation/refresh").param("days", "7").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.setupHint").doesNotExist())
                .andExpect(jsonPath("$.days.length()").value(7))
                .andExpect(jsonPath("$.days[0].day").value(today.toString()))
                .andExpect(jsonPath("$.days[0].billedUsd").value(80.00))
                .andExpect(jsonPath("$.days[0].calculatedUsd").value(50.25))
                .andExpect(jsonPath("$.days[0].gapUsd").value(29.75))
                .andExpect(jsonPath("$.days[0].keys[0].apiKeyId").value("apikey_lc"))
                .andExpect(jsonPath("$.days[0].keys[0].amountUsd").value(8.0))
                .andExpect(jsonPath("$.days[0].keys[1].label").value("gateway (…owXY)"))
                .andExpect(jsonPath("$.days[1].billedUsd").doesNotExist());

        // Relire remplace, ne double pas.
        mockMvc.perform(post("/api/admin/cost/reconciliation/refresh").param("days", "7").contextPath("/api")
                .header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(reports.count()).isEqualTo(3);
    }
}
