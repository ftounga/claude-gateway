package fr.claudegateway.atelier.dashboard;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.actions.TerminalAction;
import fr.claudegateway.atelier.actions.TerminalActionKind;
import fr.claudegateway.atelier.actions.TerminalActionQui;
import fr.claudegateway.atelier.actions.TerminalActionRepository;
import fr.claudegateway.atelier.actions.TerminalActionService;
import fr.claudegateway.atelier.actions.TerminalActionStatus;
import fr.claudegateway.atelier.journey.JourneyPlan;
import fr.claudegateway.atelier.journey.SubjectJourneyChantierRepository;
import fr.claudegateway.atelier.journey.SubjectJourneyEventRepository;
import fr.claudegateway.atelier.journey.SubjectJourneyRepository;
import fr.claudegateway.atelier.journey.SubjectJourneyService;
import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.atelier.live.PendingQuestion;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-220 / SF-220-02 — le tableau du client : tous les chantiers ouverts des sujets d'un poste, groupés par qui
 * les débloque, le plus dormant d'abord ; ni l'autre poste ni le terminal Teams ; et Bob ne lit rien.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClientBoardIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private SubjectJourneyRepository journeyRepository;
    @Autowired private SubjectJourneyChantierRepository chantierRepository;
    @Autowired private SubjectJourneyEventRepository eventRepository;
    @Autowired private SubjectJourneyService service;
    @Autowired private LiveTurnRegistry liveTurns;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TerminalActionService terminalActions;
    @Autowired private TerminalActionRepository actionRepository;

    private String aliceToken;
    private String bobToken;
    private UUID aliceId;
    private RunnerHost aliceHost;
    private Workspace ingress;
    private Workspace migration;
    private Workspace elsewhere;
    private Workspace teams;
    private LiveTurn openedTurn;

    @BeforeEach
    void setUp() {
        actionRepository.deleteAll();
        eventRepository.deleteAll();
        chantierRepository.deleteAll();
        journeyRepository.deleteAll();
        workspaceRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();
        User alice = seedUser("alice-board@example.com");
        aliceId = alice.getId();
        aliceToken = jwtService.generateToken(alice);
        aliceHost = seedHost(aliceId, "EDENRED");
        RunnerHost otherHost = seedHost(aliceId, "CAGIP");
        ingress = seedWorkspace(aliceId, aliceHost.getId(), "ingress", false);
        migration = seedWorkspace(aliceId, aliceHost.getId(), "migration", false);
        teams = seedWorkspace(aliceId, aliceHost.getId(), "teams", true);
        elsewhere = seedWorkspace(aliceId, otherHost.getId(), "ailleurs", false);
        User bob = seedUser("bob-board@example.com");
        bobToken = jwtService.generateToken(bob);
    }

    @AfterEach
    void tearDown() {
        if (openedTurn != null) {
            liveTurns.close(openedTurn);
        }
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }

    private RunnerHost seedHost(UUID userId, String name) {
        return hostRepository.save(RunnerHost.builder().userId(userId).name(name).rootName("dev")
                .os("linux").shell("posix").elevated(false)
                .lastSeenAt(OffsetDateTime.now()).build());
    }

    private Workspace seedWorkspace(UUID userId, UUID hostId, String name, boolean teamsTerminal) {
        return workspaceRepository.save(Workspace.builder().userId(userId).name(name)
                .hostId(hostId).projectPath(name).teamsTerminal(teamsTerminal)
                .executionTarget(WorkspaceExecutionTarget.RUNNER).build());
    }

    private static JourneyPlan plan(String json) {
        try {
            return JourneyPlan.fromToolInput(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void guided(Workspace w, String title) throws Exception {
        mockMvc.perform(put("/api/workspaces/" + w.getId() + "/journey/mode").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"GUIDE\",\"title\":\"" + title + "\"}")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
    }

    private void toPlan(Workspace w, String title) throws Exception {
        guided(w, title);
        service.submitDiagnosis(aliceId, w.getId(), "diagnostic", "preuve", "ELEVEE");
        service.confirmDiagnosis(aliceId, w.getId());
        service.setPlan(aliceId, w.getId(), plan("[{\"title\":\"Lire\",\"risk\":\"LECTURE\"},"
                + "{\"title\":\"Corriger\",\"risk\":\"REVERSIBLE\"}]"));
    }

    private ResultActions board(String token, UUID hostId) throws Exception {
        return mockMvc.perform(get("/api/runner-hosts/" + hostId + "/chantiers").contextPath("/api")
                .header("Authorization", "Bearer " + token));
    }

    /** Ingress : chantier 1 en exécution (prêt), chantier 2 ouvert à côté en investigation (focus). */
    private void seedIngress() throws Exception {
        toPlan(ingress, "Certificat");
        service.validatePlan(aliceId, ingress.getId(), null);
        service.openAlongside(aliceId, ingress.getId(), "Logs");
    }

    @Test
    @DisplayName("tous les chantiers ouverts du poste, groupés par qui débloque, le plus dormant d'abord")
    void boardGroupsAndAges() throws Exception {
        seedIngress();
        toPlan(migration, "Bascule"); // plan posé, non validé : ma décision
        guided(elsewhere, "Ailleurs");
        guided(teams, "Teams");
        OffsetDateTime sixDaysAgo = OffsetDateTime.now().minusDays(6).minusHours(1);
        jdbc.update("update subject_journeys set updated_at = ?, chantier_opened_at = ? where workspace_id = ?",
                sixDaysAgo, sixDaysAgo, migration.getId());

        board(aliceToken, aliceHost.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostName", is("EDENRED")))
                .andExpect(jsonPath("$.chantiers", hasSize(3)))
                .andExpect(jsonPath("$.chantiers[0].unblocker.who", is("OUTIL")))
                .andExpect(jsonPath("$.chantiers[1].unblocker.who", is("OUTIL")))
                .andExpect(jsonPath("$.chantiers[2].workspaceName", is("migration")))
                .andExpect(jsonPath("$.chantiers[2].unblocker.who", is("MOI")))
                .andExpect(jsonPath("$.chantiers[2].idleDays", is(6)))
                .andExpect(jsonPath("$.chantiers[2].dormant", is(true)))
                .andExpect(jsonPath("$.chantiers[2].nextStep.rank", is(1)))
                .andExpect(jsonPath("$.chantiers[2].ready", is(false)))
                .andExpect(jsonPath("$.chantiers[2].missing[0]", is("Le plan attend votre validation.")))
                .andExpect(jsonPath("$.counts.outil", is(2)))
                .andExpect(jsonPath("$.counts.moi", is(1)))
                .andExpect(jsonPath("$.counts.dormant", is(1)))
                .andExpect(jsonPath("$.autonomy.maxParallel", is(4)));
    }

    @Test
    @DisplayName("chantier prêt : ready et rien ne manque ; prochaine étape, avancement")
    void readyChantier() throws Exception {
        seedIngress();
        board(aliceToken, aliceHost.getId())
                .andExpect(jsonPath("$.chantiers[?(@.number == 1)].ready", org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.chantiers[?(@.number == 1)].missing",
                        org.hamcrest.Matchers.contains(org.hamcrest.Matchers.empty())))
                .andExpect(jsonPath("$.chantiers[?(@.number == 1)].nextStep.title",
                        org.hamcrest.Matchers.contains("Lire")))
                .andExpect(jsonPath("$.chantiers[?(@.number == 1)].total", org.hamcrest.Matchers.contains(2)));
    }

    @Test
    @DisplayName("question en suspens : le chantier au focus attend ma décision, la question est montrée, il vit")
    void questionPending() throws Exception {
        seedIngress();
        openedTurn = liveTurns.open(aliceId, ingress.getId());
        openedTurn.publishQuestionRequest(java.util.Map.of("callId", "c1"), new PendingQuestion("c1",
                "[{\"header\":\"Namespace\",\"question\":\"Quel namespace viser ?\"}]", 60_000L,
                System.currentTimeMillis()));
        board(aliceToken, aliceHost.getId())
                .andExpect(jsonPath("$.chantiers[?(@.focus == true)].unblocker.who",
                        org.hamcrest.Matchers.contains("MOI")))
                .andExpect(jsonPath("$.chantiers[?(@.focus == true)].question",
                        org.hamcrest.Matchers.contains("Quel namespace viser ?")))
                .andExpect(jsonPath("$.chantiers[?(@.focus == true)].live", org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.chantiers[?(@.focus == false)].question",
                        org.hamcrest.Matchers.contains(nullValue())))
                .andExpect(jsonPath("$.counts.live", is(1)));
    }

    @Test
    @DisplayName("poste sans sujet guidé : liste vide")
    void empty() throws Exception {
        board(aliceToken, aliceHost.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chantiers", hasSize(0)))
                .andExpect(jsonPath("$.counts.outil", is(0)));
    }

    @Test
    @DisplayName("isolation : Bob ne lit pas le tableau du poste d'Alice (404)")
    void isolation() throws Exception {
        seedIngress();
        board(bobToken, aliceHost.getId()).andExpect(status().isNotFound());
        board(bobToken, UUID.randomUUID()).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ SF-220-07 : hors chantier

    /** Une attente inscrite par la route de l'agent, puis mise dans l'état voulu. */
    private TerminalAction attente(Workspace w, String key, String description, String person,
            TerminalActionStatus status, TerminalActionQui qui) {
        UUID id = terminalActions.record(aliceId, w.getId(), null, description, null, person,
                TerminalActionKind.MESSAGE, key).action().getId();
        TerminalAction action = actionRepository.findById(id).orElseThrow();
        action.setStatus(status);
        action.setQui(qui);
        return actionRepository.save(action);
    }

    private static final String WAITING_PLAN = "[{\"title\":\"Lire\",\"risk\":\"LECTURE\"},"
            + "{\"title\":\"Obtenir le certificat\",\"risk\":\"EXTERNE\",\"waits_on\":\"cert-gino\"}]";

    /** Migration : chantier en exécution dont l'étape 2 attend « cert-gino ». */
    private void seedWaitingChantier() throws Exception {
        guided(migration, "Bascule");
        service.submitDiagnosis(aliceId, migration.getId(), "diagnostic", "preuve", "ELEVEE");
        service.confirmDiagnosis(aliceId, migration.getId());
        service.setPlan(aliceId, migration.getId(), plan(WAITING_PLAN));
        service.validatePlan(aliceId, migration.getId(), null);
    }

    @Test
    @DisplayName("hors chantier : les attentes qu'aucun chantier ne montre, ma décision avant quelqu'un")
    void looseTasks() throws Exception {
        seedWaitingChantier();
        attente(migration, "cert-gino", "Demander le certificat à Gino", "Gino",
                TerminalActionStatus.DEMANDE, TerminalActionQui.CLIENT); // citée : déjà visible
        attente(ingress, "dns-support", "Ticket DNS chez l'hébergeur", "Support",
                TerminalActionStatus.A_FAIRE, TerminalActionQui.TIERS); // pas encore demandée : à moi
        TerminalAction asked = attente(ingress, "vpn-marc", "Ouverture du VPN par Marc", "Marc",
                TerminalActionStatus.DEMANDE, TerminalActionQui.CLIENT);
        attente(migration, "sso", "Se reconnecter en SSO", null,
                TerminalActionStatus.A_FAIRE, TerminalActionQui.MOI);
        attente(elsewhere, "autre-poste", "Attente d'un autre poste", "Paul",
                TerminalActionStatus.A_FAIRE, TerminalActionQui.CLIENT);
        attente(teams, "teams-key", "Attente du terminal Teams", "Léa",
                TerminalActionStatus.A_FAIRE, TerminalActionQui.CLIENT);
        attente(ingress, "deja-fait", "Déjà obtenu", "Zoé", TerminalActionStatus.FAIT, TerminalActionQui.CLIENT);
        OffsetDateTime fiveDaysAgo = OffsetDateTime.now().minusDays(5).minusHours(1);
        jdbc.update("update terminal_actions set requested_to = ?, requested_at = ?, updated_at = ? where id = ?",
                "Marc Dupont", fiveDaysAgo, fiveDaysAgo, asked.getId());

        board(aliceToken, aliceHost.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.horsChantier", hasSize(3)))
                .andExpect(jsonPath("$.horsChantier[0].unblocker.who", is("MOI")))
                .andExpect(jsonPath("$.horsChantier[1].unblocker.who", is("MOI")))
                .andExpect(jsonPath("$.horsChantier[?(@.description == 'Ticket DNS chez l\\'hébergeur')].unblocker.detail",
                        org.hamcrest.Matchers.contains("À demander à Support.")))
                .andExpect(jsonPath("$.horsChantier[?(@.qui == 'MOI')].unblocker.detail",
                        org.hamcrest.Matchers.contains("Un geste à faire par vous.")))
                .andExpect(jsonPath("$.horsChantier[2].description", is("Ouverture du VPN par Marc")))
                .andExpect(jsonPath("$.horsChantier[2].workspaceName", is("ingress")))
                .andExpect(jsonPath("$.horsChantier[2].unblocker.who", is("AUTRUI")))
                .andExpect(jsonPath("$.horsChantier[2].unblocker.label", is("On attend quelqu'un")))
                .andExpect(jsonPath("$.horsChantier[2].unblocker.waitingOn", is("Marc Dupont")))
                .andExpect(jsonPath("$.horsChantier[2].unblocker.since", org.hamcrest.Matchers.notNullValue()))
                .andExpect(jsonPath("$.horsChantier[2].idleDays", is(5)))
                .andExpect(jsonPath("$.horsChantier[2].dormant", is(true)))
                .andExpect(jsonPath("$.horsChantier[2].followUpDue", is(true)))
                .andExpect(jsonPath("$.counts.horsChantier", is(3)))
                // le chantier de migration peut avancer (étape 1) : l'outil ; les tâches s'ajoutent aux couleurs
                .andExpect(jsonPath("$.counts.outil", is(1)))
                .andExpect(jsonPath("$.counts.autrui", is(1)))
                .andExpect(jsonPath("$.counts.moi", is(2)))
                .andExpect(jsonPath("$.counts.dormant", is(1)));
    }

    @Test
    @DisplayName("hors chantier : une attente citée seulement par une étape faite redevient visible")
    void citedByDoneStepOnly() throws Exception {
        seedWaitingChantier();
        attente(migration, "cert-gino", "Demander le certificat à Gino", "Gino",
                TerminalActionStatus.DEMANDE, TerminalActionQui.CLIENT);
        board(aliceToken, aliceHost.getId()).andExpect(jsonPath("$.horsChantier", hasSize(0)));

        service.updateStep(aliceId, migration.getId(), 1, "VERIFIE", "lu");
        service.updateStep(aliceId, migration.getId(), 2, "FAIT", "obtenu");
        board(aliceToken, aliceHost.getId())
                .andExpect(jsonPath("$.horsChantier", hasSize(1)))
                .andExpect(jsonPath("$.horsChantier[0].unblocker.waitingOn", is("Gino")));
    }

    @Test
    @DisplayName("hors chantier sans aucun chantier : la section est servie ; Bob ne la lit pas")
    void looseWithoutChantier() throws Exception {
        attente(ingress, "dns-support", "Ticket DNS chez l'hébergeur", "Support",
                TerminalActionStatus.A_FAIRE, null);
        board(aliceToken, aliceHost.getId())
                .andExpect(jsonPath("$.chantiers", hasSize(0)))
                .andExpect(jsonPath("$.horsChantier", hasSize(1)))
                .andExpect(jsonPath("$.horsChantier[0].unblocker.who", is("MOI")))
                .andExpect(jsonPath("$.counts.moi", is(1)));
        board(bobToken, aliceHost.getId()).andExpect(status().isNotFound());
    }
}
