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
}
