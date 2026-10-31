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
    @Autowired private ProviderCostReconciliationService service;

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
    @DisplayName("relecture : facturé, calculé, écart par jour COMPLET ; la consommation de chaque clé, nommée")
    void reconcilesBilledAndCalculated() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate yesterday = today.minusDays(1);
        when(billing.configured()).thenReturn(true);
        when(billing.billedCosts(any(), any())).thenReturn(List.of(
                new ProviderBillingReport.BilledDay(yesterday, new BigDecimal("80.00")),
                new ProviderBillingReport.BilledDay(today, new BigDecimal("3.00"))));
        when(billing.usageByKey(any(), any())).thenReturn(List.of(
                new ProviderBillingReport.KeyUsage(yesterday, "apikey_gw", "claude-opus-5-5", 1_000_000L, 0L, 0L, 0L),
                new ProviderBillingReport.KeyUsage(yesterday, "apikey_lc", "claude-opus-5-5", 2_000_000L, 0L, 0L, 0L)));
        when(billing.keyLabels()).thenReturn(Map.of("apikey_gw", "gateway (…owXY)"));
        usageTurns.save(UsageTurn.builder().userId(UUID.randomUUID()).inputTokens(1L).outputTokens(1L)
                .providerCostUsd(new BigDecimal("50.25")).costSource(TurnCost.Source.CALCULATED)
                .model("claude-opus-5-5").occurredAt(yesterday.atTime(12, 0).atOffset(ZoneOffset.UTC)).build());
        usageTurns.save(UsageTurn.builder().userId(UUID.randomUUID()).inputTokens(1L).outputTokens(1L)
                .providerCostUsd(new BigDecimal("10.00")).costSource(TurnCost.Source.CALCULATED)
                .model("claude-opus-5-5").occurredAt(OffsetDateTime.now(ZoneOffset.UTC)).build());

        mockMvc.perform(post("/api/admin/cost/reconciliation/refresh").param("days", "7").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.setupHint").doesNotExist())
                .andExpect(jsonPath("$.days.length()").value(7))
                // F-224 / SF-224-06 : aujourd'hui est PARTIEL — montré, sans écart, hors des totaux.
                .andExpect(jsonPath("$.days[0].day").value(today.toString()))
                .andExpect(jsonPath("$.days[0].partial").value(true))
                .andExpect(jsonPath("$.days[0].billedUsd").value(3.00))
                .andExpect(jsonPath("$.days[0].calculatedUsd").value(10.00))
                .andExpect(jsonPath("$.days[0].gapUsd").doesNotExist())
                .andExpect(jsonPath("$.days[0].gapEur").doesNotExist())
                .andExpect(jsonPath("$.days[1].day").value(yesterday.toString()))
                .andExpect(jsonPath("$.days[1].partial").value(false))
                .andExpect(jsonPath("$.days[1].billedUsd").value(80.00))
                .andExpect(jsonPath("$.days[1].calculatedUsd").value(50.25))
                .andExpect(jsonPath("$.days[1].gapUsd").value(29.75))
                .andExpect(jsonPath("$.days[1].keys[0].apiKeyId").value("apikey_lc"))
                .andExpect(jsonPath("$.days[1].keys[0].amountUsd").value(8.0))
                .andExpect(jsonPath("$.days[1].keys[1].label").value("gateway (…owXY)"))
                .andExpect(jsonPath("$.days[2].billedUsd").doesNotExist())
                .andExpect(jsonPath("$.billedTotalUsd").value(80.00))
                .andExpect(jsonPath("$.calculatedTotalUsd").value(50.25))
                // F-224 / SF-224-05 : les mêmes montants en euros, au taux unique de la passerelle (0,92).
                .andExpect(jsonPath("$.usdToEur").value(0.92))
                .andExpect(jsonPath("$.billedTotalEur").value(73.60))
                .andExpect(jsonPath("$.calculatedTotalEur").value(46.23))
                .andExpect(jsonPath("$.days[1].billedEur").value(73.60))
                .andExpect(jsonPath("$.days[1].calculatedEur").value(46.23))
                .andExpect(jsonPath("$.days[1].gapEur").value(27.37))
                .andExpect(jsonPath("$.days[1].keys[0].amountEur").value(7.36))
                .andExpect(jsonPath("$.days[2].billedEur").doesNotExist())
                .andExpect(jsonPath("$.days[2].gapEur").doesNotExist())
                .andExpect(jsonPath("$.days[2].calculatedEur").value(0.0))
                // F-224 / SF-224-06 : le périmètre de chaque montant est dit ; sans identifiant de la clé de la
                // passerelle, pas de comparaison à périmètre égal, et l'écran dit comment la poser.
                .andExpect(jsonPath("$.scopes.billed").value(org.hamcrest.Matchers.containsString("toute l'organisation")))
                .andExpect(jsonPath("$.scopes.calculated").value(org.hamcrest.Matchers.containsString("passerelle")))
                .andExpect(jsonPath("$.scopes.today").value(org.hamcrest.Matchers.containsString("partiel")))
                .andExpect(jsonPath("$.gatewayApiKeyId").doesNotExist())
                .andExpect(jsonPath("$.gatewayKeyHint").value(
                        org.hamcrest.Matchers.containsString("APP_ANTHROPIC_GATEWAY_KEY_ID")))
                .andExpect(jsonPath("$.days[1].gatewayKeyUsd").doesNotExist())
                .andExpect(jsonPath("$.days[1].keys[0].gateway").value(false));

        // Relire remplace, ne double pas.
        mockMvc.perform(post("/api/admin/cost/reconciliation/refresh").param("days", "7").contextPath("/api")
                .header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(reports.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("clé de la passerelle désignée : sa ligne marquée, son montant et l'écart à périmètre égal")
    void comparesTheGatewayToItsOwnKey() throws Exception {
        Object target = org.springframework.test.util.AopTestUtils.getTargetObject(service);
        org.springframework.test.util.ReflectionTestUtils.setField(target, "gatewayKeyId", "apikey_gw");
        try {
            LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
            when(billing.configured()).thenReturn(true);
            when(billing.billedCosts(any(), any())).thenReturn(List.of(
                    new ProviderBillingReport.BilledDay(yesterday, new BigDecimal("80.00"))));
            when(billing.usageByKey(any(), any())).thenReturn(List.of(
                    new ProviderBillingReport.KeyUsage(yesterday, "apikey_gw", "claude-opus-5-5", 13_000_000L, 0L,
                            0L, 0L),
                    new ProviderBillingReport.KeyUsage(yesterday, "apikey_lc", "claude-opus-5-5", 2_000_000L, 0L,
                            0L, 0L)));
            when(billing.keyLabels()).thenReturn(Map.of());
            usageTurns.save(UsageTurn.builder().userId(UUID.randomUUID()).inputTokens(1L).outputTokens(1L)
                    .providerCostUsd(new BigDecimal("50.25")).costSource(TurnCost.Source.CALCULATED)
                    .model("claude-opus-5-5").occurredAt(yesterday.atTime(12, 0).atOffset(ZoneOffset.UTC)).build());

            mockMvc.perform(post("/api/admin/cost/reconciliation/refresh").param("days", "7").contextPath("/api")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.gatewayApiKeyId").value("apikey_gw"))
                    .andExpect(jsonPath("$.gatewayKeyHint").doesNotExist())
                    // 13 M d'entrée Opus 5.5 = 52 $ ; calculé 50,25 $ ; écart passerelle 1,75 $.
                    .andExpect(jsonPath("$.days[1].keys[0].apiKeyId").value("apikey_gw"))
                    .andExpect(jsonPath("$.days[1].keys[0].gateway").value(true))
                    .andExpect(jsonPath("$.days[1].keys[1].gateway").value(false))
                    .andExpect(jsonPath("$.days[1].gatewayKeyUsd").value(52.00))
                    .andExpect(jsonPath("$.days[1].gatewayGapUsd").value(1.75))
                    .andExpect(jsonPath("$.days[1].gatewayKeyEur").value(47.84))
                    .andExpect(jsonPath("$.days[1].gatewayGapEur").value(1.61))
                    .andExpect(jsonPath("$.gatewayKeyTotalUsd").value(52.00))
                    .andExpect(jsonPath("$.gatewayKeyTotalEur").value(47.84))
                    // Un jour sans relecture : la clé n'est pas « à zéro », elle est inconnue.
                    .andExpect(jsonPath("$.days[3].gatewayKeyUsd").doesNotExist());
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(target, "gatewayKeyId", "");
        }
    }
}
