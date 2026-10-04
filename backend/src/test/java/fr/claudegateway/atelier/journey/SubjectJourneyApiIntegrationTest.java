package fr.claudegateway.atelier.journey;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * Le parcours d'un sujet, de bout en bout (F-176) — et <b>le terminal d'Alice est introuvable pour
 * Bob</b>.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SubjectJourneyApiIntegrationTest {

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
    private SubjectJourneyEventRepository eventRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JourneyToolExecutor journeyTools;

    private String aliceToken;
    private String bobToken;
    private String workspaceId;

    @BeforeEach
    void setUp() throws Exception {
        eventRepository.deleteAll();
        journeyRepository.deleteAll();
        workspaceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        aliceToken = tokenFor("alice-journey@ex.com");
        bobToken = tokenFor("bob-journey@ex.com");
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
                        .param("name", "Incident ingress")
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

    ResultActions getJourney(String token) throws Exception {
        return mockMvc.perform(get("/api/workspaces/" + workspaceId + "/journey").contextPath("/api")
                .header("Authorization", "Bearer " + token));
    }

    ResultActions putMode(String token, String mode) throws Exception {
        return mockMvc.perform(put("/api/workspaces/" + workspaceId + "/journey/mode").contextPath("/api")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"" + mode + "\"}")
                .header("Authorization", "Bearer " + token));
    }

    @Test
    @DisplayName("un sujet jamais décidé est Libre, sans phase — et rien n'est écrit en lisant")
    void defaultIsLibre() throws Exception {
        getJourney(aliceToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode", is("LIBRE")))
                .andExpect(jsonPath("$.phase", nullValue()));
        org.assertj.core.api.Assertions.assertThat(journeyRepository.count()).isZero();
    }

    @Test
    @DisplayName("passer en Guidé ouvre l'Investigation ; repasser en Libre garde la phase")
    void switchModes() throws Exception {
        putMode(aliceToken, "GUIDE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode", is("GUIDE")))
                .andExpect(jsonPath("$.phase", is("INVESTIGATION")))
                .andExpect(jsonPath("$.phaseLabel", is("Investigation")));
        putMode(aliceToken, "LIBRE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode", is("LIBRE")))
                .andExpect(jsonPath("$.phase", is("INVESTIGATION")));
        getJourney(aliceToken).andExpect(jsonPath("$.mode", is("LIBRE")));
        org.assertj.core.api.Assertions.assertThat(eventRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("un mode inconnu est refusé, lisiblement")
    void unknownMode() throws Exception {
        putMode(aliceToken, "TURBO")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("journey_invalid")));
    }

    @Test
    @DisplayName("ISOLATION : le terminal d'Alice est introuvable pour Bob, en lecture comme en écriture")
    void isolation() throws Exception {
        getJourney(bobToken).andExpect(status().isNotFound());
        putMode(bobToken, "GUIDE").andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(journeyRepository.count()).isZero();
    }

    private fr.claudegateway.atelier.Workspace aliceWorkspace() {
        return workspaceRepository.findById(java.util.UUID.fromString(workspaceId)).orElseThrow();
    }

    private JourneyToolExecutor.Outcome propose(String reason) {
        fr.claudegateway.atelier.Workspace ws = aliceWorkspace();
        com.fasterxml.jackson.databind.node.ObjectNode input =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        input.put("reason", reason);
        return journeyTools.execute(ws.getUserId(), ws, JourneyToolCatalog.PROPOSE_GUIDED, input);
    }

    ResultActions postJourney(String token, String path) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + workspaceId + "/journey/" + path).contextPath("/api")
                .header("Authorization", "Bearer " + token));
    }

    @Test
    @DisplayName("SF-176-02 : l'agent propose, la carte s'affiche, [Passer en guidé] ouvre l'Investigation")
    void proposeThenAccept() throws Exception {
        JourneyToolExecutor.Outcome first = propose("Incident d'ingress en prod, plusieurs inconnues.");
        org.assertj.core.api.Assertions.assertThat(first.error()).isFalse();
        org.assertj.core.api.Assertions.assertThat(first.content()).contains("[Passer en guidé]");
        org.assertj.core.api.Assertions.assertThat(propose("encore").content()).contains("attend déjà");

        getJourney(aliceToken)
                .andExpect(jsonPath("$.mode", is("LIBRE")))
                .andExpect(jsonPath("$.guidedProposal.reason", is("Incident d'ingress en prod, plusieurs inconnues.")))
                .andExpect(jsonPath("$.guidedDeclined", is(false)));

        postJourney(aliceToken, "guided-proposal/accept")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode", is("GUIDE")))
                .andExpect(jsonPath("$.phase", is("INVESTIGATION")))
                .andExpect(jsonPath("$.guidedProposal", nullValue()));
        org.assertj.core.api.Assertions.assertThat(propose("x").content()).contains("déjà en mode guidé");
    }

    @Test
    @DisplayName("SF-176-02 : [Rester libre] écarte la carte, et l'agent ne la repropose plus")
    void proposeThenDecline() throws Exception {
        propose("Changement d'infra sur trois clusters.");
        postJourney(aliceToken, "guided-proposal/decline")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode", is("LIBRE")))
                .andExpect(jsonPath("$.guidedProposal", nullValue()))
                .andExpect(jsonPath("$.guidedDeclined", is(true)));
        org.assertj.core.api.Assertions.assertThat(propose("encore").content()).contains("RESTER LIBRE");
    }

    @Test
    @DisplayName("SF-176-02 : une proposition sans raison est un résultat d'outil en erreur, pas une exception")
    void proposeWithoutReason() {
        org.assertj.core.api.Assertions.assertThat(propose("  ").error()).isTrue();
    }

    @Test
    @DisplayName("SF-176-02 ISOLATION : Bob ne peut ni accepter ni écarter la proposition d'Alice")
    void proposalIsolation() throws Exception {
        propose("Chantier.");
        postJourney(bobToken, "guided-proposal/accept").andExpect(status().isNotFound());
        postJourney(bobToken, "guided-proposal/decline").andExpect(status().isNotFound());
        getJourney(aliceToken).andExpect(jsonPath("$.guidedProposal.reason", is("Chantier.")));
    }
}
