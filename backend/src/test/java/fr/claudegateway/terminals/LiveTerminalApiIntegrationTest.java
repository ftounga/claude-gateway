package fr.claudegateway.terminals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Les <b>terminaux vivants</b>, de bout en bout (F-70 / SF-70-01).
 *
 * <p>Ce qui compte ici tient en trois questions : <b>combien vivent</b>, <b>que dit-on au
 * cinquième</b>, et <b>que voit-on du voisin</b>. La troisième est la plus importante : le registre
 * nomme des projets et des postes, et un plafond qui compterait les terminaux d'un autre serait à la
 * fois une fuite et un blocage.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LiveTerminalApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private LiveTerminalRepository liveTerminalRepository;
    @Autowired private JwtService jwtService;

    private String aliceToken;
    private String bobToken;
    private UUID aliceId;
    private RunnerHost aliceHost;
    private Workspace aliceProject;
    private Workspace aliceOtherProject;
    private Workspace bobProject;

    @BeforeEach
    void setUp() {
        liveTerminalRepository.deleteAll();
        workspaceRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        User alice = seedUser("alice-terminals@example.com");
        aliceId = alice.getId();
        aliceToken = jwtService.generateToken(alice);
        aliceHost = seedHost(aliceId, "Poste CAGIP");
        aliceProject = seedWorkspace(aliceId, aliceHost.getId(), "web");
        aliceOtherProject = seedWorkspace(aliceId, aliceHost.getId(), "api");

        User bob = seedUser("bob-terminals@example.com");
        bobToken = jwtService.generateToken(bob);
        RunnerHost bobHost = seedHost(bob.getId(), "Poste de Bob");
        bobProject = seedWorkspace(bob.getId(), bobHost.getId(), "secret");
    }

    // ------------------------------------------------------------------ décors

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }

    private RunnerHost seedHost(UUID userId, String name) {
        return hostRepository.save(RunnerHost.builder().userId(userId).name(name).rootName("dev")
                .os("linux").shell("posix").elevated(false)
                .lastSeenAt(OffsetDateTime.now()).build());
    }

    private Workspace seedWorkspace(UUID userId, UUID hostId, String name) {
        return workspaceRepository.save(Workspace.builder().userId(userId).name(name)
                .hostId(hostId).projectPath(name)
                .executionTarget(WorkspaceExecutionTarget.RUNNER).build());
    }

    private String claimUrl(UUID workspaceId) {
        return "/api/workspaces/" + workspaceId + "/terminal/live";
    }

    private String body(String sessionId) {
        return "{\"sessionId\":\"" + sessionId + "\"}";
    }

    private void claim(String token, UUID workspaceId, String sessionId) throws Exception {
        mockMvc.perform(post(claimUrl(workspaceId)).contextPath("/api")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(sessionId)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ cas nominal

    @Test
    void aTerminalTakesItsPlaceAndTheRegisterNamesIt() throws Exception {
        mockMvc.perform(post(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("tab-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(4))
                .andExpect(jsonPath("$.live").value(1))
                .andExpect(jsonPath("$.terminals[0].workspaceName").value("web"))
                .andExpect(jsonPath("$.terminals[0].hostName").value("Poste CAGIP"));
    }

    @Test
    void theSameTabBeatingItsHeartDoesNotConsumeASecondPlace() throws Exception {
        claim(aliceToken, aliceProject.getId(), "tab-1");
        OffsetDateTime firstSeen = liveTerminalRepository
                .findByUserIdAndSessionId(aliceId, "tab-1").orElseThrow().getLastSeenAt();

        claim(aliceToken, aliceProject.getId(), "tab-1");

        mockMvc.perform(get("/api/terminals/live").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.live").value(1));
        assertThat(liveTerminalRepository.findByUserIdAndSessionId(aliceId, "tab-1").orElseThrow()
                .getLastSeenAt()).isAfterOrEqualTo(firstSeen);
    }

    @Test
    void aTabThatChangesProjectKeepsItsPlace() throws Exception {
        claim(aliceToken, aliceProject.getId(), "tab-1");
        claim(aliceToken, aliceOtherProject.getId(), "tab-1");

        mockMvc.perform(get("/api/terminals/live").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.live").value(1))
                .andExpect(jsonPath("$.terminals[0].workspaceName").value("api"));
    }

    // ------------------------------------------------------------------ le plafond

    @Test
    void theFifthTerminalIsRefusedWithTheSentenceTheScreenShows() throws Exception {
        for (int i = 1; i <= 4; i++) {
            claim(aliceToken, aliceProject.getId(), "tab-" + i);
        }

        mockMvc.perform(post(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("tab-5")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("terminal_limit_reached"))
                .andExpect(jsonPath("$.message")
                        .value("Quatre terminaux actifs au maximum, fermez-en un pour en ouvrir un autre."));
    }

    @Test
    void aRefusedClaimLeavesNoTraceBehind() throws Exception {
        for (int i = 1; i <= 4; i++) {
            claim(aliceToken, aliceProject.getId(), "tab-" + i);
        }
        mockMvc.perform(post(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("tab-5")))
                .andExpect(status().isConflict());

        // Le registre doit rester lisible : EXACTEMENT quatre, et pas de cinquième fantôme.
        mockMvc.perform(get("/api/terminals/live").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.live").value(4));
        assertThat(liveTerminalRepository.findByUserIdAndSessionId(aliceId, "tab-5")).isEmpty();
    }

    @Test
    void freeingAPlaceMakesTheNextClaimSucceed() throws Exception {
        for (int i = 1; i <= 4; i++) {
            claim(aliceToken, aliceProject.getId(), "tab-" + i);
        }

        mockMvc.perform(delete(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("sessionId", "tab-2"))
                .andExpect(status().isNoContent());

        claim(aliceToken, aliceProject.getId(), "tab-5");
        mockMvc.perform(get("/api/terminals/live").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.live").value(4));
    }

    @Test
    void releasingAPlaceThatIsAlreadyFreeIsNotAnError() throws Exception {
        mockMvc.perform(delete(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("sessionId", "never-opened"))
                .andExpect(status().isNoContent());
    }

    @Test
    void aTabClosedBrutallyFreesItsPlaceOnItsOwn() throws Exception {
        for (int i = 1; i <= 4; i++) {
            claim(aliceToken, aliceProject.getId(), "tab-" + i);
        }
        // Personne n'a envoyé de libération : l'onglet a simplement disparu. Sans expiration, le
        // compte resterait condamné.
        LiveTerminal abandoned = liveTerminalRepository
                .findByUserIdAndSessionId(aliceId, "tab-3").orElseThrow();
        abandoned.setLastSeenAt(OffsetDateTime.now().minusHours(1));
        liveTerminalRepository.save(abandoned);

        claim(aliceToken, aliceProject.getId(), "tab-5");

        mockMvc.perform(get("/api/terminals/live").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.live").value(4));
        assertThat(liveTerminalRepository.findByUserIdAndSessionId(aliceId, "tab-3")).isEmpty();
    }

    // ------------------------------------------------------------------ isolation user_id

    @Test
    void aTerminalCannotBeOpenedOnSomeoneElsesProject() throws Exception {
        mockMvc.perform(post(claimUrl(bobProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("tab-1")))
                .andExpect(status().isNotFound());

        assertThat(liveTerminalRepository.count()).isZero();
    }

    @Test
    void theCeilingOfOneUserNeverBlocksAnother() throws Exception {
        for (int i = 1; i <= 4; i++) {
            claim(aliceToken, aliceProject.getId(), "tab-" + i);
        }

        claim(bobToken, bobProject.getId(), "tab-1");

        mockMvc.perform(get("/api/terminals/live").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(jsonPath("$.live").value(1))
                .andExpect(jsonPath("$.terminals[0].workspaceName").value("secret"));
    }

    @Test
    void theRegisterNeverNamesSomeoneElsesProject() throws Exception {
        claim(bobToken, bobProject.getId(), "tab-1");

        mockMvc.perform(get("/api/terminals/live").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.live").value(0))
                .andExpect(jsonPath("$.terminals").isEmpty());
    }

    @Test
    void oneTabCannotFreeAnotherUsersPlace() throws Exception {
        claim(bobToken, bobProject.getId(), "tab-1");

        mockMvc.perform(delete(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("sessionId", "tab-1"))
                .andExpect(status().isNoContent());

        // La place de Bob est intacte : la libération est bornée à son propriétaire.
        mockMvc.perform(get("/api/terminals/live").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(jsonPath("$.live").value(1));
    }

    // ------------------------------------------------------------------ validation & accès

    @Test
    void anEmptySessionIdIsRefused() throws Exception {
        mockMvc.perform(post(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aSessionIdWithAForbiddenCharacterIsRefused() throws Exception {
        mockMvc.perform(post(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("tab 1;drop")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theRegisterIsNotReadableWithoutAToken() throws Exception {
        mockMvc.perform(get("/api/terminals/live").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ vue d'ensemble

    @Test
    void theHostOverviewShowsWhichProjectsHaveALiveTerminal() throws Exception {
        claim(aliceToken, aliceProject.getId(), "tab-1");

        mockMvc.perform(get("/api/runner-hosts/overview").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].liveTerminals").value(1))
                .andExpect(jsonPath("$[0].projects[?(@.name == 'web')].liveTerminal")
                        .value(org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$[0].projects[?(@.name == 'api')].liveTerminal")
                        .value(org.hamcrest.Matchers.hasItem(false)));
    }

    @Test
    void anotherUsersLiveTerminalNeverLightsUpMyOverview() throws Exception {
        claim(bobToken, bobProject.getId(), "tab-1");

        mockMvc.perform(get("/api/runner-hosts/overview").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].liveTerminals").value(0));
    }

    // ------------------------------------------------- l'aperçu vivant (F-76 / SF-76-01)

    /** Corps d'un battement de cœur portant un aperçu. */
    private String bodyWithPreview(String sessionId, String activity, String detail,
            String... lines) {
        StringBuilder joined = new StringBuilder();
        for (String line : lines) {
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append('"').append(line).append('"');
        }
        return "{\"sessionId\":\"" + sessionId + "\",\"activity\":\"" + activity
                + "\",\"activityDetail\":\"" + detail + "\",\"previewLines\":["
                + joined + "]}";
    }

    @Test
    void theRegisterSaysWhatEachTerminalIsDoing() throws Exception {
        mockMvc.perform(post(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithPreview("tab-1", "RUNNING", "npm test",
                                "$ npm test", "PASS src/app.spec.ts")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/terminals/live").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.terminals[0].activity").value("RUNNING"))
                .andExpect(jsonPath("$.terminals[0].activityDetail").value("npm test"))
                .andExpect(jsonPath("$.terminals[0].previewLines[0]").value("$ npm test"))
                .andExpect(jsonPath("$.terminals[0].previewLines[1]").value("PASS src/app.spec.ts"))
                .andExpect(jsonPath("$.terminals[0].activityAt").isNotEmpty());
    }

    @Test
    void aTerminalThatAwaitsAnApprovalSaysSo() throws Exception {
        // L'exigence non négociable : c'est le seul état que l'utilisateur DOIT voir, et il doit
        // pouvoir le voir sans ouvrir l'onglet concerné.
        mockMvc.perform(post(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithPreview("tab-1", "AWAITING_APPROVAL", "rm -rf build",
                                "Autorisation demandée")))
                .andExpect(jsonPath("$.terminals[0].activity").value("AWAITING_APPROVAL"));
    }

    @Test
    void aHeartbeatWithoutAPreviewErasesNothing() throws Exception {
        // Ne rien dire n'est pas dire qu'il ne se passe rien : un écran antérieur à F-76 tient sa
        // place exactement comme avant, sans effacer ce qu'un autre battement a écrit.
        mockMvc.perform(post(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithPreview("tab-1", "RUNNING", "npm test", "PASS")))
                .andExpect(status().isOk());

        claim(aliceToken, aliceProject.getId(), "tab-1");

        mockMvc.perform(get("/api/terminals/live").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.terminals[0].activity").value("RUNNING"))
                .andExpect(jsonPath("$.terminals[0].previewLines[0]").value("PASS"));
    }

    @Test
    void theBoundsAreHeldByTheGatewayNotByTheScreen() throws Exception {
        String[] flood = new String[50];
        for (int i = 0; i < flood.length; i++) {
            flood[i] = "ligne " + (i + 1);
        }
        mockMvc.perform(post(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithPreview("tab-1", "RUNNING", "x".repeat(400), flood)))
                .andExpect(status().isOk())
                // Six lignes, et ce sont les DERNIÈRES : un aperçu dit où l'on en est.
                .andExpect(jsonPath("$.terminals[0].previewLines.length()").value(6))
                .andExpect(jsonPath("$.terminals[0].previewLines[5]").value("ligne 50"))
                .andExpect(jsonPath("$.terminals[0].activityDetail").value("x".repeat(120)));
    }

    @Test
    void anUnknownActivityIsReadAsIdleRatherThanRefused() throws Exception {
        // Un écran déployé avant sa gateway perdrait sinon sa PLACE pour un ornement.
        mockMvc.perform(post(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithPreview("tab-1", "DANCING", "valse", "une ligne")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terminals[0].activity").value("IDLE"))
                .andExpect(jsonPath("$.terminals[0].previewLines[0]").value("une ligne"));
    }

    @Test
    void anActivityThatIsNotALabelAtAllIsRefused() throws Exception {
        mockMvc.perform(post(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithPreview("tab-1", "running!", "x", "y")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theOverviewCarriesThePreviewOfEachLiveProject() throws Exception {
        // PREMIÈRE DENSITÉ : on voit qu'un agent attend quelque chose sans rien ouvrir.
        mockMvc.perform(post(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithPreview("tab-1", "AWAITING_APPROVAL", "git push",
                                "Autorisation demandée")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/runner-hosts/overview").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$[0].projects[?(@.name == 'web')].terminalPreview.activity")
                        .value(org.hamcrest.Matchers.hasItem("AWAITING_APPROVAL")))
                .andExpect(jsonPath("$[0].projects[?(@.name == 'api')].terminalPreview")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())));
    }

    @Test
    void anotherUsersPreviewNeverReachesMyScreens() throws Exception {
        mockMvc.perform(post(claimUrl(bobProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithPreview("tab-1", "RUNNING", "secret-tool",
                                "ligne confidentielle")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/terminals/live").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.terminals").isEmpty());
        mockMvc.perform(get("/api/runner-hosts/overview").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$..terminalPreview")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())));
    }

    // ------------------------------------------ la concurrence (F-78 / SF-78-01)

    /** Nombre d'appels lancés ensemble. Sous la taille du pool de connexions (10 par défaut). */
    private static final int CONCURRENT_CALLERS = 8;

    /** Lance {@code callers} battements en même temps et rend le code HTTP rendu à chacun. */
    private List<Integer> beatTogether(int callers, java.util.function.IntFunction<String> body)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> pending = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                final String content = body.apply(i);
                pending.add(pool.submit(() -> {
                    gate.await();
                    return mockMvc.perform(post(claimUrl(aliceProject.getId())).contextPath("/api")
                                    .header("Authorization", "Bearer " + aliceToken)
                                    .contentType(MediaType.APPLICATION_JSON).content(content))
                            .andReturn().getResponse().getStatus();
                }));
            }
            gate.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : pending) {
                statuses.add(future.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentHeartbeatsOfTheSameTabNeverCollide() throws Exception {
        // LE DÉFAUT DU 2026-09-12, REPRODUIT. F-76 a branché trois sources d'envoi sur ce seul
        // appel — battement de 30 s, envoi immédiat au changement d'activité, envoi apaisé à 5 s au
        // défilement des lignes. Quand deux arrivent ensemble, l'ancienne prise de place les
        // laissait insérer tous les deux et le second violait idx_live_terminals_user_session :
        // 500 en boucle, à un écran qui ne demandait qu'à tenir sa place.
        List<Integer> statuses = beatTogether(CONCURRENT_CALLERS, i -> i % 2 == 0
                ? bodyWithPreview("tab-1", "RUNNING", "npm test", "PASS")
                : body("tab-1"));

        // ZÉRO ERREUR. Pas un seul 500 : c'est toute la feature.
        assertThat(statuses).containsOnly(200);
        // UNE SEULE PLACE : un onglet ne consomme jamais qu'une place, même en se bousculant.
        assertThat(liveTerminalRepository.count()).isEqualTo(1);
        assertThat(liveTerminalRepository.findByUserIdAndSessionId(aliceId, "tab-1")).isPresent();
    }

    @Test
    void concurrentHeartbeatsKeepTheOldestOpenedAt() throws Exception {
        // opened_at décide QUI garde sa place quand deux prises se croisent. Un renouvellement qui
        // le remettrait à l'instant présent ferait passer un vieil onglet pour un nouveau et
        // changerait qui est refusé au plafond.
        claim(aliceToken, aliceProject.getId(), "tab-1");
        OffsetDateTime firstOpening = liveTerminalRepository
                .findByUserIdAndSessionId(aliceId, "tab-1").orElseThrow().getOpenedAt();

        assertThat(beatTogether(CONCURRENT_CALLERS, i -> body("tab-1"))).containsOnly(200);

        assertThat(liveTerminalRepository.findByUserIdAndSessionId(aliceId, "tab-1").orElseThrow()
                .getOpenedAt()).isEqualTo(firstOpening);
    }

    @Test
    void concurrentTabsRacingForTheLastPlacesNeverGetAnError() throws Exception {
        // L'AUTRE COURSE : huit ONGLETS DIFFÉRENTS qui se jettent ensemble sur les places libres.
        // Ce qui est vérifié ici, c'est qu'aucun n'obtient une panne : soit sa place, soit le refus
        // du PO. Le compte EXACT de quatre, lui, est tenu par les tests séquentiels du plafond —
        // le recompte lit les places COMMITÉES, propriété de F-70 que F-78 ne change pas.
        List<Integer> statuses = beatTogether(CONCURRENT_CALLERS, i -> body("race-" + i));

        assertThat(statuses).isSubsetOf(200, 409);
        assertThat(statuses).contains(200);
        // L'index unique n'a pas été bousculé : pas deux lignes pour un même onglet.
        Set<String> sessions = new HashSet<>();
        liveTerminalRepository.findAll().forEach(terminal -> sessions.add(terminal.getSessionId()));
        assertThat(sessions).hasSize((int) liveTerminalRepository.count());
    }

    @Test
    void aTabRefusedWhileBeatingLeavesTheRegisterAtExactlyFour() throws Exception {
        // Le cinquième onglet bat, encore et encore, comme le fait un vrai écran refusé. Chaque
        // battement doit rendre le MÊME refus, et ne JAMAIS laisser de trace : l'écran qui reçoit
        // le 409 relit le registre et doit y compter exactement quatre.
        for (int i = 1; i <= 4; i++) {
            claim(aliceToken, aliceProject.getId(), "tab-" + i);
        }

        for (int beat = 0; beat < 3; beat++) {
            mockMvc.perform(post(claimUrl(aliceProject.getId())).contextPath("/api")
                            .header("Authorization", "Bearer " + aliceToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyWithPreview("tab-5", "RUNNING", "npm test", "PASS")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("terminal_limit_reached"));
        }

        mockMvc.perform(get("/api/terminals/live").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.live").value(4));
        assertThat(liveTerminalRepository.findByUserIdAndSessionId(aliceId, "tab-5")).isEmpty();
    }

    @Test
    void aRefusedFifthTerminalWritesNoPreviewEither() throws Exception {
        // Non-régression du plafond de F-70, aperçus présents : le garde-fou de dépense ne bouge
        // pas parce qu'on a ajouté du décor.
        for (int i = 1; i <= 4; i++) {
            mockMvc.perform(post(claimUrl(aliceProject.getId())).contextPath("/api")
                            .header("Authorization", "Bearer " + aliceToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyWithPreview("tab-" + i, "RUNNING", "npm test", "PASS")))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post(claimUrl(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithPreview("tab-5", "RUNNING", "npm test", "PASS")))
                .andExpect(status().isConflict());

        assertThat(liveTerminalRepository.findByUserIdAndSessionId(aliceId, "tab-5")).isEmpty();
    }
}
