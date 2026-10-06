package fr.claudegateway.atelier.poste;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.quota.UsageTurn;
import fr.claudegateway.quota.UsageTurnRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * La mesure du terminal central (F-178 / SF-178-04), sur la base réelle (H2 + Liquibase) : appels comptés
 * depuis la trajectoire d'outils (recall poste / fil, sujets_etat, fouille), coût moyen d'un tour,
 * avant / après — terminaux du poste du seul utilisateur, garde administrateur.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PosteToolsMeasureIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PosteToolsMeasureService service;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private AtelierMessageRepository messageRepository;
    @Autowired private UsageTurnRepository turnRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;

    private final OffsetDateTime pivot = OffsetDateTime.of(2026, 10, 6, 0, 0, 0, 0, ZoneOffset.UTC);
    private User admin;
    private User member;
    private UUID adminHost;
    private UUID adminSubject;
    private UUID memberHost;

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        turnRepository.deleteAll();
        admin = user("po-poste-measure@example.com", UserRole.ADMIN);
        member = user("membre-poste-measure@example.com", UserRole.USER);
        adminHost = terminal(admin, "racine", true);
        adminSubject = terminal(admin, "data-platform", false);
        memberHost = terminal(member, "racine", true);
    }

    private User user(String email, UserRole role) {
        return userRepository.findByEmail(email).orElseGet(() -> userRepository.save(User.builder().email(email)
                .emailVerified(true).provider(AuthProvider.LOCAL).role(role).build()));
    }

    private UUID terminal(User owner, String name, boolean host) {
        return workspaceRepository.save(Workspace.builder().userId(owner.getId()).name(name)
                .executionTarget(WorkspaceExecutionTarget.SANDBOX).hostTerminal(host).build()).getId();
    }

    private void trace(User owner, UUID ws, String json, OffsetDateTime at) {
        AtelierMessage saved = messageRepository.save(AtelierMessage.builder().workspaceId(ws).userId(owner.getId())
                .role("ASSISTANT").content("réponse").toolTrace(json).build());
        jdbc.update("update atelier_messages set created_at = ? where id = ?", Timestamp.from(at.toInstant()),
                saved.getId());
    }

    private void turn(User owner, UUID ws, String costUsd, OffsetDateTime at) {
        turnRepository.save(UsageTurn.builder().userId(owner.getId()).workspaceId(ws)
                .providerCostUsd(new BigDecimal(costUsd)).occurredAt(at).build());
    }

    private static String calls(String... calls) {
        return "{\"steps\":[{\"text\":\"\",\"calls\":[" + String.join(",", calls) + "]}]}";
    }

    private static String call(String name, String input) {
        return "{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"" + name + "\",\"input\":" + input
                + ",\"result\":\"ok\",\"error\":false}";
    }

    @Test
    @DisplayName("compte recall poste / fil, sujets_etat et la fouille ; coût moyen d'un tour ; isolation")
    void measures() {
        OffsetDateTime after = pivot.plusDays(1);
        trace(admin, adminHost, calls(call("recall", "{\"query\":\"Atlantis\",\"portee\":\"poste\"}"),
                call("sujets_etat", "{}"), call("bash", "{\"command\":\"ls\"}")), after);
        trace(admin, adminHost, calls(call("recall", "{\"query\":\"x\"}")), after);
        trace(admin, adminHost, calls(call("bash", "{\"command\":\"cat a\"}"), call("read_file", "{\"path\":\"a\"}")),
                pivot.minusDays(2));
        // Un sujet (pas un terminal du poste) et un autre compte : hors mesure.
        trace(admin, adminSubject, calls(call("sujets_etat", "{}")), after);
        trace(member, memberHost, calls(call("sujets_etat", "{}")), after);
        turn(admin, adminHost, "0.30", after);
        turn(admin, adminHost, "0.10", after);
        turn(admin, adminSubject, "5.00", after);
        turn(member, memberHost, "9.00", after);

        PosteToolsMeasureService.Comparison c = service.compare(admin.getId(), pivot, 7, pivot.plusDays(7));

        assertThat(c.after().hostTerminals()).isEqualTo(1);
        assertThat(c.after().calls()).containsEntry("recall_poste", 1L).containsEntry("recall_fil", 1L)
                .containsEntry("sujets_etat", 1L).containsEntry("bash", 1L);
        assertThat(c.after().turns()).isEqualTo(2);
        assertThat(c.after().costPerTurnUsd()).isEqualByComparingTo("0.20");
        assertThat(c.before().calls()).containsEntry("bash", 1L).containsEntry("read_file", 1L)
                .containsEntry("recall_poste", 0L);
        assertThat(c.notes()).anyMatch(n -> n.contains("Nouveaux chemins"));
    }

    @Test
    @DisplayName("l'endpoint est réservé à l'administrateur ; une date illisible est refusée")
    void adminOnly() throws Exception {
        mockMvc.perform(get("/api/admin/poste-tools/measure").contextPath("/api")
                        .header("Authorization", "Bearer " + jwtService.generateToken(member)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/poste-tools/measure").param("pivot", "2026-10-06").param("days", "7")
                        .contextPath("/api").header("Authorization", "Bearer " + jwtService.generateToken(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.after.calls.recall_poste").exists())
                .andExpect(jsonPath("$.notes").isArray());
        mockMvc.perform(get("/api/admin/poste-tools/measure").param("pivot", "hier").contextPath("/api")
                        .header("Authorization", "Bearer " + jwtService.generateToken(admin)))
                .andExpect(status().isBadRequest());
    }
}
