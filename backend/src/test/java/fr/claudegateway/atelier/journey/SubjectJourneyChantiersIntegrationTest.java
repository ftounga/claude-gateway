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
    @DisplayName("SF-176-12 : la confirmation écrite du diagnostic d'un chantier hors focus le passe en Plan")
    void confirmDiagnosisTool() throws Exception {
        chantierOneExecuting();
        post(aliceToken, "chantiers", "{\"title\":\"Logs vers Loki\"}");
        post(aliceToken, "chantiers/1/focus", null);
        service.submitDiagnosis(aliceId, ws(), 2, "pas de collecteur", "kubectl", "ELEVEE");
        // Sans numéro ni citation : refusé, rien ne bouge.
        assertThat(tool(JourneyToolCatalog.CONFIRM_DIAGNOSIS, "{\"user_words\":\"go\"}").error()).isTrue();
        assertThat(tool(JourneyToolCatalog.CONFIRM_DIAGNOSIS, "{\"chantier\":2}").error()).isTrue();
        getJourney(aliceToken).andExpect(jsonPath("$.chantiers[1].diagnosisPending", is(true)));
        JourneyToolExecutor.Outcome ok = tool(JourneyToolCatalog.CONFIRM_DIAGNOSIS,
                "{\"chantier\":2,\"user_words\":\"go pour la planification\"}");
        assertThat(ok.error()).isFalse();
        assertThat(ok.content()).startsWith("Chantier 2 : Diagnostic confirmé");
        getJourney(aliceToken).andExpect(jsonPath("$.chantiers[1].phase", is("PLAN")))
                .andExpect(jsonPath("$.chantiers[1].diagnosisPending", is(false)))
                .andExpect(jsonPath("$.chantier.number", is(1)));
        // Plus rien à confirmer : erreur lisible, pas d'exception.
        assertThat(tool(JourneyToolCatalog.CONFIRM_DIAGNOSIS,
                "{\"chantier\":2,\"user_words\":\"go\"}").content()).contains("Aucun diagnostic");
        assertThat(service.setPlan(aliceId, ws(), 2, plan("Installer Fluent Bit")).outcome())
                .isEqualTo(SubjectJourneyService.PlanOutcome.SET);
    }

    @Test
    @DisplayName("SF-222-01 : un amendement dans l'objectif s'applique sans clic, un changement d'objectif se revalide")
    void amendmentInScopeAppliesWithoutClick() throws Exception {
        chantierOneExecuting();
        JourneyToolExecutor.Outcome inScope = tool(JourneyToolCatalog.SET_PLAN, "{\"same_objective\":true,\"steps\":["
                + "{\"title\":\"Remplacer le certificat\",\"risk\":\"REVERSIBLE\",\"actor\":\"OUTIL\"},"
                + "{\"title\":\"Recharger l'ingress\",\"risk\":\"REVERSIBLE\",\"actor\":\"OUTIL\"}]}");
        assertThat(inScope.error()).isFalse();
        assertThat(inScope.content()).contains("SANS CLIC").contains("Annonce-le");
        getJourney(aliceToken).andExpect(jsonPath("$.phase", is("EXECUTION")))
                .andExpect(jsonPath("$.plan.version", is(2)))
                .andExpect(jsonPath("$.plan.validatedVersion", is(2)))
                .andExpect(jsonPath("$.plan.awaitingValidation", is(false)));
        assertThat(eventRepository.countByUserIdAndWorkspaceIdAndType(aliceId, ws(),
                SubjectJourneyEvent.PLAN_AMENDED_IN_SCOPE)).isEqualTo(1);
        // Changement d'objectif : revalidation, la porte se referme.
        JourneyToolExecutor.Outcome change = tool(JourneyToolCatalog.SET_PLAN,
                "{\"steps\":[{\"title\":\"Migrer vers cert-manager\",\"risk\":\"EXTERNE\",\"actor\":\"OUTIL\"}]}");
        assertThat(change.content()).contains("REVALIDÉ");
        getJourney(aliceToken).andExpect(jsonPath("$.phase", is("PLAN")))
                .andExpect(jsonPath("$.plan.awaitingValidation", is(true)));
        // Un amendement « même objectif » posé sur un amendement EN ATTENTE ne le valide pas en douce.
        assertThat(service.setPlan(aliceId, ws(), null, plan("Autre chose"), true).outcome())
                .isEqualTo(SubjectJourneyService.PlanOutcome.AMENDMENT);
        getJourney(aliceToken).andExpect(jsonPath("$.phase", is("PLAN")));
    }

    @Test
    @DisplayName("SF-222-01 : le premier plan garde le clic, même annoncé « même objectif »")
    void firstPlanKeepsTheClick() throws Exception {
        mockMvc.perform(put("/api/workspaces/" + workspaceId + "/journey/mode").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"GUIDE\",\"title\":\"Ingress\"}")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
        service.submitDiagnosis(aliceId, ws(), "cert expiré", "openssl", "ELEVEE");
        service.confirmDiagnosis(aliceId, ws());
        assertThat(service.setPlan(aliceId, ws(), null, plan("Remplacer"), true).outcome())
                .isEqualTo(SubjectJourneyService.PlanOutcome.SET);
        getJourney(aliceToken).andExpect(jsonPath("$.phase", is("PLAN")))
                .andExpect(jsonPath("$.plan.awaitingValidation", is(true)));
    }

    @Test
    @DisplayName("SF-222-01/04 : un « go » écrit valide le plan lu (version), sur des mots écrits, jamais en autonome")
    void validatePlanTool() throws Exception {
        chantierOneExecuting();
        post(aliceToken, "chantiers", "{\"title\":\"Logs vers Loki\"}");
        service.submitDiagnosis(aliceId, ws(), 2, "pas de collecteur", "kubectl", "ELEVEE");
        service.confirmDiagnosis(aliceId, ws(), 2);
        service.setPlan(aliceId, ws(), 2, plan("Installer Fluent Bit"));
        post(aliceToken, "chantiers/1/focus", null);
        JourneyToolExecutor.TurnContext said = JourneyToolExecutor.TurnContext.ofUser(
                java.util.List.of("Ok, GO pour le chantier 2 !"));
        // Sans numéro, sans version, sans citation : refusé, rien ne bouge.
        assertThat(tool(JourneyToolCatalog.VALIDATE_PLAN, "{\"version\":1,\"user_words\":\"go\"}", said).error())
                .isTrue();
        assertThat(tool(JourneyToolCatalog.VALIDATE_PLAN, "{\"chantier\":2,\"user_words\":\"go\"}", said).content())
                .contains("version est requise");
        assertThat(tool(JourneyToolCatalog.VALIDATE_PLAN, "{\"chantier\":2,\"version\":1}", said).error()).isTrue();
        // Citation inventée : refusée.
        assertThat(tool(JourneyToolCatalog.VALIDATE_PLAN,
                "{\"chantier\":2,\"version\":1,\"user_words\":\"je valide\"}", said).content())
                .contains("ne figurent pas");
        // Course autonome / routine (ou contexte inconnu) : refusée même avec de vrais mots.
        assertThat(tool(JourneyToolCatalog.VALIDATE_PLAN,
                "{\"chantier\":2,\"version\":1,\"user_words\":\"go\"}").content()).contains("course autonome");
        // Version dépassée : refusée.
        assertThat(tool(JourneyToolCatalog.VALIDATE_PLAN,
                "{\"chantier\":2,\"version\":3,\"user_words\":\"go\"}", said).content()).contains("Le plan a changé");
        getJourney(aliceToken).andExpect(jsonPath("$.chantiers[1].phase", is("PLAN")));
        JourneyToolExecutor.Outcome ok = tool(JourneyToolCatalog.VALIDATE_PLAN,
                "{\"chantier\":2,\"version\":\"v1\",\"user_words\":\"« go pour le chantier 2 »\"}", said);
        assertThat(ok.error()).isFalse();
        assertThat(ok.content()).startsWith("Chantier 2 : Plan v1 validé");
        getJourney(aliceToken).andExpect(jsonPath("$.chantiers[1].phase", is("EXECUTION")))
                .andExpect(jsonPath("$.chantier.number", is(1)));
        // Le journal garde le canal : le clic du chantier 1, l'écrit du chantier 2.
        java.util.List<String> details = eventRepository.findAll().stream()
                .filter(e -> SubjectJourneyEvent.PLAN_VALIDATED.equals(e.getType()))
                .map(SubjectJourneyEvent::getDetail).toList();
        assertThat(details).anyMatch(d -> d.startsWith("chantier 1 · v1") && d.endsWith("· clic"))
                .anyMatch(d -> d.startsWith("chantier 2 · v1") && d.contains("écrit « « go pour le chantier 2 » »"));
        // Plus rien à valider : erreur lisible, pas d'exception.
        assertThat(tool(JourneyToolCatalog.VALIDATE_PLAN, "{\"chantier\":2,\"version\":1,\"user_words\":\"go\"}",
                said).error()).isTrue();
    }

    @Test
    @DisplayName("SF-261-01 : « tu peux clore » écrit clôt un chantier vérifié, sur des mots écrits, jamais en autonome")
    void closeChantierTool() throws Exception {
        chantierOneExecuting();
        JourneyToolExecutor.TurnContext said = JourneyToolExecutor.TurnContext.ofUser(
                java.util.List.of("Il a validé tu peux cloturer et fermer"));
        // En Exécution : refusé, même avec de vrais mots — un écrit ne clôt pas un chantier inachevé.
        assertThat(tool(JourneyToolCatalog.CLOSE_CHANTIER,
                "{\"chantier\":1,\"user_words\":\"tu peux cloturer\"}", said).content())
                .contains("n'est pas prêt à clore");
        tool(JourneyToolCatalog.UPDATE_STEP, "{\"chantier\":1,\"step\":1,\"status\":\"FAIT\"}");
        // En Vérification, étape pas encore vérifiée : refusé.
        assertThat(tool(JourneyToolCatalog.CLOSE_CHANTIER,
                "{\"chantier\":1,\"user_words\":\"tu peux cloturer\"}", said).content())
                .contains("TOUTES les étapes sont vérifiées");
        tool(JourneyToolCatalog.UPDATE_STEP,
                "{\"chantier\":1,\"step\":1,\"status\":\"VERIFIE\",\"evidence\":\"curl 200\"}");
        // Sans numéro, sans citation, citation inventée, tour sans utilisateur : refusé, rien ne bouge.
        assertThat(tool(JourneyToolCatalog.CLOSE_CHANTIER, "{\"user_words\":\"tu peux cloturer\"}", said).content())
                .contains("chantier est requis");
        assertThat(tool(JourneyToolCatalog.CLOSE_CHANTIER, "{\"chantier\":1}", said).content())
                .contains("user_words est requis");
        assertThat(tool(JourneyToolCatalog.CLOSE_CHANTIER,
                "{\"chantier\":1,\"user_words\":\"clos le chantier\"}", said).content()).contains("ne figurent pas");
        assertThat(tool(JourneyToolCatalog.CLOSE_CHANTIER,
                "{\"chantier\":1,\"user_words\":\"tu peux cloturer\"}").content()).contains("course autonome");
        assertThat(tool(JourneyToolCatalog.CLOSE_CHANTIER,
                "{\"chantier\":9,\"user_words\":\"tu peux cloturer\"}", said).content())
                .isEqualTo("Le chantier 9 n'est pas ouvert.");
        getJourney(aliceToken).andExpect(jsonPath("$.phase", is("VERIFICATION")));
        // Le bon geste : clos, comme le bouton ; dernier chantier ⇒ Libre.
        JourneyToolExecutor.Outcome ok = tool(JourneyToolCatalog.CLOSE_CHANTIER,
                "{\"chantier\":\"1\",\"user_words\":\"Tu peux clôturer\"}", said);
        assertThat(ok.error()).isFalse();
        assertThat(ok.content()).startsWith("Chantier 1 : Clos sur la demande").contains("revient en Libre");
        getJourney(aliceToken).andExpect(jsonPath("$.mode", is("LIBRE"))).andExpect(jsonPath("$.phase", is("CLOS")))
                .andExpect(jsonPath("$.closedChantiers", is(1)));
        // Le journal garde le canal écrit.
        assertThat(eventRepository.findAll().stream()
                .filter(e -> SubjectJourneyEvent.CLOSED.equals(e.getType()))
                .map(SubjectJourneyEvent::getDetail).toList())
                .containsExactly("chantier 1 · écrit « Tu peux clôturer »");
        // Plus rien à clore : erreur lisible, pas d'exception.
        assertThat(tool(JourneyToolCatalog.CLOSE_CHANTIER,
                "{\"chantier\":1,\"user_words\":\"tu peux cloturer\"}", said).error()).isTrue();
    }

    @Test
    @DisplayName("SF-261-01 : clore par écrit un chantier parmi plusieurs garde les autres ouverts ; le bouton journalise sans canal")
    void closeChantierToolAmongOthers() throws Exception {
        chantierOneExecuting();
        tool(JourneyToolCatalog.UPDATE_STEP, "{\"chantier\":1,\"step\":1,\"status\":\"FAIT\"}");
        tool(JourneyToolCatalog.UPDATE_STEP,
                "{\"chantier\":1,\"step\":1,\"status\":\"VERIFIE\",\"evidence\":\"curl 200\"}");
        post(aliceToken, "chantiers", "{\"title\":\"Migration DNS\"}");
        JourneyToolExecutor.Outcome ok = tool(JourneyToolCatalog.CLOSE_CHANTIER,
                "{\"chantier\":1,\"user_words\":\"go pour clore le 1\"}",
                JourneyToolExecutor.TurnContext.ofUser(java.util.List.of("ok, go pour clore le 1")));
        assertThat(ok.error()).isFalse();
        assertThat(ok.content()).contains("Le chantier 2 est maintenant au focus");
        getJourney(aliceToken).andExpect(jsonPath("$.mode", is("GUIDE")))
                .andExpect(jsonPath("$.chantier.number", is(2)))
                .andExpect(jsonPath("$.chantiers.length()", is(1)));
        post(aliceToken, "chantiers/2/close", null).andExpect(status().isOk());
        assertThat(eventRepository.findAll().stream()
                .filter(e -> SubjectJourneyEvent.CLOSED.equals(e.getType()))
                .map(SubjectJourneyEvent::getDetail).toList())
                .containsExactlyInAnyOrder("chantier 1 · écrit « go pour clore le 1 »", null);
    }

    @Test
    @DisplayName("SF-274-01 : le couloir hors chantier — citation vérifiée, un événement par chantier rattaché")
    void outOfPlanCorridor() throws Exception {
        JourneyToolExecutor.TurnContext said = JourneyToolExecutor.TurnContext.ofUser(
                java.util.List.of("Régénère la page État des lieux (vague 1, vague 2, secrets) et republie-la"));
        // En Libre : le couloir n'existe pas.
        assertThat(tool(JourneyToolCatalog.OUT_OF_PLAN,
                "{\"user_words\":\"Régénère la page\",\"objet\":\"page\"}", said).content())
                .contains("n'existe qu'en mode guidé");
        chantierOneExecuting();
        post(aliceToken, "chantiers", "{\"title\":\"Migration DNS\"}");
        // Objet ou citation absents, citation inventée, tour sans utilisateur : refusé, rien journalisé.
        assertThat(tool(JourneyToolCatalog.OUT_OF_PLAN, "{\"user_words\":\"Régénère la page\"}", said).content())
                .contains("objet est requis");
        assertThat(tool(JourneyToolCatalog.OUT_OF_PLAN, "{\"objet\":\"page\"}", said).content())
                .contains("user_words est requis");
        assertThat(tool(JourneyToolCatalog.OUT_OF_PLAN,
                "{\"user_words\":\"fais tout\",\"objet\":\"page\"}", said).content()).contains("ne figurent pas");
        assertThat(tool(JourneyToolCatalog.OUT_OF_PLAN,
                "{\"user_words\":\"Régénère la page\",\"objet\":\"page\"}").content()).contains("course autonome");
        assertThat(eventRepository.findAll()).noneMatch(e -> SubjectJourneyEvent.OUT_OF_PLAN.equals(e.getType()));
        // Transverse : un événement par chantier ouvert cité ; un chantier inconnu est signalé.
        JourneyToolExecutor.Outcome ok = tool(JourneyToolCatalog.OUT_OF_PLAN,
                "{\"user_words\":\"Régénère la page État des lieux\",\"objet\":\"page État des lieux v7\","
                        + "\"chantiers\":[2,1,7]}", said);
        assertThat(ok.error()).isFalse();
        assertThat(ok.content()).contains("transverse, rattaché aux chantiers 1, 2")
                .contains("Chantier(s) 7 ignoré(s)");
        // Hors de tout chantier.
        assertThat(tool(JourneyToolCatalog.OUT_OF_PLAN,
                "{\"user_words\":\"republie-la\",\"objet\":\"republier\"}", said).content())
                .contains("hors de tout chantier");
        assertThat(eventRepository.findAll().stream()
                .filter(e -> SubjectJourneyEvent.OUT_OF_PLAN.equals(e.getType()))
                .map(SubjectJourneyEvent::getDetail).toList())
                .containsExactlyInAnyOrder(
                        "chantier 1 · page État des lieux v7 · écrit « Régénère la page État des lieux »",
                        "chantier 2 · page État des lieux v7 · écrit « Régénère la page État des lieux »",
                        "aucun chantier · republier · écrit « republie-la »");
    }

    @Test
    @DisplayName("SF-274-02 : rattaché à chacun — rangé par chantier, servi à l'écran, Bob ne voit rien")
    void outOfPlanAttachedToEach() throws Exception {
        chantierOneExecuting();
        post(aliceToken, "chantiers", "{\"title\":\"Migration DNS\"}");
        JourneyToolExecutor.TurnContext said = JourneyToolExecutor.TurnContext.ofUser(
                java.util.List.of("Régénère la page État des lieux puis clôture SIP-10567"));
        tool(JourneyToolCatalog.OUT_OF_PLAN, "{\"user_words\":\"Régénère la page État des lieux\","
                + "\"objet\":\"page État des lieux v7\",\"chantiers\":[1,2]}", said);
        tool(JourneyToolCatalog.OUT_OF_PLAN,
                "{\"user_words\":\"clôture SIP-10567\",\"objet\":\"clôture SIP-10567\"}", said);
        java.util.Map<Integer, java.util.List<SubjectJourneyService.OutOfPlanGesture>> byChantier =
                service.outOfPlanByChantier(aliceId, ws());
        assertThat(byChantier.keySet()).containsExactly(0, 1, 2);
        assertThat(byChantier.get(1)).extracting(SubjectJourneyService.OutOfPlanGesture::objet)
                .containsExactly("page État des lieux v7");
        assertThat(byChantier.get(0)).extracting(SubjectJourneyService.OutOfPlanGesture::objet)
                .containsExactly("clôture SIP-10567");
        getJourney(aliceToken)
                .andExpect(jsonPath("$.chantiers[0].horsPlan[0].objet", is("page État des lieux v7")))
                .andExpect(jsonPath("$.chantiers[1].horsPlan[0].objet", is("page État des lieux v7")));
        assertThat(service.outOfPlanByChantier(userRepository.findByEmail("bob-chantiers@ex.com").orElseThrow()
                .getId(), ws())).isEmpty();
        getJourney(bobToken).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("SF-274-02 : lire_plan_chantier rend le plan complet d'un autre chantier, au format de set_subject_plan")
    void readPlanOfAnotherChantier() throws Exception {
        chantierOneExecuting();
        post(aliceToken, "chantiers", "{\"title\":\"Migration DNS\"}");
        assertThat(tool(JourneyToolCatalog.READ_PLAN, "{}").content()).contains("chantier est requis");
        assertThat(tool(JourneyToolCatalog.READ_PLAN, "{\"chantier\":9}").content())
                .isEqualTo("Le chantier 9 n'est pas ouvert.");
        assertThat(tool(JourneyToolCatalog.READ_PLAN, "{\"chantier\":2}").content()).contains("pas encore de plan");
        JourneyToolExecutor.Outcome read = tool(JourneyToolCatalog.READ_PLAN, "{\"chantier\":1}");
        assertThat(read.error()).isFalse();
        assertThat(read.content()).startsWith("Chantier 1 — Ingress · phase Exécution · plan v1 validé · 1 étapes")
                .contains("\"title\":\"Remplacer le certificat\"").contains("\"risk\":\"REVERSIBLE\"");
        // Le focus n'a pas bougé : lire n'est pas basculer.
        getJourney(aliceToken).andExpect(jsonPath("$.chantier.number", is(2)));
    }

    @Test
    @DisplayName("SF-222-04 : la citation est cherchée mot pour mot, casse, accents et ponctuation ignorés")
    void quotedWords() {
        java.util.List<String> said = java.util.List.of("Le plan me va. Validé, tu peux y aller !");
        assertThat(JourneyToolExecutor.quoted("valide tu peux", said)).isTrue();
        assertThat(JourneyToolExecutor.quoted("« VALIDÉ »", said)).isTrue();
        assertThat(JourneyToolExecutor.quoted("go", said)).isFalse();
        assertThat(JourneyToolExecutor.quoted("val", said)).isFalse(); // un morceau de mot ne vaut pas
        assertThat(JourneyToolExecutor.quoted("« »", said)).isFalse();
        assertThat(JourneyToolExecutor.quoted("go", java.util.List.of())).isFalse();
    }

    @Test
    @DisplayName("SF-222-04 : un amendement « même objectif » qui ajoute une étape EXTERNE se revalide ; l'origine se voit")
    void inScopeAmendmentRefusesNewExternalStep() throws Exception {
        chantierOneExecuting(); // v1 : « Remplacer le certificat » (REVERSIBLE)
        JourneyToolExecutor.Outcome external = tool(JourneyToolCatalog.SET_PLAN, "{\"same_objective\":true,"
                + "\"steps\":[{\"title\":\"Remplacer le certificat\",\"risk\":\"REVERSIBLE\",\"actor\":\"OUTIL\"},"
                + "{\"title\":\"Pousser en prod\",\"risk\":\"EXTERNE\",\"actor\":\"OUTIL\"}]}");
        assertThat(external.content()).contains("étape externe").contains("REVALIDÉ");
        getJourney(aliceToken).andExpect(jsonPath("$.phase", is("PLAN")))
                .andExpect(jsonPath("$.plan.awaitingValidation", is(true)));
        assertThat(eventRepository.countByUserIdAndWorkspaceIdAndType(aliceId, ws(),
                SubjectJourneyEvent.PLAN_AMENDED_IN_SCOPE)).isZero();
        // Validé d'un clic, l'étape externe est couverte : un ajout réversible ensuite passe sans clic.
        service.validatePlan(aliceId, ws(), 2);
        assertThat(service.setPlan(aliceId, ws(), null, planJson("[{\"title\":\"Remplacer le certificat\","
                + "\"risk\":\"REVERSIBLE\"},{\"title\":\"Pousser en prod\",\"risk\":\"EXTERNE\",\"actor\":\"OUTIL\"},"
                + "{\"title\":\"Purger le cache\",\"risk\":\"REVERSIBLE\"}]"), true).outcome())
                .isEqualTo(SubjectJourneyService.PlanOutcome.AMENDMENT_APPLIED);
        // L'étape externe MODIFIÉE (même titre, autre retour arrière) n'entre pas sans clic.
        assertThat(service.setPlan(aliceId, ws(), null, planJson("[{\"title\":\"Remplacer le certificat\","
                + "\"risk\":\"REVERSIBLE\"},{\"title\":\"Pousser en prod\",\"risk\":\"EXTERNE\",\"actor\":\"OUTIL\","
                + "\"rollback\":\"aucun\"},"
                + "{\"title\":\"Purger le cache\",\"risk\":\"REVERSIBLE\"}]"), true).outcome())
                .isEqualTo(SubjectJourneyService.PlanOutcome.AMENDMENT_EXTERNAL);
        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/journey/chantiers/1/versions").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].origin", is("PLAN")))
                .andExpect(jsonPath("$[1].origin", is("AMENDMENT")))
                .andExpect(jsonPath("$[2].origin", is("IN_SCOPE")))
                .andExpect(jsonPath("$[3].origin", is("AMENDMENT")));
        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/journey/chantiers/1/versions").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("SF-222-03 : un chantier en Exécution sans geste depuis 3 jours est signalé, daté, jamais clos seul")
    void staleChantierIsFlagged() throws Exception {
        chantierOneExecuting();
        post(aliceToken, "chantiers", "{\"title\":\"Logs vers Loki\"}");
        getJourney(aliceToken).andExpect(jsonPath("$.chantiers[0].stale", is(false)))
                .andExpect(jsonPath("$.chantiers[0].lastActivityAt").exists());
        SubjectJourneyChantier one = chantierRepository.findFirstByUserIdAndWorkspaceIdAndNumber(aliceId, ws(), 1)
                .orElseThrow();
        one.setUpdatedAt(java.time.OffsetDateTime.now().minusDays(4));
        chantierRepository.save(one);
        getJourney(aliceToken).andExpect(jsonPath("$.chantiers[0].number", is(1)))
                .andExpect(jsonPath("$.chantiers[0].stale", is(true)))
                .andExpect(jsonPath("$.chantiers[0].phase", is("EXECUTION")))
                .andExpect(jsonPath("$.chantiers[1].stale", is(false)));
        assertThat(one.isStale(java.time.OffsetDateTime.now())).isTrue();
        // SF-222-04 : un chantier qui attend un tiers attend ; il n'est jamais « en sommeil ».
        one = chantierRepository.findFirstByUserIdAndWorkspaceIdAndNumber(aliceId, ws(), 1).orElseThrow();
        one.setPlanJson(planJson("[{\"title\":\"Le client signe le bon\",\"risk\":\"REVERSIBLE\",\"actor\":\"TIERS\"}]")
                .toJson());
        one.setUpdatedAt(java.time.OffsetDateTime.now().minusDays(4));
        chantierRepository.save(one);
        getJourney(aliceToken).andExpect(jsonPath("$.chantiers[0].stale", is(false)))
                .andExpect(jsonPath("$.chantiers[0].unblocker.who", is("AUTRUI")));
        assertThat(one.isIdleInExecution(java.time.OffsetDateTime.now())).isTrue();
        assertThat(one.isStale(java.time.OffsetDateTime.now())).isFalse();
        // Bob ne voit rien du parcours d'Alice.
        getJourney(bobToken).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("SF-223-01 : le plan posé demande une phrase au plus puis la fin du tour")
    void planToolAsksForShortPresentation() throws Exception {
        chantierOneExecuting();
        post(aliceToken, "chantiers", "{\"title\":\"Logs vers Loki\"}");
        service.submitDiagnosis(aliceId, ws(), 2, "pas de collecteur", "kubectl", "ELEVEE");
        tool(JourneyToolCatalog.CONFIRM_DIAGNOSIS, "{\"chantier\":2,\"user_words\":\"go\"}");
        JourneyToolExecutor.Outcome set = tool(JourneyToolCatalog.SET_PLAN,
                "{\"chantier\":2,\"steps\":[{\"title\":\"Installer Fluent Bit\",\"risk\":\"REVERSIBLE\"}]}");
        assertThat(set.error()).isFalse();
        assertThat(set.content()).contains("une phrase au plus, puis termine ton tour")
                .doesNotContain("en quelques lignes");
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

    private JourneyToolExecutor.Outcome tool(String name, String json, JourneyToolExecutor.TurnContext turn)
            throws Exception {
        fr.claudegateway.atelier.Workspace w = workspaceRepository.findById(ws()).orElseThrow();
        return journeyTools.execute(w.getUserId(), w, name,
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json), turn);
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

    @Test
    @DisplayName("SF-221-06 : le worktree d'un chantier s'enregistre sur SA ligne et se relit ; Bob n'y touche pas")
    void chantierWorktreeIsRecorded() throws Exception {
        chantierOneExecuting();
        post(aliceToken, "chantiers", "{\"title\":\"Migration DNS\"}");
        UUID bobId = userRepository.findByEmail("bob-chantiers@ex.com").orElseThrow().getId();

        service.recordChantierWorktree(aliceId, ws(), 2, ".atelier-chantiers/x-ch2", "atelier/chantier-x-ch2");
        service.recordChantierWorktree(bobId, ws(), 1, ".atelier-chantiers/pirate", "pirate");

        assertThat(service.chantierWorktree(aliceId, ws(), 2)).hasValueSatisfying(w ->
                assertThat(w).containsExactly(".atelier-chantiers/x-ch2", "atelier/chantier-x-ch2"));
        assertThat(service.chantierWorktree(aliceId, ws(), 1)).as("la ligne d'Alice n'est pas touchée par Bob").isEmpty();
        // Un geste sur le focus recopie l'état du chantier : le worktree n'est jamais effacé par ce miroir.
        service.submitDiagnosis(aliceId, ws(), "zone", "dig", "MOYENNE");
        assertThat(service.chantierWorktree(aliceId, ws(), 2)).isPresent();
    }

    // ------------------------------------------------------------------ F-271 / SF-271-01 : ouvrir à la voix

    private static JourneyToolExecutor.TurnContext saidNow(String words) {
        return JourneyToolExecutor.TurnContext.ofUser(java.util.List.of(words));
    }

    @Test
    @DisplayName("SF-271-01 : « prends LDIC-325 » dit dans ce tour ouvre le chantier sans clic, titré par la clé")
    void openOnUserWordsFromLibre() throws Exception {
        JourneyToolExecutor.Outcome out = tool(JourneyToolCatalog.PROPOSE_GUIDED,
                "{\"reason\":\"Créer le topic SNS d'ingestion\",\"ticket\":\"ldic-325\","
                        + "\"user_words\":\"prends LDIC-325 et pose-moi le plan\"}",
                saidNow("Jarvis, prends LDIC-325 et pose-moi le plan."));
        assertThat(out.error()).isFalse();
        assertThat(out.content()).startsWith("Chantier 1 ouvert").contains("LDIC-325 — Créer le topic SNS");
        getJourney(aliceToken).andExpect(jsonPath("$.mode", is("GUIDE")))
                .andExpect(jsonPath("$.phase", is("INVESTIGATION")))
                .andExpect(jsonPath("$.chantier.number", is(1)))
                .andExpect(jsonPath("$.chantier.title", is("LDIC-325 — Créer le topic SNS d'ingestion")));
        assertThat(eventRepository.findAll()).anyMatch(e -> SubjectJourneyEvent.GUIDED_ACCEPTED.equals(e.getType())
                && e.getDetail().startsWith("chantier 1 · écrit « prends LDIC-325"));
        // La même clé, redemandée : rien n'est doublé, le chantier existant est nommé.
        JourneyToolExecutor.Outcome again = tool(JourneyToolCatalog.PROPOSE_GUIDED,
                "{\"reason\":\"Topic SNS\",\"ticket\":\"LDIC-325\",\"user_words\":\"prends LDIC-325\"}",
                saidNow("prends LDIC-325"));
        assertThat(again.content()).contains("Le chantier 1").contains("déjà ouvert");
        getJourney(aliceToken).andExpect(jsonPath("$.chantiers.length()", is(1)));
    }

    @Test
    @DisplayName("SF-271-01 : sujet déjà guidé — un chantier DE PLUS s'ouvre sans clic, l'autre reste intact")
    void openOnUserWordsAlongside() throws Exception {
        chantierOneExecuting();
        JourneyToolExecutor.Outcome out = tool(JourneyToolCatalog.PROPOSE_GUIDED,
                "{\"reason\":\"Droits IAM du compte dev\",\"ticket\":\"LDIC-330\",\"user_words\":\"lance aussi "
                        + "LDIC-330\"}", saidNow("Lance aussi LDIC-330."));
        assertThat(out.content()).startsWith("Chantier 2 ouvert");
        getJourney(aliceToken).andExpect(jsonPath("$.chantiers.length()", is(2)))
                .andExpect(jsonPath("$.chantier.number", is(2)))
                .andExpect(jsonPath("$.chantier.title", is("LDIC-330 — Droits IAM du compte dev")))
                .andExpect(jsonPath("$.chantiers[0].phase", is("EXECUTION")));
    }

    @Test
    @DisplayName("SF-271-01 : citation absente, inventée ou tour sans utilisateur ⇒ la proposition attend son clic")
    void openOnUserWordsRefused() throws Exception {
        // Citation inventée : rien n'est ouvert, la carte est posée.
        JourneyToolExecutor.Outcome invented = tool(JourneyToolCatalog.PROPOSE_GUIDED,
                "{\"reason\":\"Topic SNS\",\"ticket\":\"LDIC-325\",\"user_words\":\"ouvre le chantier\"}",
                saidNow("Qu'est-ce que demande LDIC-325 ?"));
        assertThat(invented.error()).isFalse();
        assertThat(invented.content()).startsWith("Chantier NON ouvert").contains("ne figurent pas")
                .contains("[Passer en guidé]");
        getJourney(aliceToken).andExpect(jsonPath("$.mode", is("LIBRE")))
                .andExpect(jsonPath("$.guidedProposal").exists());
        // Le clic ouvre un chantier titré par la clé (la raison proposée la porte).
        post(aliceToken, "guided-proposal/accept", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.chantier.title", is("LDIC-325 — Topic SNS")));
        // Course autonome / routine : jamais d'ouverture, même avec de vrais mots.
        JourneyToolExecutor.Outcome headless = tool(JourneyToolCatalog.PROPOSE_GUIDED,
                "{\"reason\":\"IAM\",\"ticket\":\"LDIC-330\",\"user_words\":\"prends LDIC-330\"}");
        assertThat(headless.content()).startsWith("Chantier NON ouvert").contains("course autonome");
        getJourney(aliceToken).andExpect(jsonPath("$.chantiers.length()", is(1)));
    }

    @Test
    @DisplayName("SF-271-01 : un « Rester libre » passé ne bloque pas une demande explicite d'ouvrir")
    void openOnUserWordsAfterDecline() throws Exception {
        tool(JourneyToolCatalog.PROPOSE_GUIDED, "{\"reason\":\"Chantier\"}");
        post(aliceToken, "guided-proposal/decline", null).andExpect(status().isOk());
        assertThat(tool(JourneyToolCatalog.PROPOSE_GUIDED, "{\"reason\":\"Encore\"}").content()).contains("RESTER LIBRE");
        JourneyToolExecutor.Outcome out = tool(JourneyToolCatalog.PROPOSE_GUIDED,
                "{\"reason\":\"Topic SNS\",\"ticket\":\"LDIC-325\",\"user_words\":\"ouvre un chantier pour "
                        + "LDIC-325\"}", saidNow("ouvre un chantier pour LDIC-325"));
        assertThat(out.content()).startsWith("Chantier 1 ouvert");
        getJourney(aliceToken).andExpect(jsonPath("$.mode", is("GUIDE")));
    }

    @Test
    @DisplayName("SF-271-01 : « LDIC-325 » → (sujet, chantier) parmi les chantiers OUVERTS du compte ; Bob ne voit rien")
    void openChantiersWithTicket() throws Exception {
        chantierOneExecuting();
        tool(JourneyToolCatalog.PROPOSE_GUIDED, "{\"reason\":\"Topic SNS\",\"ticket\":\"LDIC-325\","
                + "\"user_words\":\"prends LDIC-325\"}", saidNow("prends LDIC-325"));
        tool(JourneyToolCatalog.PROPOSE_GUIDED, "{\"reason\":\"IAM\",\"ticket\":\"LDIC-330\","
                + "\"user_words\":\"prends LDIC-330\"}", saidNow("prends LDIC-330"));
        // Chantier 2 (LDIC-325) ouvert à côté, chantier 3 (LDIC-330) au focus.
        assertThat(service.openChantiersWithTicket(aliceId, "ldic-325")).singleElement()
                .satisfies(t -> {
                    assertThat(t.workspaceId()).isEqualTo(ws());
                    assertThat(t.number()).isEqualTo(2);
                });
        assertThat(service.openChantiersWithTicket(aliceId, "LDIC-330")).singleElement()
                .satisfies(t -> assertThat(t.number()).isEqualTo(3));
        assertThat(service.openChantiersWithTicket(aliceId, "LDIC-999")).isEmpty();
        assertThat(service.openChantiersWithTicket(aliceId, "pas une clé")).isEmpty();
        // Isolation : Bob ne trouve jamais les chantiers d'Alice.
        UUID bobId = userRepository.findByEmail("bob-chantiers@ex.com").orElseThrow().getId();
        assertThat(service.openChantiersWithTicket(bobId, "LDIC-325")).isEmpty();
        // Un chantier clos n'est plus trouvé.
        SubjectJourney focus = journeyRepository.findByUserIdAndWorkspaceId(aliceId, ws()).orElseThrow();
        focus.setPhase(JourneyPhase.CLOS);
        journeyRepository.save(focus);
        assertThat(service.openChantiersWithTicket(aliceId, "LDIC-330")).isEmpty();
    }
}
