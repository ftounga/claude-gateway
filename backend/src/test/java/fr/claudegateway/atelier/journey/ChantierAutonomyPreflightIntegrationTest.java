package fr.claudegateway.atelier.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * F-219 / SF-219-01 — le contrôle avant départ d'un chantier : prêt, bloqué par l'agent, débloqué, question
 * en suspens, information non suivie — et Bob ne lit rien.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChantierAutonomyPreflightIntegrationTest {

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

    private String aliceToken;
    private String bobToken;
    private String workspaceId;
    private UUID aliceId;
    private LiveTurn openedTurn;

    @BeforeEach
    void setUp() throws Exception {
        eventRepository.deleteAll();
        chantierRepository.deleteAll();
        journeyRepository.deleteAll();
        workspaceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        aliceToken = tokenFor("alice-preflight@ex.com");
        bobToken = tokenFor("bob-preflight@ex.com");
        aliceId = userRepository.findByEmail("alice-preflight@ex.com").orElseThrow().getId();
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

    private ResultActions read(String token, String path) throws Exception {
        return mockMvc.perform(get("/api/workspaces/" + workspaceId + "/journey/" + path).contextPath("/api")
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

    private static final String TWO_STEPS = "[{\"title\":\"Remplacer le certificat\",\"risk\":\"REVERSIBLE\"},"
            + "{\"title\":\"Redémarrer l'ingress\",\"risk\":\"EXTERNE\",\"depends_on\":[1]}]";

    @Test
    @DisplayName("plan validé en exécution : prêt à partir ; la liste rend chaque chantier ouvert")
    void ready() throws Exception {
        executing(TWO_STEPS);
        read(aliceToken, "chantiers/1/autonomy/preflight")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number", is(1)))
                .andExpect(jsonPath("$.ready", is(true)))
                .andExpect(jsonPath("$.reasons.length()", is(0)));
        read(aliceToken, "autonomy")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chantiers.length()", is(1)))
                .andExpect(jsonPath("$.chantiers[0].title", is("Ingress")))
                .andExpect(jsonPath("$.chantiers[0].preflight.ready", is(true)));
    }

    @Test
    @DisplayName("l'agent bloque une étape (raison requise) puis la débloque")
    void blockAndUnblock() throws Exception {
        executing(TWO_STEPS);
        JourneyToolExecutor.Outcome noReason = tool(JourneyToolCatalog.UPDATE_STEP, "{\"step\":1,\"status\":\"BLOQUE\"}");
        assertThat(noReason.error()).isTrue();
        assertThat(noReason.content()).contains("raison");

        JourneyToolExecutor.Outcome blocked = tool(JourneyToolCatalog.UPDATE_STEP,
                "{\"step\":1,\"status\":\"BLOQUE\",\"evidence\":\"le certificat n'est pas encore livré\"}");
        assertThat(blocked.error()).isFalse();
        assertThat(blocked.content()).contains("BLOQUÉE");
        read(aliceToken, "chantiers/1/autonomy/preflight")
                .andExpect(jsonPath("$.ready", is(false)))
                .andExpect(jsonPath("$.reasons[0].code", is("STEP_BLOCKED")))
                .andExpect(jsonPath("$.reasons[0].label", is("Étape 1 bloquée : le certificat n'est pas encore livré")))
                .andExpect(jsonPath("$.reasons[1].code", is("DEPENDENCY_BLOCKED")));
        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/journey").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.phase", is("EXECUTION")))
                .andExpect(jsonPath("$.plan.steps[0].status", is("BLOQUE")));

        JourneyToolExecutor.Outcome unblocked = tool(JourneyToolCatalog.UPDATE_STEP, "{\"step\":1,\"status\":\"A_FAIRE\"}");
        assertThat(unblocked.error()).isFalse();
        read(aliceToken, "chantiers/1/autonomy/preflight").andExpect(jsonPath("$.ready", is(true)));
    }

    @Test
    @DisplayName("SF-219-06 : plan existant sans acteur — prêt ; l'agent requalifie l'étape 2 en MOI sans amendement, "
            + "le préflight refuse STEP_ACTOR_MOI ; un acteur inconnu, ou ni état ni acteur, est refusé")
    void stepActor() throws Exception {
        executing(TWO_STEPS);
        read(aliceToken, "chantiers/1/autonomy/preflight").andExpect(jsonPath("$.ready", is(true)));
        SubjectJourney before = journeyRepository.findByUserIdAndWorkspaceId(aliceId, ws()).orElseThrow();
        int version = before.getPlanVersion();

        assertThat(tool(JourneyToolCatalog.UPDATE_STEP, "{\"step\":2,\"actor\":\"PERSONNE\"}").error()).isTrue();
        assertThat(tool(JourneyToolCatalog.UPDATE_STEP, "{\"step\":2}").content()).contains("status ou actor");
        JourneyToolExecutor.Outcome set = tool(JourneyToolCatalog.UPDATE_STEP, "{\"step\":2,\"actor\":\"MOI\"}");
        assertThat(set.error()).isFalse();
        assertThat(set.content()).contains("par vous").contains("pas un amendement");

        SubjectJourney after = journeyRepository.findByUserIdAndWorkspaceId(aliceId, ws()).orElseThrow();
        assertThat(after.getPlanVersion()).isEqualTo(version);
        assertThat(after.getValidatedVersion()).isEqualTo(version);
        assertThat(after.getPhase()).isEqualTo(JourneyPhase.EXECUTION);
        assertThat(JourneyPlan.fromJson(after.getValidatedPlanJson()).steps().get(1).actor())
                .isEqualTo(JourneyPlan.Actor.MOI);
        read(aliceToken, "chantiers/1/autonomy/preflight")
                .andExpect(jsonPath("$.ready", is(false)))
                .andExpect(jsonPath("$.reasons[0].code", is("STEP_ACTOR_MOI")))
                .andExpect(jsonPath("$.reasons[0].label", startsWith("Étape 2 : « Redémarrer l'ingress »")))
                .andExpect(jsonPath("$.advance.possible", is(true)))
                .andExpect(jsonPath("$.advance.toolSteps[0]", is(1)))
                .andExpect(jsonPath("$.advance.remaining[0].who", is("MOI")));
        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/journey").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.plan.steps[0].actor", is("OUTIL")))
                .andExpect(jsonPath("$.plan.steps[0].actorExplicit", is(false)))
                .andExpect(jsonPath("$.plan.steps[1].actor", is("MOI")))
                .andExpect(jsonPath("$.plan.steps[1].actorLabel", is("par vous")))
                .andExpect(jsonPath("$.plan.steps[1].unblocker.who", is("MOI")));

        // Statut et acteur ensemble : l'étape 1 passe FAIT, explicitement par l'outil.
        assertThat(tool(JourneyToolCatalog.UPDATE_STEP, "{\"step\":1,\"status\":\"FAIT\",\"actor\":\"OUTIL\"}")
                .error()).isFalse();
        JourneyPlan plan = JourneyPlan.fromJson(journeyRepository.findByUserIdAndWorkspaceId(aliceId, ws())
                .orElseThrow().getPlanJson());
        assertThat(plan.steps().get(0).actor()).isEqualTo(JourneyPlan.Actor.OUTIL);
        assertThat(plan.steps().get(0).status()).isEqualTo(JourneyPlan.StepStatus.FAIT);
    }

    @Test
    @DisplayName("une étape qui attend un input que personne ne suit : MISSING_INFO")
    void missingInfo() throws Exception {
        executing("[{\"title\":\"Obtenir le mot de passe\",\"risk\":\"LECTURE\",\"waits_on\":\"mdp-db\"}]");
        read(aliceToken, "chantiers/1/autonomy/preflight")
                .andExpect(jsonPath("$.ready", is(false)))
                .andExpect(jsonPath("$.reasons[0].code", is("MISSING_INFO")))
                .andExpect(jsonPath("$.reasons[0].step", is(1)));
    }

    @Test
    @DisplayName("une question en suspens sur le tour vivant : QUESTION_PENDING")
    void questionPending() throws Exception {
        executing(TWO_STEPS);
        openedTurn = liveTurns.open(aliceId, ws());
        openedTurn.publishQuestionRequest(java.util.Map.of("callId", "c1"),
                new PendingQuestion("c1", "[]", 60_000L, System.currentTimeMillis()));
        read(aliceToken, "chantiers/1/autonomy/preflight")
                .andExpect(jsonPath("$.ready", is(false)))
                .andExpect(jsonPath("$.reasons[0].code", is("QUESTION_PENDING")));
    }

    @Test
    @DisplayName("chantier non ouvert : 400 ; sujet jamais guidé : liste vide")
    void notOpen() throws Exception {
        read(aliceToken, "autonomy").andExpect(status().isOk()).andExpect(jsonPath("$.chantiers.length()", is(0)));
        executing(TWO_STEPS);
        read(aliceToken, "chantiers/7/autonomy/preflight")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", startsWith("Le chantier 7")));
    }

    @Test
    @DisplayName("isolation : Bob ne lit pas le préflight d'Alice (404)")
    void isolation() throws Exception {
        executing(TWO_STEPS);
        read(bobToken, "chantiers/1/autonomy/preflight").andExpect(status().isNotFound());
        read(bobToken, "autonomy").andExpect(status().isNotFound());
    }
}
