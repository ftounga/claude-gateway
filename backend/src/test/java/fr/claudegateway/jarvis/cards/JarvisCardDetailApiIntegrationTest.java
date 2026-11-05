package fr.claudegateway.jarvis.cards;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.ResultActions;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.actions.TerminalAction;
import fr.claudegateway.atelier.actions.TerminalActionKind;
import fr.claudegateway.atelier.actions.TerminalActionRepository;
import fr.claudegateway.atelier.actions.TerminalActionStatus;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.jarvis.companion.JarvisCompanionScope;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** F-248 / SF-248-06 — {@code GET /api/jarvis/cards/{id}} : détail sous le compte, 404 ailleurs, 400, portée compagnon. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisCardDetailApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private TerminalActionRepository actions;
    @Autowired private JwtService jwtService;
    @Autowired private JarvisCardController controller;
    @Autowired private JarvisCardDetailService service;

    private String aliceToken;
    private String bobToken;
    private UUID action;
    private UUID ws;

    @BeforeEach
    void setUp() {
        User alice = user();
        User bob = user();
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(bob);
        ws = workspaces.save(Workspace.builder().userId(alice.getId()).name("Edenred").projectPath("edenred")
                .executionTarget(WorkspaceExecutionTarget.RUNNER).build()).getId();
        OffsetDateTime at = OffsetDateTime.now().minusHours(3);
        action = actions.save(TerminalAction.builder().userId(alice.getId()).workspaceId(ws)
                .description("Obtenir l'accès VPN password=hunter2hunter2").kind(TerminalActionKind.ACTION)
                .status(TerminalActionStatus.DEMANDE).requestedTo("Rémi").channel("Teams").chantierNumber(2)
                .requestedAt(at).createdAt(at).updatedAt(at).build()).getId();
    }

    private User user() {
        return userRepository.save(User.builder().email("cards-d-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).createdAt(OffsetDateTime.now()).build());
    }

    private ResultActions open(String token, String id) throws Exception {
        return mockMvc.perform(get("/api/jarvis/cards/" + id).contextPath("/api")
                .header("Authorization", "Bearer " + token));
    }

    @Test
    @DisplayName("Alice ouvre son attente : tous les champs, le sujet, le chantier, le lien ; jamais un secret")
    void opensHerOwn() throws Exception {
        String body = open(aliceToken, "fait:attente:" + action).andExpect(status().isOk())
                .andExpect(jsonPath("$.card.id").value("fait:attente:" + action))
                .andExpect(jsonPath("$.card.type").value("ATTENTE"))
                .andExpect(jsonPath("$.card.link").value("/atelier/" + ws + "?chantier=2"))
                .andExpect(jsonPath("$.subjectName").value("Edenred"))
                .andExpect(jsonPath("$.chantier").value(2))
                .andExpect(jsonPath("$.fields[?(@.label == 'Par')].value").value("Teams"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("Qui").contains("Rémi").contains("password=•••").doesNotContain("hunter2");
    }

    @Test
    @DisplayName("isolation : l'attente d'Alice est introuvable pour Bob (404) ; famille inconnue 404 ; illisible 400")
    void isolationAndValidation() throws Exception {
        open(bobToken, "fait:attente:" + action).andExpect(status().isNotFound());
        open(aliceToken, "fait:inconnu:abc").andExpect(status().isNotFound());
        open(aliceToken, "fait:attente:pas-un-uuid").andExpect(status().isNotFound());
        open(aliceToken, "n-importe-quoi").andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/jarvis/cards/fait:attente:" + action).contextPath("/api"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    @Test
    @DisplayName("K7 : coupe-circuit éteint ⇒ 503 ; portée compagnon : la route est ouverte, la liste reste fermée")
    void killSwitchAndScope() throws Exception {
        java.lang.reflect.Field f = JarvisCardController.class.getDeclaredField("properties");
        f.setAccessible(true);
        Object before = f.get(controller);
        f.set(controller, new JarvisCardsProperties(false));
        try {
            open(aliceToken, "fait:attente:" + action).andExpect(status().isServiceUnavailable());
        } finally {
            f.set(controller, before);
        }
        assertThat(JarvisCompanionScope.allows("GET", "/jarvis/cards/fait:attente:" + action)).isTrue();
        assertThat(JarvisCompanionScope.allows("DELETE", "/jarvis/cards/x")).isFalse();
        assertThat(JarvisCompanionScope.allows("GET", "/jarvis/cards/a/b")).isFalse();
        assertThat(service.families()).contains("attente", "envoi", "courriel", "personne");
    }

    @Test
    @DisplayName("le filtre des secrets vaut aussi au plein écran (valeur à forme de secret écartée)")
    void detailFieldsFilterSecrets() {
        var fields = JarvisCardTemplates.detailFields(new java.util.LinkedHashMap<>(java.util.Map.of(
                "Note", "glpat-abcdefghijklmnopqrstu")));
        assertThat(fields).isEmpty();
    }
}
