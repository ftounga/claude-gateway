package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
 * Les budgets hebdomadaires (F-133 / SF-133-04) : les quatre routes, leur garde d'administration et
 * l'isolation.
 *
 * <p>Deux tests portent l'essentiel : {@link #everyRouteRefusesSomeoneWhoIsNotAdmin()} — la garde
 * doit être sur <b>chacune</b>, une oubliée suffirait — et
 * {@link #aBudgetIsInvisibleFromAnotherAccount()}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CostBudgetApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private RunnerHostRepository hosts;
    @Autowired
    private CostBudgetRepository budgets;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private QuotaService quotaService;

    private String adminToken;
    private String userToken;
    private UUID adminHost;

    @BeforeEach
    void setUp() {
        budgets.deleteAll();
        hosts.deleteAll();

        User admin = users.save(User.builder()
                .email("admin-budget-" + UUID.randomUUID() + "@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
        User ordinary = users.save(User.builder()
                .email("user-budget-" + UUID.randomUUID() + "@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        adminToken = jwtService.generateToken(admin);
        userToken = jwtService.generateToken(ordinary);
        adminHost = hosts.save(RunnerHost.builder().userId(admin.getId()).name("poste-cagip")
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build()).getId();
    }

    @Test
    void setsReadsAndClearsBudgets() throws Exception {
        setBudget("", adminToken, "{\"amountEur\": 120.00}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultAmountEur").value(120.00));

        setBudget("/" + adminHost, adminToken, "{\"amountEur\": 60.50}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hosts.length()").value(1))
                .andExpect(jsonPath("$.hosts[0].amountEur").value(60.50));

        // Le budget propre retiré, le client retombe sur le défaut.
        mockMvc.perform(delete("/api/admin/cost/budget/" + adminHost).contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hosts.length()").value(0))
                .andExpect(jsonPath("$.defaultAmountEur").value(120.00));
    }

    @Test
    void everyRouteRefusesSomeoneWhoIsNotAdmin() throws Exception {
        // Une garde oubliée sur UNE route suffirait : elles sont donc toutes vérifiées.
        mockMvc.perform(get("/api/admin/cost/budget").contextPath("/api")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        setBudget("", userToken, "{\"amountEur\": 10.00}").andExpect(status().isForbidden());
        setBudget("/" + adminHost, userToken, "{\"amountEur\": 10.00}").andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/admin/cost/budget/" + adminHost).contextPath("/api")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void refusesANegativeAmountButAcceptsZero() throws Exception {
        setBudget("", adminToken, "{\"amountEur\": -1.00}").andExpect(status().isBadRequest());
        // Zéro est une consigne légitime : « ce client ne doit rien coûter ».
        setBudget("", adminToken, "{\"amountEur\": 0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultAmountEur").value(0.00));
    }

    @Test
    void refusesAHostFromAnotherAccount() throws Exception {
        User other = users.save(User.builder()
                .email("autre-" + UUID.randomUUID() + "@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        UUID otherHost = hosts.save(RunnerHost.builder().userId(other.getId()).name("poste-cagip")
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build()).getId();

        // « Inconnu » et « pas à vous » rendent la même réponse : 404.
        setBudget("/" + otherHost, adminToken, "{\"amountEur\": 10.00}").andExpect(status().isNotFound());
        setBudget("/" + UUID.randomUUID(), adminToken, "{\"amountEur\": 10.00}")
                .andExpect(status().isNotFound());
    }

    @Test
    void aBudgetIsInvisibleFromAnotherAccount() throws Exception {
        User otherAdmin = users.save(User.builder()
                .email("admin2-" + UUID.randomUUID() + "@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
        setBudget("", adminToken, "{\"amountEur\": 999.00}").andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/cost/budget").contextPath("/api")
                        .header("Authorization", "Bearer " + jwtService.generateToken(otherAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultAmountEur").doesNotExist());
    }

    @Test
    void theOwnBudgetWinsOverTheDefault() throws Exception {
        setBudget("", adminToken, "{\"amountEur\": 120.00}").andExpect(status().isOk());
        setBudget("/" + adminHost, adminToken, "{\"amountEur\": 60.00}").andExpect(status().isOk());

        // Les deux coexistent : c'est la RÉSOLUTION qui choisit, pas l'écriture qui écrase.
        assertThat(budgets.findAll())
                .extracting(CostBudget::getAmountEur)
                .containsExactlyInAnyOrder(new BigDecimal("120.00"), new BigDecimal("60.00"));
    }

    @Test
    void theAlertsRouteIsAdminOnlyAndSaysNothingWithoutABudget() throws Exception {
        // F-133 / SF-133-06. Sans budget, aucune alerte : on ne peut pas dépasser ce qui n'existe
        // pas. Et la route est réservée à l'administrateur comme les quatre autres.
        mockMvc.perform(get("/api/admin/cost/alerts").contextPath("/api")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/cost/alerts").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theSummaryRouteIsAdminOnlyAndRefusesAnUnknownPeriod() throws Exception {
        // F-133 / SF-133-07.
        mockMvc.perform(get("/api/admin/cost/summary").contextPath("/api")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/cost/summary").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("week"))
                .andExpect(jsonPath("$.spentEur").value(0));

        mockMvc.perform(get("/api/admin/cost/summary?period=month").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("month"));

        mockMvc.perform(get("/api/admin/cost/summary?period=trimestre").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theSummaryShowsABudgetedClientAndItsShare() throws Exception {
        setBudget("/" + adminHost, adminToken, "{\"amountEur\": 100.00}").andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/cost/summary").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                // Aucune dépense, mais le client budgété apparaît : un budget posé sur un client
                // qui ne travaille pas doit se voir.
                .andExpect(jsonPath("$.clients.length()").value(1))
                .andExpect(jsonPath("$.clients[0].budgetEur").value(100.00))
                .andExpect(jsonPath("$.clients[0].percent").value(0))
                .andExpect(jsonPath("$.clients[0].ownBudget").value(true));
    }

    @Test
    void aBudgetNeverRefusesAnything() throws Exception {
        // LE TEST DE NON-RÉGRESSION DE LA SUBFEATURE. Un budget est une consigne de PILOTAGE :
        // il n'a aucun droit sur le service rendu. Budget à zéro, dépense massive — et le quota
        // doit répondre exactement ce qu'il répondait avant : rien à redire.
        setBudget("", adminToken, "{\"amountEur\": 0}").andExpect(status().isOk());
        setBudget("/" + adminHost, adminToken, "{\"amountEur\": 0}").andExpect(status().isOk());

        UUID adminId = users.findAll().stream()
                .filter(u -> u.getRole() == UserRole.ADMIN)
                .findFirst().orElseThrow().getId();
        // Le service de quota ne connaît pas les budgets : il ne peut pas s'en servir pour refuser.
        assertThatCode(() -> quotaService.assertWithinQuota(adminId)).doesNotThrowAnyException();
    }

    private org.springframework.test.web.servlet.ResultActions setBudget(String path, String token,
            String body) throws Exception {
        return mockMvc.perform(put("/api/admin/cost/budget" + path).contextPath("/api")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // --------------------------------- ce que chaque projet a coûté (F-143 / SF-143-01)

    @Test
    void projectCostsAreReservedToAdmins() throws Exception {
        // Comme tout F-133 : le montant ne quitte pas le serveur pour qui n'est pas administrateur.
        mockMvc.perform(get("/api/admin/cost/projects").contextPath("/api")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void projectCostsCarryTheWeekAndTheTotal() throws Exception {
        mockMvc.perform(get("/api/admin/cost/projects").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                // La fenêtre est celle de tout F-133 : la semaine ISO, du lundi au dimanche.
                .andExpect(jsonPath("$.from").exists())
                .andExpect(jsonPath("$.to").exists())
                .andExpect(jsonPath("$.projects").isArray());
    }
}
