package fr.claudegateway.atelier.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.actions.TerminalAction;
import fr.claudegateway.atelier.actions.TerminalActionKind;
import fr.claudegateway.atelier.actions.TerminalActionQui;
import fr.claudegateway.atelier.actions.TerminalActionRepository;
import fr.claudegateway.atelier.actions.TerminalActionService;
import fr.claudegateway.atelier.actions.TerminalActionStatus;
import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.atelier.live.PendingQuestion;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-220 / SF-220-01 — « Qui débloque ? » dans le parcours du sujet : l'outil, quelqu'un d'autre (à qui, depuis
 * quand), ma décision (étape bloquée, question en suspens) — et Bob ne lit rien.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChantierUnblockerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private SubjectJourneyRepository journeyRepository;
    @Autowired
    private SubjectJourneyChantierRepository chantierRepository;
    @Autowired
    private SubjectJourneyEventRepository eventRepository;
    @Autowired
    private SubjectJourneyService service;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JourneyToolExecutor journeyTools;
    @Autowired
    private LiveTurnRegistry liveTurns;
    @Autowired
    private TerminalActionService terminalActions;
    @Autowired
    private TerminalActionRepository terminalActionRepository;

    private String aliceToken;
    private String bobToken;
    private String workspaceId;
    private UUID aliceId;
    private LiveTurn openedTurn;

    @BeforeEach
    void setUp() throws Exception {
        terminalActionRepository.deleteAll();
        eventRepository.deleteAll();
        chantierRepository.deleteAll();
        journeyRepository.deleteAll();
        workspaceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        aliceToken = tokenFor("alice-unblock@ex.com");
        bobToken = tokenFor("bob-unblock@ex.com");
        aliceId = userRepository.findByEmail("alice-unblock@ex.com").orElseThrow().getId();
        workspaceId = createWorkspace(aliceToken);
    }

    @AfterEach
    void tearDown() {
        if (openedTurn != null) {
            liveTurns.close(openedTurn);
        }
    }

    private String tokenFor(String email) {
        User user = userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        subscriptionRepository.save(Subscription.builder().userId(user.getId())
                .planCode(PlanCode.GOLD).status(SubscriptionStatus.ACTIVE).build());
        return jwtService.generateToken(user);
    }

    private String createWorkspace(String token) throws Exception {
        String body = mockMvc.perform(multipart("/api/workspaces").file(zip()).contextPath("/api")
                        .param("name", "Agenor")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private static MockMultipartFile zip() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("README.md"));
            zos.write("hello".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return new MockMultipartFile("file", "project.zip", "application/zip", out.toByteArray());
    }

    private UUID ws() {
        return UUID.fromString(workspaceId);
    }

    private ResultActions journey(String token) throws Exception {
        return mockMvc.perform(get("/api/workspaces/" + workspaceId + "/journey").contextPath("/api")
                .header("Authorization", "Bearer " + token));
    }

    private static JourneyPlan plan(String json) {
        try {
            return JourneyPlan.fromToolInput(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void executing(String planJson) throws Exception {
        mockMvc.perform(put("/api/workspaces/" + workspaceId + "/journey/mode").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"GUIDE\",\"title\":\"Ingress\"}")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
        service.submitDiagnosis(aliceId, ws(), "cert expiré", "openssl", "ELEVEE");
        service.confirmDiagnosis(aliceId, ws());
        service.setPlan(aliceId, ws(), plan(planJson));
        service.validatePlan(aliceId, ws(), null);
    }

    private JourneyToolExecutor.Outcome tool(String name, String json) throws Exception {
        fr.claudegateway.atelier.Workspace w = workspaceRepository.findById(ws()).orElseThrow();
        return journeyTools.execute(w.getUserId(), w, name,
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json));
    }

    private static final String PLAN = "[{\"title\":\"Lire les logs\",\"risk\":\"LECTURE\"},"
            + "{\"title\":\"Obtenir le certificat\",\"risk\":\"EXTERNE\",\"waits_on\":\"cert-gino\"},"
            + "{\"title\":\"Remplacer le certificat\",\"risk\":\"EXTERNE\"}]";

    @Test
    @DisplayName("plan validé : l'outil avance ; puis on attend Gino (à qui, depuis quand) ; la suite hérite")
    void toolThenSomeone() throws Exception {
        executing(PLAN);
        journey(aliceToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chantiers[0].unblocker.who", is("OUTIL")))
                .andExpect(jsonPath("$.chantiers[0].unblocker.step", is(1)))
                .andExpect(jsonPath("$.plan.steps[0].unblocker.who", is("OUTIL")));

        assertThat(tool(JourneyToolCatalog.UPDATE_STEP,
                "{\"step\":1,\"status\":\"VERIFIE\",\"evidence\":\"502 identifiée\"}").error()).isFalse();
        journey(aliceToken)
                .andExpect(jsonPath("$.plan.steps[0].unblocker", nullValue()))
                .andExpect(jsonPath("$.plan.steps[1].unblocker.who", is("AUTRUI")))
                .andExpect(jsonPath("$.chantiers[0].unblocker.who", is("AUTRUI")));

        terminalActions.record(aliceId, ws(), null, "Demander le certificat à Gino", null, "Gino",
                TerminalActionKind.MESSAGE, "cert-gino");
        TerminalAction action = terminalActionRepository.findAll().get(0);
        action.setStatus(TerminalActionStatus.DEMANDE);
        action.setQui(TerminalActionQui.CLIENT);
        action.setRequestedTo("Gino Rossi");
        action.setRequestedAt(OffsetDateTime.parse("2026-10-20T09:00:00Z"));
        terminalActionRepository.save(action);

        journey(aliceToken)
                .andExpect(jsonPath("$.chantiers[0].unblocker.who", is("AUTRUI")))
                .andExpect(jsonPath("$.chantiers[0].unblocker.waitingOn", is("Gino Rossi")))
                .andExpect(jsonPath("$.chantiers[0].unblocker.label", is("On attend quelqu'un")))
                .andExpect(jsonPath("$.plan.steps[1].unblocker.waitingOn", is("Gino Rossi")))
                .andExpect(jsonPath("$.plan.steps[2].unblocker.who", is("AUTRUI")))
                .andExpect(jsonPath("$.chantiers[0].plan.steps[2].unblocker.step", is(2)));

        action.setQui(TerminalActionQui.MOI);
        terminalActionRepository.save(action);
        journey(aliceToken).andExpect(jsonPath("$.chantiers[0].unblocker.who", is("MOI")));
    }

    @Test
    @DisplayName("étape bloquée par l'agent, question en suspens : ma décision")
    void myDecision() throws Exception {
        executing(PLAN);
        assertThat(tool(JourneyToolCatalog.UPDATE_STEP,
                "{\"step\":1,\"status\":\"BLOQUE\",\"evidence\":\"accès refusé\"}").error()).isFalse();
        journey(aliceToken)
                .andExpect(jsonPath("$.chantiers[0].unblocker.who", is("MOI")))
                .andExpect(jsonPath("$.plan.steps[0].unblocker.detail", is("Étape 1 bloquée : accès refusé")));

        assertThat(tool(JourneyToolCatalog.UPDATE_STEP, "{\"step\":1,\"status\":\"A_FAIRE\"}").error()).isFalse();
        openedTurn = liveTurns.open(aliceId, ws());
        openedTurn.publishQuestionRequest(java.util.Map.of("callId", "c1"),
                new PendingQuestion("c1", "[]", 60_000L, System.currentTimeMillis()));
        journey(aliceToken)
                .andExpect(jsonPath("$.chantiers[0].unblocker.who", is("MOI")))
                .andExpect(jsonPath("$.chantiers[0].unblocker.detail",
                        is("Une question ou une autorisation attend votre réponse dans ce sujet.")));
    }

    @Test
    @DisplayName("isolation : Bob ne lit pas le parcours d'Alice (404)")
    void isolation() throws Exception {
        executing(PLAN);
        journey(bobToken).andExpect(status().isNotFound());
    }
}
