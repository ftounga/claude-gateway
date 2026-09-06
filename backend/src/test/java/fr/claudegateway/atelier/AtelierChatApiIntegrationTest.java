package fr.claudegateway.atelier;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.WorkspaceService.CreatedWorkspace;
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
 * Tests d'intégration de l'Atelier (F-28 / SF-28-02) : la boucle tool-use exécute réellement les
 * outils fichiers sur le workspace, sous isolation {@code user_id}. Le fournisseur d'agent est un stub
 * scriptable (aucun réseau).
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AtelierChatApiIntegrationTest {

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        StubAiAgentProvider stubAiAgentProvider() {
            return new StubAiAgentProvider();
        }

        /** Exécuteur SSE synchrone : le relais s'exécute au retour du contrôleur (corps lisible direct). */
        @Bean("chatStreamExecutor")
        @Primary
        java.util.concurrent.Executor chatStreamExecutor() {
            return Runnable::run;
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private AtelierMessageRepository atelierMessageRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private StubAiAgentProvider stub;

    private User alice;
    private String aliceToken;
    private User bob;
    private String bobToken;

    @BeforeEach
    void setUp() {
        atelierMessageRepository.deleteAll();
        workspaceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        stub.reset();
        // Gating SF-28-06 : l'Atelier est réservé à l'offre Gold. Alice et Bob sont abonnés Gold actif ;
        // un accès non-Gold est testé séparément (chatRequiresGoldAccess).
        alice = userRepository.save(User.builder().email("alice@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        provisionGold(alice);
        aliceToken = jwtService.generateToken(alice);
        bob = userRepository.save(User.builder().email("bob@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        provisionGold(bob);
        bobToken = jwtService.generateToken(bob);
    }

    private void provisionGold(User user) {
        subscriptionRepository.save(Subscription.builder()
                .userId(user.getId())
                .planCode(PlanCode.GOLD)
                .status(SubscriptionStatus.ACTIVE)
                .build());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private UUID createWorkspace(User user, String file, String content) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            zip.putNextEntry(new ZipEntry(file));
            zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        CreatedWorkspace created = workspaceService.create(user.getId(), "projet", baos.toByteArray());
        return created.workspace().getId();
    }

    @Test
    void agentLoopReadsAndWritesFilesThenAnswers() throws Exception {
        UUID ws = createWorkspace(alice, "notes.txt", "contenu initial");
        stub.enqueueToolCall("read_file", "path", "notes.txt");
        stub.enqueueToolCall("write_file", "path", "notes.txt", "content", "contenu mis à jour");
        stub.enqueueFinal("J'ai mis à jour notes.txt.");

        mockMvc.perform(post("/api/workspaces/" + ws + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"mets à jour notes.txt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply", containsString("mis à jour")))
                .andExpect(jsonPath("$.actions[?(@.type=='read')].path", org.hamcrest.Matchers.hasItem("notes.txt")))
                .andExpect(jsonPath("$.actions[?(@.type=='write')].path", org.hamcrest.Matchers.hasItem("notes.txt")));

        // Le fichier a réellement été modifié dans le workspace.
        org.assertj.core.api.Assertions.assertThat(workspaceService.readFile(alice.getId(), ws, "notes.txt"))
                .isEqualTo("contenu mis à jour");
        // L'échange est persisté (user + assistant).
        org.assertj.core.api.Assertions.assertThat(
                atelierMessageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(ws, alice.getId())).hasSize(2);
    }

    @Test
    void historyReturnsPastMessages() throws Exception {
        UUID ws = createWorkspace(alice, "a.txt", "x");
        stub.enqueueFinal("Bonjour.");
        mockMvc.perform(post("/api/workspaces/" + ws + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"salut\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/workspaces/" + ws + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(2)))
                .andExpect(jsonPath("$[0].role", is("USER")))
                .andExpect(jsonPath("$[1].role", is("ASSISTANT")));
    }

    @Test
    void whatTheTurnCostIsReturnedAndSurvivesInTheHistory() throws Exception {
        // F-39 / SF-39-15 : la boucle maison ne relayait aucune consommation — l'acquis §4 n°6
        // (« coût du tour affiché ») ne valait donc que pour le moteur hébergé, c'est-à-dire pas
        // pour celui qui exécute réellement.
        UUID ws = createWorkspace(alice, "a.txt", "x");
        stub.enqueueFinal("Bonjour.");

        mockMvc.perform(post("/api/workspaces/" + ws + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"salut\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputTokens", is(5)))
                .andExpect(jsonPath("$.outputTokens", is(5)))
                .andExpect(jsonPath("$.budgetReached", is(false)));

        // Le relevé se range dans la colonne d'affichage existante (D-L8-6) : au rechargement, le
        // coût du tour est encore là — et le motif de son arrêt avec lui.
        mockMvc.perform(get("/api/workspaces/" + ws + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].terminal.inputTokens", is(5)))
                .andExpect(jsonPath("$[1].terminal.outputTokens", is(5)))
                .andExpect(jsonPath("$[1].terminal.budgetReached", is(false)))
                .andExpect(jsonPath("$[1].terminal.blocks.length()", is(0)));
    }

    @Test
    void toolTrajectoryIsRememberedForReplayButNeverExposedByTheHistory() throws Exception {
        // SF-39-03 : la trajectoire est une donnée de REJEU. Elle doit être en base pour que le tour
        // suivant ne refasse pas le travail, et absente de la réponse d'historique — l'écran a déjà
        // sa transcription (`terminal`).
        UUID ws = createWorkspace(alice, "notes.txt", "contenu initial");
        stub.enqueueToolCall("read_file", "path", "notes.txt");
        stub.enqueueFinal("J'ai lu notes.txt.");

        mockMvc.perform(post("/api/workspaces/" + ws + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"lis notes.txt\"}"))
                .andExpect(status().isOk());

        String trace = atelierMessageRepository
                .findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(ws, alice.getId())
                .stream().filter(m -> "ASSISTANT".equals(m.getRole()))
                .findFirst().orElseThrow().getToolTrace();
        org.assertj.core.api.Assertions.assertThat(trace).contains("read_file");

        mockMvc.perform(get("/api/workspaces/" + ws + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].toolTrace").doesNotExist())
                .andExpect(jsonPath("$[1].tool_trace").doesNotExist());
    }

    @Test
    void resumeSaysNothingToAskOnAFreshProjectAndCountsTheThread() throws Exception {
        // SF-39-04 : par défaut le fil reprend en silence. L'écran n'appelle cette route que pour
        // savoir s'il doit, exceptionnellement, proposer un choix.
        UUID ws = createWorkspace(alice, "a.txt", "x");
        stub.enqueueFinal("Bonjour.");
        mockMvc.perform(post("/api/workspaces/" + ws + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"salut\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/workspaces/" + ws + "/chat/resume").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prompt", is("NONE")))
                .andExpect(jsonPath("$.turns", is(2)))
                .andExpect(jsonPath("$.threadStartedAt").doesNotExist());
    }

    @Test
    void aFreshStartStopsTheReplayWithoutDeletingTheConversation() throws Exception {
        // SF-39-04 (décision D1) : la frontière déplace la MÉMOIRE, pas la conversation.
        UUID ws = createWorkspace(alice, "a.txt", "x");
        stub.enqueueFinal("Bonjour.");
        mockMvc.perform(post("/api/workspaces/" + ws + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"salut\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/workspaces/" + ws + "/chat/restart").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turns", is(0)))
                .andExpect(jsonPath("$.threadStartedAt").exists());

        // Rien n'a été supprimé : l'écran retrouve toute la conversation.
        mockMvc.perform(get("/api/workspaces/" + ws + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(2)));

        // Et le tour suivant ne rejoue rien d'avant la frontière : seul le nouveau message part.
        stub.enqueueFinal("Nouveau sujet.");
        mockMvc.perform(post("/api/workspaces/" + ws + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"on repart\"}"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(stub.lastRequest.messages()).hasSize(1);
    }

    @Test
    void resumeAndRestartAreInvisibleOnAnotherUsersWorkspace() throws Exception {
        UUID ws = createWorkspace(alice, "a.txt", "x");

        mockMvc.perform(get("/api/workspaces/" + ws + "/chat/resume").contextPath("/api")
                        .header("Authorization", bearer(bobToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/workspaces/" + ws + "/chat/restart").contextPath("/api")
                        .header("Authorization", bearer(bobToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aTargetedEditReallyChangesTheFileAndIsAnnouncedAsAWrite() throws Exception {
        // SF-39-06 : changer trois caractères ne doit plus imposer de réémettre le fichier entier —
        // le coût est en tokens de sortie, les plus chers, et une réponse coupée réécrivait un
        // fichier tronqué.
        UUID ws = createWorkspace(alice, "notes.txt", "bonjour monde");
        stub.enqueueToolCall("edit_file", "path", "notes.txt", "old_string", "monde",
                "new_string", "atelier");
        stub.enqueueFinal("J'ai remplacé un mot.");

        mockMvc.perform(post("/api/workspaces/" + ws + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"remplace monde par atelier\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[?(@.type=='write')].path",
                        org.hamcrest.Matchers.hasItem("notes.txt")));

        org.assertj.core.api.Assertions.assertThat(workspaceService.readFile(alice.getId(), ws, "notes.txt"))
                .isEqualTo("bonjour atelier");
    }

    @Test
    void cannotChatOnAnotherUsersWorkspace() throws Exception {
        UUID ws = createWorkspace(alice, "a.txt", "x");
        stub.enqueueFinal("ne devrait pas être atteint");
        mockMvc.perform(post("/api/workspaces/" + ws + "/chat").contextPath("/api")
                        .header("Authorization", bearer(bobToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"coucou\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void chatRequiresGoldAccess() throws Exception {
        // SF-28-06 : un utilisateur non-Gold non-admin (essai lazy) est refusé (403 atelier_forbidden),
        // sans que le fournisseur d'agent soit sollicité.
        User charlie = userRepository.save(User.builder().email("charlie@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        String charlieToken = jwtService.generateToken(charlie);
        UUID ws = createWorkspace(charlie, "a.txt", "x");
        stub.enqueueFinal("ne devrait pas être atteint");

        mockMvc.perform(post("/api/workspaces/" + ws + "/chat").contextPath("/api")
                        .header("Authorization", bearer(charlieToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"salut\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("atelier_forbidden")));
        org.assertj.core.api.Assertions.assertThat(stub.lastRequest).isNull();
    }

    // ------------------------------------------------ POST /workspaces/{id}/chat/stream (SF-28-05)

    @Test
    void streamRelaysActionThenDoneAsSse() throws Exception {
        UUID ws = createWorkspace(alice, "notes.txt", "contenu initial");
        stub.enqueueToolCall("read_file", "path", "notes.txt");
        stub.enqueueFinal("J'ai lu notes.txt.");

        var result = mockMvc.perform(post("/api/workspaces/" + ws + "/chat/stream").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"lis notes.txt\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("event:action")   // étape d'action relayée
                .contains("read")            // type d'action
                .contains("notes.txt")       // chemin
                .contains("event:done")      // fin de flux
                .contains("J'ai lu notes.txt.");
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentType())
                .contains("text/event-stream");
    }

    @Test
    void streamOnAnotherUsersWorkspaceEmitsErrorInStreamNotHttp406() throws Exception {
        UUID ws = createWorkspace(alice, "a.txt", "x");
        stub.enqueueFinal("ne devrait pas être atteint");

        var result = mockMvc.perform(post("/api/workspaces/" + ws + "/chat/stream").contextPath("/api")
                        .header("Authorization", bearer(bobToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"coucou\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();

        // L'isolation est émise DANS le flux (jamais un 406/404 via l'@ExceptionHandler JSON).
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .contains("event:error")
                .contains("workspace_not_found");
        // Le fournisseur n'a jamais été sollicité (aucun tour joué).
        org.assertj.core.api.Assertions.assertThat(stub.lastRequest).isNull();
    }

    @Test
    void pathTraversalInToolCallIsRefusedWithoutTouchingOutside() throws Exception {
        UUID ws = createWorkspace(alice, "a.txt", "x");
        // L'agent tente d'écrire hors du workspace : l'outil renvoie une erreur, la boucle continue.
        stub.enqueueToolCall("write_file", "path", "../evil.txt", "content", "pwned");
        stub.enqueueFinal("Je ne peux pas écrire hors du projet.");

        mockMvc.perform(post("/api/workspaces/" + ws + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"écris ../evil.txt\"}"))
                .andExpect(status().isOk());

        // Aucune fuite : le fichier de traversée n'apparaît pas dans l'arborescence.
        org.assertj.core.api.Assertions.assertThat(workspaceService.tree(alice.getId(), ws))
                .noneMatch(p -> p.contains("evil"));
    }

    // ------------------------------------------- interruption du tour (F-38 / SF-38-07)

    @Test
    void interruptOnOwnWorkspaceReturns204() throws Exception {
        UUID ws = createWorkspace(alice, "a.txt", "x");

        mockMvc.perform(post("/api/workspaces/" + ws + "/chat/interrupt").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isNoContent());
    }

    @Test
    void interruptIsIdempotentWhenNothingIsRunning() throws Exception {
        UUID ws = createWorkspace(alice, "a.txt", "x");

        // Interrompre deux fois de suite alors que rien ne tourne n'est pas une erreur : la marque
        // est de toute façon effacée à l'ouverture du prochain tour.
        mockMvc.perform(post("/api/workspaces/" + ws + "/chat/interrupt").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/workspaces/" + ws + "/chat/interrupt").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isNoContent());
    }

    @Test
    void interruptOnAnotherUsersWorkspaceIsRefused() throws Exception {
        UUID ws = createWorkspace(alice, "a.txt", "x");

        // Isolation user_id : le projet d'autrui n'existe pas pour Bob.
        mockMvc.perform(post("/api/workspaces/" + ws + "/chat/interrupt").contextPath("/api")
                        .header("Authorization", bearer(bobToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void interruptWithoutTokenIsRejected() throws Exception {
        UUID ws = createWorkspace(alice, "a.txt", "x");

        mockMvc.perform(post("/api/workspaces/" + ws + "/chat/interrupt").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void truncatedTurnAnswers200WithAnExplicitMessageAndTouchesNoFile() throws Exception {
        UUID ws = createWorkspace(alice, "notes.txt", "contenu initial");
        // Le fournisseur a coupé la réponse au plafond de sortie, en plein `write_file` (SF-28-18).
        stub.enqueueTruncated("Je vais mettre à jour notes.txt.", "write_file");

        mockMvc.perform(post("/api/workspaces/" + ws + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"réécris entièrement notes.txt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply", containsString("rien n'a été exécuté")))
                .andExpect(jsonPath("$.actions", org.hamcrest.Matchers.hasSize(0)));

        // Le fichier est intact : un contenu tronqué n'est jamais écrit.
        org.assertj.core.api.Assertions.assertThat(workspaceService.readFile(alice.getId(), ws, "notes.txt"))
                .isEqualTo("contenu initial");
    }

    @Test
    void aTruncatedTurnDoesNotCondemnTheProjectForTheNextMessages() throws Exception {
        UUID ws = createWorkspace(alice, "notes.txt", "contenu initial");
        stub.enqueueTruncated("", "write_file");

        mockMvc.perform(post("/api/workspaces/" + ws + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"premier message\"}"))
                .andExpect(status().isOk());

        // Le message suivant doit passer : c'est exactement ce qui échouait avant SF-28-18, le
        // fournisseur refusant de rejouer un message assistant vide.
        stub.enqueueFinal("Voilà.");
        mockMvc.perform(post("/api/workspaces/" + ws + "/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"deuxième message\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply", containsString("Voilà")));

        // Aucun message vide n'a été écrit dans l'historique du projet.
        org.assertj.core.api.Assertions.assertThat(
                        atelierMessageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(ws, alice.getId()))
                .allSatisfy(m -> org.assertj.core.api.Assertions.assertThat(m.getContent()).isNotBlank());
    }
}
