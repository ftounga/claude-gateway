package fr.claudegateway.runner.rupture;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

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
 * Le journal des ruptures, de bout en bout (F-161 / SF-161-03).
 *
 * <p>Ce que ces tests tiennent : le <b>403</b> pour qui n'est pas administrateur — jamais un
 * rapport vide, qui lui laisserait croire que son poste n'a jamais décroché —, et l'<b>isolation</b>
 * : les ruptures d'un autre compte n'apparaissent pas, même pour un administrateur.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerDisconnectApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RunnerDisconnectRepository ruptures;
    @Autowired private JwtService jwtService;

    private UUID adminId;
    private String adminToken;
    private UUID otherAdminId;
    private String userToken;

    @BeforeEach
    void setUp() {
        ruptures.deleteAll();
        userRepository.deleteAll();
        User admin = save("admin-ruptures@ex.com", UserRole.ADMIN);
        adminId = admin.getId();
        adminToken = jwtService.generateToken(admin);
        otherAdminId = save("admin2-ruptures@ex.com", UserRole.ADMIN).getId();
        userToken = jwtService.generateToken(save("user-ruptures@ex.com", UserRole.USER));
    }

    private User save(String email, UserRole role) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(role).build());
    }

    private void breakFor(UUID userId, RunnerDisconnectCause cause, int inFlight) {
        ruptures.save(RunnerDisconnect.builder()
                .userId(userId)
                .hostId(UUID.randomUUID())
                .cause(cause)
                .transport(RunnerTransport.WEBSOCKET)
                .livedMs(600_000L)
                .silentMs(95_000L)
                .closeStatus("SESSION_NOT_RELIABLE")
                .callsInFlight(inFlight)
                .createdAt(OffsetDateTime.now().minusHours(2))
                .build());
    }

    @Test
    @DisplayName("l'administrateur lit le rapport : total, subies, et celles qui ont coûté")
    void anAdminReadsTheReport() throws Exception {
        breakFor(adminId, RunnerDisconnectCause.SOCKET_MUETTE, 2);
        breakFor(adminId, RunnerDisconnectCause.ARRET_PROPRE, 0);

        mockMvc.perform(get("/admin/runner-disconnects").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(2)))
                .andExpect(jsonPath("$.subies", is(1)))
                .andExpect(jsonPath("$.avecAppels", is(1)))
                .andExpect(jsonPath("$.parCause.length()", is(RunnerDisconnectCause.values().length)))
                .andExpect(jsonPath("$.parHeure.length()", is(24)));
    }

    @Test
    @DisplayName("ISOLATION : les ruptures d'un AUTRE compte n'apparaissent pas")
    void anotherAccountIsInvisible() throws Exception {
        breakFor(otherAdminId, RunnerDisconnectCause.SOCKET_MUETTE, 3);

        mockMvc.perform(get("/admin/runner-disconnects").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(0)))
                .andExpect(jsonPath("$.parPoste.length()", is(0)));
    }

    @Test
    @DisplayName("un non-administrateur reçoit 403 — jamais un rapport vide qui le rassurerait à tort")
    void aPlainUserIsRefused() throws Exception {
        breakFor(adminId, RunnerDisconnectCause.SOCKET_MUETTE, 0);

        mockMvc.perform(get("/admin/runner-disconnects").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("la fenêtre est bornée : une demande démesurée est ramenée à 90 jours")
    void theWindowIsBounded() throws Exception {
        mockMvc.perform(get("/admin/runner-disconnects").param("days", "100000")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(0)));
    }
}
