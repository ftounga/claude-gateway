package fr.claudegateway.diagnostic;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Le diagnostic du produit, de bout en bout (F-156 / SF-156-05).
 *
 * <p>Ce que ces tests tiennent : <b>403</b> pour un non-administrateur — jamais un rapport vide qui
 * laisserait croire que tout va bien —, et un compte sans activité qui rend un rapport honnête
 * plutôt qu'une erreur.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductDiagnosticApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        adminToken = jwtService.generateToken(save("admin-diag@ex.com", UserRole.ADMIN));
        userToken = jwtService.generateToken(save("user-diag@ex.com", UserRole.USER));
    }

    private User save(String email, UserRole role) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(role).build());
    }

    @Test
    @DisplayName("un NON-administrateur reçoit 403 — jamais un rapport vide")
    void aNonAdminIsForbidden() throws Exception {
        mockMvc.perform(post("/api/admin/diagnostic").contextPath("/api")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("sans jeton, rien du tout")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/admin/diagnostic").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("un compte sans activité rend un rapport HONNÊTE : aucun constat, la parité lisible")
    void anAccountWithoutActivityGetsAnHonestReport() throws Exception {
        mockMvc.perform(post("/api/admin/diagnostic").contextPath("/api")
                        .param("days", "7")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turns", is(0)))
                .andExpect(jsonPath("$.findings").isEmpty())
                .andExpect(jsonPath("$.specLines").isEmpty())
                .andExpect(jsonPath("$.parity.length()", greaterThan(0)))
                .andExpect(jsonPath("$.parity[0].state", is("NON_OBSERVEE")));
    }

    @Test
    @DisplayName("la durée hors bornes est corrigée, et le rapport reste servi")
    void anOutOfBoundsDurationIsCorrected() throws Exception {
        mockMvc.perform(post("/api/admin/diagnostic").contextPath("/api")
                        .param("days", "9999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/diagnostic").contextPath("/api")
                        .param("days", "-3")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("la parité montre les hooks ÉCARTÉS, avec leur raison — jamais comme un manque")
    void hooksAreShownAsExcluded() throws Exception {
        mockMvc.perform(post("/api/admin/diagnostic").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parity[?(@.referenceId == 'hooks')].state")
                        .value(org.hamcrest.Matchers.hasItem("ECARTEE")));
    }
}
