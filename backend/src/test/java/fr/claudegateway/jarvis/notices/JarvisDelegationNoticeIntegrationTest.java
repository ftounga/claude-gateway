package fr.claudegateway.jarvis.notices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * F-272 / SF-272-05 — la réponse d'une délégation arrivée pendant l'absence : annonce posée à la fin du tour, dite à la
 * reprise ; jamais rouverte une fois dite par l'écran ; isolation A/B.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisDelegationNoticeIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwt;
    @Autowired private JarvisNoticeService notices;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        alice = user("deleg-notice-a");
        bob = user("deleg-notice-b");
    }

    @Test
    @DisplayName("fin d'un tour délégué : annonce ouverte, à dire, en mots-clés ; la phrase dite porte la ligne 🎙")
    void aFinishedDelegationIsAnnounced() throws Exception {
        UUID delegation = UUID.randomUUID();
        notices.delegationDone(alice.getId(), delegation, null, "tickets LDIC", "data-platform",
                "Trois tickets LDIC sont bloqués, Monsieur.", false);

        mvc.perform(get("/api/jarvis/notices").contextPath("/api").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("DELEGATION_DONE"))
                .andExpect(jsonPath("$[0].text").value("Réponse prête · tickets LDIC · data-platform"))
                .andExpect(jsonPath("$[0].speech").value(
                        "Réponse sur tickets LDIC : Trois tickets LDIC sont bloqués, Monsieur."))
                .andExpect(jsonPath("$[0].spoken").value(false))
                .andExpect(jsonPath("$[0].delegationId").value(delegation.toString()));
        mvc.perform(get("/api/jarvis/notices").contextPath("/api").header("Authorization", bearer(bob)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("dite par l'écran avant la fin du tour : la fin ne la rouvre pas ; après : elle est close")
    void spokenByTheScreenIsNeverReopened() throws Exception {
        UUID before = UUID.randomUUID();
        mvc.perform(post("/api/jarvis/notices/delegations/" + before + "/spoken").contextPath("/api")
                .header("Authorization", bearer(alice))).andExpect(status().isNoContent());
        notices.delegationDone(alice.getId(), before, null, "x", "y", "z", false);

        UUID after = UUID.randomUUID();
        notices.delegationDone(alice.getId(), after, null, "a", "b", "c", false);
        mvc.perform(post("/api/jarvis/notices/delegations/" + after + "/spoken").contextPath("/api")
                .header("Authorization", bearer(alice))).andExpect(status().isNoContent());

        assertThat(notices.open(alice.getId())).isEmpty();
    }

    @Test
    @DisplayName("isolation : Bob qui marque la délégation d'Alice ne ferme rien chez Alice")
    void bobCannotCloseAlicesNotice() throws Exception {
        UUID delegation = UUID.randomUUID();
        notices.delegationDone(alice.getId(), delegation, null, "tickets", "waf", "", true);
        mvc.perform(post("/api/jarvis/notices/delegations/" + delegation + "/spoken").contextPath("/api")
                .header("Authorization", bearer(bob))).andExpect(status().isNoContent());

        assertThat(notices.open(alice.getId())).singleElement().satisfies(n -> {
            assertThat(n.spoken()).isFalse();
            assertThat(n.text()).isEqualTo("Sans réponse · tickets · waf");
            assertThat(n.speech()).isEqualTo("La recherche sur tickets n'a pas abouti.");
        });
        mvc.perform(post("/api/jarvis/notices/delegations/" + delegation + "/spoken").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    private String bearer(User u) {
        return "Bearer " + jwt.generateToken(u);
    }

    private User user(String prefix) {
        return users.save(User.builder().email(prefix + "-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }
}
