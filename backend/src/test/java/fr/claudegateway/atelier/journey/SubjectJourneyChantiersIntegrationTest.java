package fr.claudegateway.atelier.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
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
 * F-206 / SF-206-01 — plusieurs chantiers OUVERTS dans un même sujet : ouvrir à côté, basculer, agir
 * sur un chantier par son numéro, clore — et Bob ne touche à rien.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SubjectJourneyChantiersIntegrationTest {

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

    private String aliceToken;
    private String bobToken;
    private String workspaceId;
    private UUID aliceId;

    @BeforeEach
    void setUp() throws Exception {
        eventRepository.deleteAll();
        chantierRepository.deleteAll();
        journeyRepository.deleteAll();
        workspaceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        aliceToken = tokenFor("alice-chantiers@ex.com");
        bobToken = tokenFor("bob-chantiers@ex.com");
        aliceId = userRepository.findByEmail("alice-chantiers@ex.com").orElseThrow().getId();
        workspaceId = createWorkspace(aliceToken);
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

    private ResultActions getJourney(String token) throws Exception {
        return mockMvc.perform(get("/api/workspaces/" + workspaceId + "/journey").contextPath("/api")
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions post(String token, String path, String body) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/workspaces/" + workspaceId + "/journey/" + path).contextPath("/api")
                .contentType(MediaType.APPLICATION_JSON).content(body == null ? "{}" : body)
                .header("Authorization", "Bearer " + token));
    }

    private static JourneyPlan plan(String title) {
        try {
            return JourneyPlan.fromToolInput(new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    "[{\"title\":\"" + title + "\",\"risk\":\"REVERSIBLE\"}]"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Chantier 1 guidé jusqu'à l'Exécution, plan validé. */
    private void chantierOneExecuting() throws Exception {
        mockMvc.perform(put("/api/workspaces/" + workspaceId + "/journey/mode").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"GUIDE\",\"title\":\"Ingress\"}")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
        service.submitDiagnosis(aliceId, ws(), "cert expiré", "openssl", "ELEVEE");
        service.confirmDiagnosis(aliceId, ws());
        service.setPlan(aliceId, ws(), plan("Remplacer le certificat"));
        service.validatePlan(aliceId, ws(), null);
    }

    @Test
    @DisplayName("ouvrir un chantier à côté ne clôt pas le premier ; le nouveau prend le focus")
    void openAlongside() throws Exception {
        chantierOneExecuting();
        post(aliceToken, "chantiers", "{\"title\":\"Migration DNS\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode", is("GUIDE")))
                .andExpect(jsonPath("$.phase", is("INVESTIGATION")))
                .andExpect(jsonPath("$.chantier.number", is(2)))
                .andExpect(jsonPath("$.chantiers.length()", is(2)))
                .andExpect(jsonPath("$.chantiers[0].number", is(1)))
                .andExpect(jsonPath("$.chantiers[0].focus", is(false)))
                .andExpect(jsonPath("$.chantiers[0].phase", is("EXECUTION")))
                .andExpect(jsonPath("$.chantiers[0].executing", is(true)))
                .andExpect(jsonPath("$.chantiers[0].total", is(1)))
                .andExpect(jsonPath("$.chantiers[1].number", is(2)))
                .andExpect(jsonPath("$.chantiers[1].focus", is(true)))
                .andExpect(jsonPath("$.chantiers[1].title", is("Migration DNS")))
                .andExpect(jsonPath("$.closedChantiers", is(0)));
        // La liste des CLOS n'expose aucun ouvert.
        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/journey/chantiers").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.length()", is(0)));
    }

    @Test
    @DisplayName("basculer recharge l'état du chantier visé ; le chantier quitté reste intact")
    void focusSwitch() throws Exception {
        chantierOneExecuting();
        post(aliceToken, "chantiers", "{\"title\":\"Migration DNS\"}");
        service.submitDiagnosis(aliceId, ws(), "zone mal déléguée", "dig", "MOYENNE");
        post(aliceToken, "chantiers/1/focus", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chantier.number", is(1)))
                .andExpect(jsonPath("$.phase", is("EXECUTION")))
                .andExpect(jsonPath("$.plan.steps[0].title", is("Remplacer le certificat")))
                .andExpect(jsonPath("$.chantiers[1].diagnosisPending", is(true)));
        post(aliceToken, "chantiers/2/focus", null)
                .andExpect(jsonPath("$.chantier.number", is(2)))
                .andExpect(jsonPath("$.phase", is("INVESTIGATION")))
                .andExpect(jsonPath("$.diagnosis.text", is("zone mal déléguée")))
                .andExpect(jsonPath("$.diagnosis.pending", is(true)));
        // Basculer sur le focus courant : sans effet.
        post(aliceToken, "chantiers/2/focus", null).andExpect(jsonPath("$.chantier.number", is(2)));
    }

    @Test
    @DisplayName("agir sur un chantier par son numéro ne change pas le focus")
    void actOnOtherChantier() throws Exception {
        chantierOneExecuting();
        post(aliceToken, "chantiers", "{\"title\":\"Migration DNS\"}");
        post(aliceToken, "chantiers/1/focus", null);
        // Le chantier 2 avance sans être au focus : diagnostic → plan → validation.
        service.submitDiagnosis(aliceId, ws(), 2, "zone mal déléguée", "dig", "MOYENNE");
        post(aliceToken, "chantiers/2/diagnosis/confirm", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chantier.number", is(1)))
                .andExpect(jsonPath("$.chantiers[1].phase", is("PLAN")));
        assertThat(service.setPlan(aliceId, ws(), 2, plan("Déléguer la zone")).outcome())
                .isEqualTo(SubjectJourneyService.PlanOutcome.SET);
        getJourney(aliceToken).andExpect(jsonPath("$.chantiers[1].awaitingValidation", is(true)));
        post(aliceToken, "chantiers/2/plan/validate", "{\"version\":1}")
                .andExpect(jsonPath("$.chantier.number", is(1)))
                .andExpect(jsonPath("$.phase", is("EXECUTION")))
                .andExpect(jsonPath("$.plan.steps[0].title", is("Remplacer le certificat")))
                .andExpect(jsonPath("$.chantiers[1].executing", is(true)));
        assertThat(service.updateStep(aliceId, ws(), 2, 1, "FAIT", null).outcome())
                .isEqualTo(SubjectJourneyService.StepOutcome.TO_VERIFICATION);
        getJourney(aliceToken).andExpect(jsonPath("$.chantiers[1].done", is(1)))
                .andExpect(jsonPath("$.chantiers[1].phase", is("VERIFICATION")))
                .andExpect(jsonPath("$.chantiers[0].phase", is("EXECUTION")));
    }

    @Test
    @DisplayName("clore le focus avec un autre ouvert : l'autre prend le focus, on reste guidé ; clore le dernier : Libre")
    void closeFocus() throws Exception {
        chantierOneExecuting();
        post(aliceToken, "chantiers", "{\"title\":\"Migration DNS\"}");
        post(aliceToken, "close", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode", is("GUIDE")))
                .andExpect(jsonPath("$.chantier.number", is(1)))
                .andExpect(jsonPath("$.phase", is("EXECUTION")))
                .andExpect(jsonPath("$.chantiers.length()", is(1)))
                .andExpect(jsonPath("$.closedChantiers", is(1)));
        post(aliceToken, "chantiers/1/close", null)
                .andExpect(jsonPath("$.mode", is("LIBRE")))
                .andExpect(jsonPath("$.phase", is("CLOS")))
                .andExpect(jsonPath("$.chantiers.length()", is(0)))
                .andExpect(jsonPath("$.closedChantiers", is(2)));
        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/journey/chantiers").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.length()", is(2)))
                .andExpect(jsonPath("$[0].number", is(2)))
                .andExpect(jsonPath("$[1].plan[0].title", is("Remplacer le certificat")));
        // Repasser en Guidé ouvre le chantier 3 (numérotation continue).
        mockMvc.perform(put("/api/workspaces/" + workspaceId + "/journey/mode").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"GUIDE\"}")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.chantier.number", is(3)));
    }

    @Test
    @DisplayName("clore un chantier qui n'est pas le focus ne touche pas au focus")
    void closeOther() throws Exception {
        chantierOneExecuting();
        post(aliceToken, "chantiers", "{\"title\":\"Migration DNS\"}");
        post(aliceToken, "chantiers/1/close", null)
                .andExpect(jsonPath("$.mode", is("GUIDE")))
                .andExpect(jsonPath("$.chantier.number", is(2)))
                .andExpect(jsonPath("$.phase", is("INVESTIGATION")))
                .andExpect(jsonPath("$.chantiers.length()", is(1)))
                .andExpect(jsonPath("$.closedChantiers", is(1)));
    }

    @Test
    @DisplayName("un chantier inconnu ou clos est refusé lisiblement ; ouvrir à côté exige le mode guidé")
    void errors() throws Exception {
        post(aliceToken, "chantiers", "{\"title\":\"x\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("journey_invalid")));
        chantierOneExecuting();
        post(aliceToken, "chantiers/7/focus", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Le chantier 7 n'est pas ouvert.")));
        post(aliceToken, "chantiers/7/plan/validate", "{}").andExpect(status().isBadRequest());
        post(aliceToken, "chantiers", "{}").andExpect(jsonPath("$.chantier.title", is("Chantier 2")));
        post(aliceToken, "chantiers/1/close", null);
        post(aliceToken, "chantiers/1/focus", null).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("ISOLATION : Bob n'atteint aucune route de chantier du terminal d'Alice")
    void isolation() throws Exception {
        chantierOneExecuting();
        post(aliceToken, "chantiers", "{\"title\":\"Migration DNS\"}");
        post(bobToken, "chantiers", "{\"title\":\"intrus\"}").andExpect(status().isNotFound());
        for (String path : new String[] {"chantiers/1/focus", "chantiers/1/plan/validate",
                "chantiers/1/diagnosis/confirm", "chantiers/1/diagnosis/dismiss", "chantiers/1/close",
                "chantiers/1/close/dismiss"}) {
            post(bobToken, path, null).andExpect(status().isNotFound());
        }
        getJourney(aliceToken).andExpect(jsonPath("$.chantiers.length()", is(2)))
                .andExpect(jsonPath("$.chantier.number", is(2)));
        assertThat(chantierRepository.findByUserIdAndWorkspaceIdAndStatusOrderByNumberAsc(aliceId, ws(),
                SubjectJourneyChantier.OPEN)).hasSize(2);
    }

    private JourneyToolExecutor.Outcome tool(String name, String json) throws Exception {
        fr.claudegateway.atelier.Workspace w = workspaceRepository.findById(ws()).orElseThrow();
        return journeyTools.execute(w.getUserId(), w, name,
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json));
    }

    @Test
    @DisplayName("SF-206-02 : outils avec chantier, proposition d'un chantier de plus, porte agrégée")
    void toolsAndAggregatedGate() throws Exception {
        chantierOneExecuting();
        // Sujet guidé : propose_guided_mode propose un chantier DE PLUS (carte), sans rien ouvrir.
        JourneyToolExecutor.Outcome proposed = tool(JourneyToolCatalog.PROPOSE_GUIDED,
                "{\"reason\":\"Migration DNS, distincte de l'incident\"}");
        assertThat(proposed.error()).isFalse();
        assertThat(proposed.content()).contains("[Ouvrir le chantier]");
        assertThat(tool(JourneyToolCatalog.PROPOSE_GUIDED, "{\"reason\":\"encore\"}").content())
                .contains("attend déjà");
        getJourney(aliceToken).andExpect(jsonPath("$.guidedProposal.newChantier", is(true)))
                .andExpect(jsonPath("$.chantiers.length()", is(1)));
        post(aliceToken, "guided-proposal/accept", null)
                .andExpect(jsonPath("$.chantier.number", is(2)))
                .andExpect(jsonPath("$.chantier.title", is("Migration DNS, distincte de l'incident")))
                .andExpect(jsonPath("$.chantiers.length()", is(2)))
                .andExpect(jsonPath("$.guidedProposal").doesNotExist())
                // Porte agrégée : le chantier 1 est en Exécution validé ⇒ ouverte, même si le focus investigue.
                .andExpect(jsonPath("$.gateClosed", is(false)));
        // Le focus 2 n'a pas de plan validé ; le chantier 1 avance par son numéro.
        assertThat(tool(JourneyToolCatalog.UPDATE_STEP, "{\"step\":1,\"status\":\"FAIT\"}").error()).isTrue();
        JourneyToolExecutor.Outcome step = tool(JourneyToolCatalog.UPDATE_STEP,
                "{\"chantier\":1,\"step\":1,\"status\":\"FAIT\"}");
        assertThat(step.error()).isFalse();
        assertThat(step.content()).startsWith("Chantier 1 : ").contains("VÉRIFICATION");
        // Plus aucun chantier en Exécution ⇒ porte fermée, message qui les nomme.
        getJourney(aliceToken).andExpect(jsonPath("$.gateClosed", is(true)))
                .andExpect(jsonPath("$.gateMessage", is("Ce terminal est en mode Guidé : aucun de ses chantiers "
                        + "ouverts n'est en Exécution sur un plan validé (Chantier 1 · Vérification, Chantier 2 · "
                        + "Investigation) : cette action attend la validation d'un plan.")));
        assertThat(tool(JourneyToolCatalog.SUBMIT_DIAGNOSIS,
                "{\"chantier\":9,\"diagnosis\":\"x\",\"evidence\":\"y\"}").content())
                .isEqualTo("Le chantier 9 n'est pas ouvert.");
        assertThat(tool(JourneyToolCatalog.SUBMIT_DIAGNOSIS,
                "{\"diagnosis\":\"zone\",\"evidence\":\"dig\",\"confidence\":\"ELEVEE\"}").error()).isFalse();
        assertThat(tool(JourneyToolCatalog.REOPEN, "{\"chantier\":1,\"reason\":\"502 persiste\"}").content())
                .startsWith("Chantier 1 : Retour en INVESTIGATION");
        getJourney(aliceToken).andExpect(jsonPath("$.chantiers[0].phase", is("INVESTIGATION")))
                .andExpect(jsonPath("$.chantiers[1].diagnosisPending", is(true)));
    }

    @Test
    @DisplayName("SF-206-02 : « Pas maintenant » sur un chantier de plus ne bloque pas les propositions suivantes")
    void declineAlongside() throws Exception {
        chantierOneExecuting();
        tool(JourneyToolCatalog.PROPOSE_GUIDED, "{\"reason\":\"DNS\"}");
        post(aliceToken, "guided-proposal/decline", null).andExpect(jsonPath("$.guidedDeclined", is(false)))
                .andExpect(jsonPath("$.mode", is("GUIDE")));
        assertThat(tool(JourneyToolCatalog.PROPOSE_GUIDED, "{\"reason\":\"DNS bis\"}").content())
                .contains("[Ouvrir le chantier]");
    }

    @Test
    @DisplayName("SF-208-01 : update_sub_steps détaille une étape (focus et chantier: N) ; 41 étapes refusées")
    void subStepsTool() throws Exception {
        chantierOneExecuting();
        JourneyToolExecutor.Outcome out = tool(JourneyToolCatalog.UPDATE_SUB_STEPS,
                "{\"step\":1,\"sub_steps\":[{\"title\":\"Générer la CSR\",\"done\":true,\"evidence\":\"csr ok\"},"
                        + "{\"title\":\"Installer\",\"done\":false}]}");
        assertThat(out.error()).isFalse();
        assertThat(out.content()).contains("1/2 sous-étape(s)");
        SubjectJourney focus = journeyRepository.findByUserIdAndWorkspaceId(aliceId, ws()).orElseThrow();
        assertThat(JourneyPlan.fromJson(focus.getPlanJson()).steps().get(0).inProgress()).isTrue();
        assertThat(JourneyPlan.fromJson(focus.getValidatedPlanJson()).steps().get(0).subSteps()).hasSize(2);
        // Idempotent : la même liste remplace la précédente.
        tool(JourneyToolCatalog.UPDATE_SUB_STEPS, "{\"step\":1,\"sub_steps\":[{\"title\":\"Tout\",\"done\":false}]}");
        focus = journeyRepository.findByUserIdAndWorkspaceId(aliceId, ws()).orElseThrow();
        assertThat(JourneyPlan.fromJson(focus.getPlanJson()).steps().get(0).subSteps()).hasSize(1);
        assertThat(tool(JourneyToolCatalog.UPDATE_SUB_STEPS, "{\"step\":4,\"sub_steps\":[]}").content())
                .contains("pas d'étape 4");
        // Un autre chantier ouvert, visé par son numéro ; le focus 2 n'exécute rien.
        post(aliceToken, "chantiers", "{\"title\":\"DNS\"}");
        assertThat(tool(JourneyToolCatalog.UPDATE_SUB_STEPS,
                "{\"step\":1,\"sub_steps\":[{\"title\":\"x\",\"done\":true}]}").error()).isTrue();
        assertThat(tool(JourneyToolCatalog.UPDATE_SUB_STEPS,
                "{\"chantier\":1,\"step\":1,\"sub_steps\":[{\"title\":\"x\",\"done\":true}]}").content())
                .startsWith("Chantier 1 : Étape 1 : 1/1");
        // Plus de 40 étapes : refus explicite, rien d'écrit.
        StringBuilder many = new StringBuilder("{\"chantier\":1,\"steps\":[");
        for (int i = 0; i < 41; i++) {
            many.append(i == 0 ? "" : ",").append("{\"title\":\"e").append(i).append("\",\"risk\":\"NOTES\"}");
        }
        JourneyToolExecutor.Outcome refused = tool(JourneyToolCatalog.SET_PLAN, many.append("]}").toString());
        assertThat(refused.error()).isTrue();
        assertThat(refused.content()).contains("41 étapes : 40 au plus");
        assertThat(chantierRepository.findFirstByUserIdAndWorkspaceIdAndNumber(aliceId, ws(), 1).orElseThrow()
                .getPlanVersion()).isEqualTo(1);
    }

    private static JourneyPlan planJson(String json) {
        try {
            return JourneyPlan.fromToolInput(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("SF-208-02 : chaque plan posé est une version ; la route dit ce qui a changé ; Bob reçoit 404")
    void planVersions() throws Exception {
        chantierOneExecuting(); // v1 : « Remplacer le certificat »
        service.setPlan(aliceId, ws(), planJson("[{\"title\":\"Remplacer le certificat\",\"risk\":\"EXTERNE\"},"
                + "{\"title\":\"Purger le cache\",\"risk\":\"REVERSIBLE\"}]"));
        service.setPlan(aliceId, ws(), planJson("[{\"title\":\"Purger le cache\",\"risk\":\"REVERSIBLE\"}]"));
        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/journey/chantiers/1/versions").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(3)))
                .andExpect(jsonPath("$[0].version", is(1)))
                .andExpect(jsonPath("$[0].added[0]", is("Remplacer le certificat")))
                .andExpect(jsonPath("$[1].version", is(2)))
                .andExpect(jsonPath("$[1].stepCount", is(2)))
                .andExpect(jsonPath("$[1].added[0]", is("Purger le cache")))
                .andExpect(jsonPath("$[1].modified[0]", is("Remplacer le certificat")))
                .andExpect(jsonPath("$[2].removed[0]", is("Remplacer le certificat")))
                .andExpect(jsonPath("$[2].added.length()", is(0)));
        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/journey/chantiers/1/versions").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/journey/chantiers/7/versions").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()", is(0)));
    }

    @Test
    @DisplayName("SF-208-02 : GET /journey détaille le plan de CHAQUE chantier ouvert, débloqué ou bloqué et par quoi")
    void allOpenChantiersDetailed() throws Exception {
        mockMvc.perform(put("/api/workspaces/" + workspaceId + "/journey/mode").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"GUIDE\",\"title\":\"Ingress\"}")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
        service.submitDiagnosis(aliceId, ws(), "cert expiré", "openssl", "ELEVEE");
        service.confirmDiagnosis(aliceId, ws());
        service.setPlan(aliceId, ws(), planJson("[{\"title\":\"Lire\",\"risk\":\"LECTURE\"},"
                + "{\"title\":\"Sauvegarder\",\"risk\":\"REVERSIBLE\"},"
                + "{\"title\":\"Basculer\",\"risk\":\"EXTERNE\",\"depends_on\":[1]}]"));
        service.validatePlan(aliceId, ws(), null);
        tool(JourneyToolCatalog.UPDATE_STEP, "{\"step\":1,\"status\":\"FAIT\"}");
        tool(JourneyToolCatalog.UPDATE_SUB_STEPS, "{\"step\":2,\"sub_steps\":[{\"title\":\"dump\",\"done\":true}]}");
        post(aliceToken, "chantiers", "{\"title\":\"DNS\"}");
        getJourney(aliceToken)
                .andExpect(jsonPath("$.chantier.number", is(2)))
                .andExpect(jsonPath("$.chantiers[0].focus", is(false)))
                .andExpect(jsonPath("$.chantiers[0].plan.steps.length()", is(3)))
                .andExpect(jsonPath("$.chantiers[0].plan.steps[0].status", is("FAIT")))
                .andExpect(jsonPath("$.chantiers[0].plan.steps[1].unlocked", is(true)))
                .andExpect(jsonPath("$.chantiers[0].plan.steps[1].inProgress", is(true)))
                .andExpect(jsonPath("$.chantiers[0].plan.steps[1].subSteps[0].title", is("dump")))
                // Dépendance déclarée faite : débloquée, même si l'étape 2 n'est pas finie.
                .andExpect(jsonPath("$.chantiers[0].plan.steps[2].dependsOn[0]", is(1)))
                .andExpect(jsonPath("$.chantiers[0].plan.steps[2].unlocked", is(true)))
                .andExpect(jsonPath("$.chantiers[1].focus", is(true)))
                .andExpect(jsonPath("$.chantiers[1].plan").doesNotExist());
        // Sans dépendance déclarée : l'étape précédente non faite bloque.
        service.setPlan(aliceId, ws(), 1, planJson("[{\"title\":\"Lire\",\"risk\":\"LECTURE\"},"
                + "{\"title\":\"Sauvegarder\",\"risk\":\"REVERSIBLE\"},"
                + "{\"title\":\"Basculer\",\"risk\":\"EXTERNE\",\"depends_on\":[2]},"
                + "{\"title\":\"Annoncer\",\"risk\":\"EXTERNE\"}]"));
        getJourney(aliceToken)
                .andExpect(jsonPath("$.chantiers[0].plan.awaitingValidation", is(true)))
                .andExpect(jsonPath("$.chantiers[0].plan.steps[2].unlocked", is(false)))
                .andExpect(jsonPath("$.chantiers[0].plan.steps[2].blockedBy[0]", is("étape 2")))
                .andExpect(jsonPath("$.chantiers[0].plan.steps[3].blockedBy[0]", is("étape 3")));
    }

    @Test
    @DisplayName("SF-221-01 : la vue du fil d'un chantier le met au focus sans toucher au focus persisté")
    void laneViewKeepsThePersistedFocus() throws Exception {
        chantierOneExecuting();
        post(aliceToken, "chantiers", "{\"title\":\"Migration DNS\"}"); // le chantier 2 prend le focus

        SubjectJourney lane = service.forLane(aliceId, ws(), 1);

        assertThat(lane.getChantierNumber()).isEqualTo(1);
        assertThat(lane.getPhase()).isEqualTo(JourneyPhase.EXECUTION);
        assertThat(lane.getOtherOpenChantiers()).extracting(SubjectJourneyChantier::getNumber).containsExactly(2);
        assertThat(service.forTurn(aliceId, ws()).getChantierNumber()).as("focus persisté inchangé").isEqualTo(2);
        assertThat(service.forLane(aliceId, ws(), 2).getChantierNumber()).isEqualTo(2);
        assertThat(service.forLane(aliceId, ws(), 9).getChantierNumber()).as("inconnu : le focus").isEqualTo(2);
        assertThat(service.chantierOpenedAt(aliceId, ws(), 2)).isPresent();
        assertThat(service.chantierOpenedAt(aliceId, ws(), 9)).isEmpty();

        // Dans un tour du fil 1, un outil du parcours sans numéro vise le chantier 1, pas le focus.
        fr.claudegateway.atelier.Workspace workspace = workspaceRepository.findById(ws()).orElseThrow();
        workspace.setTurnChantier(1);
        assertThat(JourneyToolExecutor.chantierOf(null, workspace)).isEqualTo(1);
        assertThat(JourneyToolExecutor.chantierOf(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree("{\"chantier\":2}"), workspace)).isEqualTo(2);
        workspace.setTurnChantier(null);
        assertThat(JourneyToolExecutor.chantierOf(null, workspace)).as("fil commun : le focus").isNull();
    }

    @Test
    @DisplayName("SF-221-03 : GET /journey?chantier=N rend la vue du fil N, le focus persisté intact ; Bob 404")
    void laneViewOverHttp() throws Exception {
        chantierOneExecuting();
        post(aliceToken, "chantiers", "{\"title\":\"Migration DNS\"}"); // focus = 2
        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/journey").param("chantier", "1").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chantier.number", is(1)))
                .andExpect(jsonPath("$.phase", is("EXECUTION")));
        getJourney(aliceToken).andExpect(jsonPath("$.chantier.number", is(2)));
        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/journey").param("chantier", "1").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }
}
