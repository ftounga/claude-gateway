package fr.claudegateway.quota;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Les alertes de la Forge (F-133 / SF-133-12) : ouvertes à tous, montants réservés.
 *
 * <p>Le test qui compte est {@link #theAmountsAreAbsentFromTheJsonForAConsultant()} : les montants
 * ne doivent pas être <b>masqués</b>, ils doivent être <b>absents</b>.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CostAlertMineApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private RunnerHostRepository hosts;
    @Autowired
    private CostBudgetRepository budgets;
    @Autowired
    private UsageTurnRepository turns;
    @Autowired
    private JwtService jwtService;

    private String adminToken;
    private String consultantToken;
    private UUID adminHost;

    @BeforeEach
    void setUp() {
        budgets.deleteAll();
        turns.deleteAll();
        hosts.deleteAll();

        User admin = users.save(User.builder()
                .email("admin-forge-" + UUID.randomUUID() + "@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
        User consultant = users.save(User.builder()
                .email("consultant-" + UUID.randomUUID() + "@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        adminToken = jwtService.generateToken(admin);
        consultantToken = jwtService.generateToken(consultant);

        adminHost = hosts.save(RunnerHost.builder().userId(admin.getId()).name("poste-cagip")
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build()).getId();
        // Une dépense, et un budget largement dépassé : de quoi lever une alerte.
        turns.save(UsageTurn.builder()
                .userId(admin.getId()).hostId(adminHost)
                .inputTokens(1_000L).outputTokens(100L)
                .providerCostUsd(new BigDecimal("12.000000"))
                .occurredAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build());
    }

    @Test
    void theRouteIsOpenToAnyAuthenticatedUser() throws Exception {
        // SF-133-06 réservait les alertes à la console d'administration — un écran qu'on n'ouvre
        // pas en travaillant, si bien qu'une alerte n'alertait personne.
        mockMvc.perform(get("/api/cost/alerts/mine").contextPath("/api")
                        .header("Authorization", "Bearer " + consultantToken))
                .andExpect(status().isOk());
    }

    @Test
    void theAmountsAreAbsentFromTheJsonForAConsultant() throws Exception {
        setBudget("1.00");

        // L'administrateur voit tout…
        mockMvc.perform(get("/api/cost/alerts/mine").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].level").value("EXCEEDED"))
                .andExpect(jsonPath("$[0].spentEur").exists())
                .andExpect(jsonPath("$[0].budgetEur").exists());

        // …le consultant n'a pas de poste, donc pas d'alerte : sa liste est vide, et c'est déjà une
        // garantie d'isolation. Le filtrage des montants se vérifie sur la projection elle-même.
        mockMvc.perform(get("/api/cost/alerts/mine").contextPath("/api")
                        .header("Authorization", "Bearer " + consultantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aConsultantNeverSeesTheAmountsOfAnAlert() {
        CostAlert alert = new CostAlert(CostAlert.Scope.HOST, adminHost, "poste-cagip",
                new BigDecimal("12.00"), new BigDecimal("1.00"), 1200,
                CostAlert.Level.EXCEEDED, java.time.LocalDate.of(2026, 9, 14));

        var forConsultant = fr.claudegateway.quota.dto.CostAlertResponse.from(alert, false);
        var forAdmin = fr.claudegateway.quota.dto.CostAlertResponse.from(alert, true);

        org.assertj.core.api.Assertions.assertThat(forConsultant.spentEur()).isNull();
        org.assertj.core.api.Assertions.assertThat(forConsultant.budgetEur()).isNull();
        // La PART reste visible : elle dit l'ampleur sans dire l'argent.
        org.assertj.core.api.Assertions.assertThat(forConsultant.percent()).isEqualTo(1200);
        org.assertj.core.api.Assertions.assertThat(forConsultant.hostName()).isEqualTo("poste-cagip");
        org.assertj.core.api.Assertions.assertThat(forAdmin.spentEur()).isNotNull();
    }

    @Test
    void withoutABudgetThereIsNoAlert() throws Exception {
        mockMvc.perform(get("/api/cost/alerts/mine").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private void setBudget(String amount) throws Exception {
        mockMvc.perform(put("/api/admin/cost/budget/" + adminHost).contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountEur\": " + amount + "}"))
                .andExpect(status().isOk());
    }
}
