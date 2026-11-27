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

import fr.claudegateway.ai.failover.AiChannel;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.quota.TurnCost;
import fr.claudegateway.quota.UsageTurn;
import fr.claudegateway.quota.UsageTurnRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-279 / SF-279-01 — {@code /api/admin/cost/providers} : les deux jauges du mois, administrateur seul ; la facture
 * AWS rangée sans polluer le rapprochement Anthropic ; la voix et l'audio hors des jauges.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProviderSpendApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwtService;
    @Autowired private UsageTurnRepository usageTurns;
    @Autowired private ProviderCostReportRepository reports;
    @MockitoBean private ProviderBillingReport anthropicBilling;
    @MockitoBean private BedrockBillingReport bedrockBilling;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        reports.deleteAll();
        usageTurns.deleteAll();
        User admin = users.save(User.builder().email("admin-pots-" + UUID.randomUUID() + "@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
        User ordinary = users.save(User.builder().email("user-pots-" + UUID.randomUUID() + "@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        adminToken = jwtService.generateToken(admin);
        userToken = jwtService.generateToken(ordinary);
        when(anthropicBilling.configured()).thenReturn(false);
        when(bedrockBilling.enabled()).thenReturn(true);
    }

    private void turn(String model, String usd, AiChannel channel) {
        usageTurns.save(UsageTurn.builder().userId(UUID.randomUUID()).inputTokens(1L).outputTokens(1L)
                .providerCostUsd(new BigDecimal(usd)).costSource(TurnCost.Source.CALCULATED).model(model)
                .provider(channel).occurredAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
    }

    @Test
    @DisplayName("non-administrateur : 403 sur la lecture et sur la relecture")
    void forbiddenToOrdinaryUsers() throws Exception {
        mockMvc.perform(get("/api/admin/cost/providers").contextPath("/api")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/cost/providers/refresh").contextPath("/api")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("aujourd'hui estimé par canal ; voix et audio exclus ; total = somme ; en euros")
    void estimatesTodayPerChannelWithoutAudio() throws Exception {
        turn("claude-opus-5-5", "10.00", AiChannel.ANTHROPIC);
        turn("claude-opus-5-5", "5.00", AiChannel.BEDROCK);
        turn("anthropic.claude-haiku-4-5-20251001-v1:0", "1.00", AiChannel.BEDROCK);
        turn("elevenlabs", "100.00", AiChannel.ANTHROPIC);
        turn("gpt-4o-mini-tts", "100.00", AiChannel.BEDROCK);
        turn(null, "100.00", AiChannel.ANTHROPIC);

        mockMvc.perform(get("/api/admin/cost/providers").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gauges[0].provider").value("ANTHROPIC"))
                .andExpect(jsonPath("$.gauges[0].billing").value("PREPAID"))
                .andExpect(jsonPath("$.gauges[0].spentEur").value(9.20))
                .andExpect(jsonPath("$.gauges[0].status").value("ESTIME"))
                .andExpect(jsonPath("$.gauges[0].statusNote").value(
                        org.hamcrest.Matchers.containsString("APP_ANTHROPIC_ADMIN_KEY")))
                .andExpect(jsonPath("$.gauges[1].provider").value("BEDROCK"))
                .andExpect(jsonPath("$.gauges[1].billing").value("POSTPAID"))
                .andExpect(jsonPath("$.gauges[1].spentEur").value(5.52))
                .andExpect(jsonPath("$.totalEur").value(14.72))
                .andExpect(jsonPath("$.usdToEur").value(0.92))
                .andExpect(jsonPath("$.channel.mode").value("OFF"))
                .andExpect(jsonPath("$.channel.current").value("ANTHROPIC"))
                .andExpect(jsonPath("$.caveats.length()").value(5));
    }

    @Test
    @DisplayName("relecture : la facture AWS rangée en BEDROCK, le rapprochement Anthropic inchangé ; panne ⇒ note")
    void refreshStoresBedrockWithoutPollutingReconciliation() throws Exception {
        LocalDate settled = LocalDate.now(ZoneOffset.UTC).minusDays(2);
        when(bedrockBilling.billedCosts(any(), any())).thenReturn(List.of(
                new BedrockBillingReport.BilledDay(settled, new BigDecimal("17.27"))));

        mockMvc.perform(post("/api/admin/cost/providers/refresh").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gauges[1].provider").value("BEDROCK"));
        org.assertj.core.api.Assertions.assertThat(reports.findAll())
                .singleElement().satisfies(r -> {
                    org.assertj.core.api.Assertions.assertThat(r.getKind()).isEqualTo(ProviderCostReport.BEDROCK);
                    org.assertj.core.api.Assertions.assertThat(r.getDay()).isEqualTo(settled);
                });
        // Relire remplace, ne double pas.
        mockMvc.perform(post("/api/admin/cost/providers/refresh").contextPath("/api")
                .header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(reports.count()).isEqualTo(1);

        // Le rapprochement Anthropic ne voit pas la ligne AWS : ni clé, ni facture, ni date de relecture.
        mockMvc.perform(get("/api/admin/cost/reconciliation").param("days", "7").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billedTotalUsd").value(0))
                .andExpect(jsonPath("$.days[2].keys.length()").value(0))
                .andExpect(jsonPath("$.lastFetchedAt").doesNotExist());

        // Le mois en cours la compte (si le jour clos est dans le mois).
        if (settled.getMonth() == LocalDate.now(ZoneOffset.UTC).getMonth()) {
            mockMvc.perform(get("/api/admin/cost/providers").contextPath("/api")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(jsonPath("$.gauges[1].status").value("RECALE"))
                    .andExpect(jsonPath("$.gauges[1].billedEur").value(15.89))
                    .andExpect(jsonPath("$.gauges[1].billedThrough").value(settled.toString()));
        }

        // Cost Explorer refuse : la ligne déjà rangée reste ; la note dit le droit manquant.
        when(bedrockBilling.billedCosts(any(), any()))
                .thenThrow(new BedrockBillingReport.Unavailable(BedrockBillingReport.Reason.DENIED));
        mockMvc.perform(post("/api/admin/cost/providers/refresh").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gauges[1].statusNote").value(
                        org.hamcrest.Matchers.containsString("ce:GetCostAndUsage")));
        org.assertj.core.api.Assertions.assertThat(reports.count()).isEqualTo(1);
    }
}
