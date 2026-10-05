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
    @Autowired
    private fr.claudegateway.atelier.actions.TerminalActionService terminalActions;
    @Autowired
    private fr.claudegateway.atelier.actions.TerminalActionRepository terminalActionRepository;

    private String aliceToken;
    private String bobToken;
    private String workspaceId;

    @BeforeEach
    void setUp() throws Exception {
        terminalActionRepository.deleteAll();
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

    private JourneyToolExecutor.Outcome setPlan(String stepsJson) throws Exception {
        fr.claudegateway.atelier.Workspace ws = aliceWorkspace();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode input = mapper.createObjectNode();
        input.set("steps", mapper.readTree(stepsJson));
        return journeyTools.execute(ws.getUserId(), ws, JourneyToolCatalog.SET_PLAN, input);
    }

    ResultActions validate(String token, String body) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + workspaceId + "/journey/plan/validate").contextPath("/api")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + token));
    }

    private static final String PLAN_V1 = """
            [{"title":"Lire les logs de l'ingress","risk":"LECTURE","verify":"erreur 502 identifiée"},
             {"title":"Obtenir le certificat de Gino","risk":"EXTERNE","waits_on":"certificat-gino"},
             {"title":"Remplacer le certificat","risk":"EXTERNE","verify":"curl 200","rollback":"remettre l'ancien secret"}]""";

    @Test
    @DisplayName("SF-176-03 : en Libre, pas de plan structuré (erreur d'outil, rien n'est écrit)")
    void planNeedsGuided() throws Exception {
        JourneyToolExecutor.Outcome outcome = setPlan(PLAN_V1);
        org.assertj.core.api.Assertions.assertThat(outcome.error()).isTrue();
        org.assertj.core.api.Assertions.assertThat(outcome.content()).contains("mode Libre");
        getJourney(aliceToken).andExpect(jsonPath("$.plan", nullValue()));
    }

    @Test
    @DisplayName("SF-176-03 : plan posé → Plan, en attente ; l'attente liée est résolue ; un clic valide → Exécution")
    void planThenValidate() throws Exception {
        guidedToPlan();
        fr.claudegateway.atelier.Workspace ws = aliceWorkspace();
        terminalActions.record(ws.getUserId(), ws.getId(), null, "Demander le certificat à Gino", null, "Gino",
                fr.claudegateway.atelier.actions.TerminalActionKind.MESSAGE, "certificat-gino");

        org.assertj.core.api.Assertions.assertThat(setPlan(PLAN_V1).content()).contains("Plan v1 posé");
        getJourney(aliceToken)
                .andExpect(jsonPath("$.phase", is("PLAN")))
                .andExpect(jsonPath("$.plan.version", is(1)))
                .andExpect(jsonPath("$.plan.awaitingValidation", is(true)))
                .andExpect(jsonPath("$.plan.amendment", is(false)))
                .andExpect(jsonPath("$.plan.waitingInputs", is(1)))
                .andExpect(jsonPath("$.plan.steps[1].waitsOn", is("certificat-gino")))
                .andExpect(jsonPath("$.plan.steps[1].waitsOnStatus", is("A_FAIRE")))
                .andExpect(jsonPath("$.plan.steps[2].riskLabel", is("externe / irréversible")));

        validate(aliceToken, "{\"version\":7}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("journey_invalid")));
        validate(aliceToken, "{\"version\":1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase", is("EXECUTION")))
                .andExpect(jsonPath("$.plan.validatedVersion", is(1)))
                .andExpect(jsonPath("$.plan.awaitingValidation", is(false)));
        // Plus rien à valider : la phase n'est plus « Plan ».
        validate(aliceToken, "{}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("SF-176-03 : un plan validé puis modifié est un amendement — étapes changées marquées, à revalider")
    void amendment() throws Exception {
        guidedToPlan();
        setPlan(PLAN_V1);
        validate(aliceToken, "{\"version\":1}").andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(setPlan("""
                [{"title":"Lire les logs de l'ingress","risk":"LECTURE","verify":"erreur 502 identifiée"},
                 {"title":"Redémarrer le contrôleur","risk":"EXTERNE"}]""").content()).contains("Amendement");
        getJourney(aliceToken)
                .andExpect(jsonPath("$.phase", is("PLAN")))
                .andExpect(jsonPath("$.plan.version", is(2)))
                .andExpect(jsonPath("$.plan.validatedVersion", is(1)))
                .andExpect(jsonPath("$.plan.amendment", is(true)))
                .andExpect(jsonPath("$.plan.awaitingValidation", is(true)))
                .andExpect(jsonPath("$.plan.steps[0].changed", is(false)))
                .andExpect(jsonPath("$.plan.steps[1].changed", is(true)));
        validate(aliceToken, "{\"version\":2}").andExpect(jsonPath("$.phase", is("EXECUTION")));
    }

    @Test
    @DisplayName("SF-176-03 ISOLATION : Bob ne peut pas valider le plan d'Alice")
    void validateIsolation() throws Exception {
        guidedToPlan();
        setPlan(PLAN_V1);
        validate(bobToken, "{}").andExpect(status().isNotFound());
        getJourney(aliceToken).andExpect(jsonPath("$.phase", is("PLAN")));
    }

    private JourneyToolExecutor.Outcome tool(String name, String json) throws Exception {
        fr.claudegateway.atelier.Workspace ws = aliceWorkspace();
        com.fasterxml.jackson.databind.JsonNode input = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        return journeyTools.execute(ws.getUserId(), ws, name, input);
    }

    /** Guidé, diagnostic posé et confirmé : le sujet est en Plan (SF-176-05). */
    private void guidedToPlan() throws Exception {
        putMode(aliceToken, "GUIDE");
        tool(JourneyToolCatalog.SUBMIT_DIAGNOSIS, """
                {"diagnosis":"Le certificat de l'ingress a expiré","evidence":"openssl : notAfter=2026-10-01",
                 "confidence":"ELEVEE"}""");
        postJourney(aliceToken, "diagnosis/confirm").andExpect(status().isOk());
    }

    @Test
    @DisplayName("SF-176-05 : en Investigation, pas de plan sans diagnostic ; [Planifier] ouvre le Plan")
    void diagnosisGatesThePlan() throws Exception {
        putMode(aliceToken, "GUIDE");
        org.assertj.core.api.Assertions.assertThat(setPlan(PLAN_V1).content()).contains("pose d'abord ton diagnostic");
        org.assertj.core.api.Assertions.assertThat(tool(JourneyToolCatalog.SUBMIT_DIAGNOSIS,
                "{\"diagnosis\":\"x\",\"evidence\":\"\"}").error()).isTrue();
        tool(JourneyToolCatalog.SUBMIT_DIAGNOSIS, """
                {"diagnosis":"Le certificat a expiré","evidence":"openssl notAfter","confidence":"élevée"}""");
        getJourney(aliceToken)
                .andExpect(jsonPath("$.phase", is("INVESTIGATION")))
                .andExpect(jsonPath("$.diagnosis.text", is("Le certificat a expiré")))
                .andExpect(jsonPath("$.diagnosis.confidence", is("ELEVEE")))
                .andExpect(jsonPath("$.diagnosis.pending", is(true)));
        postJourney(aliceToken, "diagnosis/dismiss").andExpect(jsonPath("$.diagnosis.pending", is(false)))
                .andExpect(jsonPath("$.phase", is("INVESTIGATION")));
        postJourney(aliceToken, "diagnosis/confirm").andExpect(status().isBadRequest());
        tool(JourneyToolCatalog.SUBMIT_DIAGNOSIS, """
                {"diagnosis":"Le certificat a expiré","evidence":"openssl notAfter","confidence":"ELEVEE"}""");
        postJourney(aliceToken, "diagnosis/confirm").andExpect(jsonPath("$.phase", is("PLAN")));
        org.assertj.core.api.Assertions.assertThat(setPlan(PLAN_V1).content()).contains("Plan v1 posé");
    }

    @Test
    @DisplayName("SF-176-05 : exécuter, vérifier avec preuve, puis la clôture est proposée et confirmée")
    void executeVerifyClose() throws Exception {
        guidedToPlan();
        setPlan("""
                [{"title":"Remplacer le certificat","risk":"EXTERNE","verify":"curl 200"},
                 {"title":"Noter la date d'expiration","risk":"NOTES"}]""");
        org.assertj.core.api.Assertions.assertThat(
                tool(JourneyToolCatalog.UPDATE_STEP, "{\"step\":1,\"status\":\"FAIT\"}").error()).isTrue();
        validate(aliceToken, "{\"version\":1}").andExpect(jsonPath("$.phase", is("EXECUTION")));

        tool(JourneyToolCatalog.UPDATE_STEP, "{\"step\":1,\"status\":\"FAIT\"}");
        org.assertj.core.api.Assertions.assertThat(
                tool(JourneyToolCatalog.UPDATE_STEP, "{\"step\":2,\"status\":\"FAIT\"}").content())
                .contains("VÉRIFICATION");
        getJourney(aliceToken).andExpect(jsonPath("$.phase", is("VERIFICATION")))
                .andExpect(jsonPath("$.plan.awaitingValidation", is(false)));
        org.assertj.core.api.Assertions.assertThat(
                tool(JourneyToolCatalog.UPDATE_STEP, "{\"step\":1,\"status\":\"VERIFIE\"}").error())
                .as("une vérification se prouve").isTrue();
        tool(JourneyToolCatalog.UPDATE_STEP, "{\"step\":1,\"status\":\"VERIFIE\",\"evidence\":\"curl → 200\"}");
        org.assertj.core.api.Assertions.assertThat(tool(JourneyToolCatalog.UPDATE_STEP,
                "{\"step\":2,\"status\":\"VERIFIE\",\"evidence\":\"STATE.md à jour\"}").content())
                .contains("clore");
        getJourney(aliceToken).andExpect(jsonPath("$.closeProposed", is(true)))
                .andExpect(jsonPath("$.plan.steps[0].evidence", is("curl → 200")))
                .andExpect(jsonPath("$.plan.steps[0].status", is("VERIFIE")));
        postJourney(aliceToken, "close").andExpect(jsonPath("$.phase", is("CLOS")))
                .andExpect(jsonPath("$.closeProposed", is(false)));
        // Le menu « Guidé » rouvre un sujet clos en Investigation.
        putMode(aliceToken, "GUIDE").andExpect(jsonPath("$.phase", is("INVESTIGATION")));
    }

    @Test
    @DisplayName("SF-176-05 : une vérification rouge → retour en Investigation, plan gardé, porte refermée")
    void reopen() throws Exception {
        guidedToPlan();
        setPlan("[{\"title\":\"Remplacer le certificat\",\"risk\":\"EXTERNE\"}]");
        validate(aliceToken, "{}");
        tool(JourneyToolCatalog.UPDATE_STEP, "{\"step\":1,\"status\":\"FAIT\"}");
        org.assertj.core.api.Assertions.assertThat(tool(JourneyToolCatalog.UPDATE_STEP,
                "{\"step\":1,\"status\":\"ECHEC\",\"evidence\":\"curl → 502\"}").content()).contains("ÉCHEC");
        org.assertj.core.api.Assertions.assertThat(tool(JourneyToolCatalog.REOPEN, "{\"reason\":\"\"}").error()).isTrue();
        org.assertj.core.api.Assertions.assertThat(
                tool(JourneyToolCatalog.REOPEN, "{\"reason\":\"le 502 vient du backend\"}").content())
                .contains("Retour en INVESTIGATION");
        getJourney(aliceToken).andExpect(jsonPath("$.phase", is("INVESTIGATION")))
                .andExpect(jsonPath("$.plan.version", is(1)));
        org.assertj.core.api.Assertions.assertThat(
                eventRepository.countByUserIdAndWorkspaceIdAndType(aliceWorkspace().getUserId(),
                        aliceWorkspace().getId(), SubjectJourneyEvent.REOPENED)).isEqualTo(1);
    }

    @Test
    @DisplayName("SF-176-05 ISOLATION : Bob ne confirme ni le diagnostic ni la clôture d'Alice")
    void transitionsIsolation() throws Exception {
        putMode(aliceToken, "GUIDE");
        tool(JourneyToolCatalog.SUBMIT_DIAGNOSIS, "{\"diagnosis\":\"x\",\"evidence\":\"y\"}");
        postJourney(bobToken, "diagnosis/confirm").andExpect(status().isNotFound());
        postJourney(bobToken, "diagnosis/dismiss").andExpect(status().isNotFound());
        postJourney(bobToken, "close").andExpect(status().isNotFound());
        postJourney(bobToken, "close/dismiss").andExpect(status().isNotFound());
        getJourney(aliceToken).andExpect(jsonPath("$.diagnosis.pending", is(true)));
    }
}
