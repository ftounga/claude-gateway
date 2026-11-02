package fr.claudegateway.jarvis.pieces;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceSource;
import fr.claudegateway.atelier.besoin.AppRequest;
import fr.claudegateway.atelier.besoin.AppRequestRepository;
import fr.claudegateway.atelier.deposit.AtelierDepositedFile;
import fr.claudegateway.atelier.deposit.AtelierDepositedFileRepository;
import fr.claudegateway.atelier.proof.ProofCapture;
import fr.claudegateway.atelier.proof.ProofCaptureRepository;
import fr.claudegateway.atelier.sends.MessageBlockSend;
import fr.claudegateway.atelier.sends.MessageBlockSendRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.images.GeneratedImage;
import fr.claudegateway.images.GeneratedImageRepository;
import fr.claudegateway.images.GeneratedImageStatus;
import fr.claudegateway.images.ImageSpace;
import fr.claudegateway.pages.Page;
import fr.claudegateway.pages.PageRepository;
import fr.claudegateway.pages.PageSpace;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-237 / SF-237-01 — {@code GET /api/jarvis/pieces} : les sources réelles, le rattachement, les filtres, et
 * l'isolation A/B (un second compte ne voit rien et reçoit 404 sur le sujet d'autrui).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisPieceApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private AtelierMessageRepository messages;
    @Autowired private AtelierDepositedFileRepository deposits;
    @Autowired private ProofCaptureRepository proofs;
    @Autowired private MessageBlockSendRepository sends;
    @Autowired private PageRepository pages;
    @Autowired private GeneratedImageRepository images;
    @Autowired private AppRequestRepository requests;
    @Autowired private JwtService jwt;
    @Autowired private fr.claudegateway.jarvis.fastbrain.JarvisFastPieces fastPieces;

    private String aliceToken;
    private String bobToken;
    private UUID aliceSubject;
    private UUID bobSubject;
    private UUID pageId;
    private String proofHex;

    private User user(String prefix) {
        return users.save(User.builder().email(prefix + "-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).createdAt(OffsetDateTime.now()).build());
    }

    private UUID subject(UUID userId, WorkspaceExecutionTarget target) {
        return workspaces.save(Workspace.builder().userId(userId).name("Edenred").projectPath("p-" + UUID.randomUUID())
                .source(WorkspaceSource.ARCHIVE).executionTarget(target).build()).getId();
    }

    @BeforeEach
    void seed() {
        User alice = user("pieces-alice");
        User bob = user("pieces-bob");
        aliceToken = jwt.generateToken(alice);
        bobToken = jwt.generateToken(bob);
        UUID a = alice.getId();
        aliceSubject = subject(a, WorkspaceExecutionTarget.SANDBOX);
        bobSubject = subject(bob.getId(), WorkspaceExecutionTarget.SANDBOX);
        pageId = UUID.randomUUID();
        UUID proofId = UUID.randomUUID();
        proofHex = proofId.toString().replace("-", "");

        AtelierMessage need = messages.save(AtelierMessage.builder().workspaceId(aliceSubject).userId(a).role("USER")
                .content("Corrige l'erreur 502 du portail. Voici la capture [Image #1] de l'erreur.")
                .chantierNumber(3).build());
        messages.save(AtelierMessage.builder().workspaceId(aliceSubject).userId(a).role("ASSISTANT")
                .content("Fait.").chantierNumber(3)
                .toolTrace("[{\"name\":\"page_publish\",\"result\":\"" + pageId + "\"},{\"id\":\"toolu_proof\"}]")
                .build());
        deposits.save(AtelierDepositedFile.builder().userId(a).workspaceId(aliceSubject)
                .path(".atelier/entrees/capture-502.png").sizeBytes(1234).messageId(need.getId()).build());
        requests.save(AppRequest.builder().userId(a).workspaceId(aliceSubject).messageId(need.getId()).kind("BESOIN")
                .title("Portail en 502").description("Le portail rend 502").reformulation("Rétablir le portail")
                .status("LIVRE").createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build());
        proofs.save(ProofCapture.builder().id(proofId).userId(a).workspaceId(aliceSubject).callId("toolu_proof")
                .title("Santé du portail").step("Étape 4").position(0)
                .commandsJson("[{\"command\":\"curl\",\"status\":\"done\",\"exitCode\":0}]")
                .storageKey("preuves/x.png").capturedAt(OffsetDateTime.now()).build());
        sends.save(MessageBlockSend.builder().userId(a).workspaceId(aliceSubject).messageId(need.getId())
                .blockIndex(0).kind(MessageBlockSend.Kind.MAIL).subject("Portail rétabli").recipient("Zahi")
                .excerpt("Bonjour Zahi").sentAt(OffsetDateTime.now()).build());
        pages.save(Page.builder().id(pageId).userId(a).space(PageSpace.FORGE).workspaceId(aliceSubject)
                .title("Rapport 502").description("Ce que montre la page : la cause").currentVersion(2).build());
        images.save(GeneratedImage.builder().userId(a).space(ImageSpace.FORGE).workspaceId(aliceSubject)
                .prompt("Un portail réparé").size("1024x1024").status(GeneratedImageStatus.READY).build());
    }

    private org.springframework.test.web.servlet.ResultActions call(String token, String query) throws Exception {
        return mockMvc.perform(get("/api/jarvis/pieces" + query).contextPath("/api")
                .header("Authorization", "Bearer " + token));
    }

    @Test
    @DisplayName("le propriétaire voit ses pièces, catégorisées, décrites et rattachées au chantier")
    void ownerSeesPieces() throws Exception {
        call(aliceToken, "?subjectId=" + aliceSubject)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(7))
                .andExpect(jsonPath("$.truncated").value(false))
                // le besoin initial ouvre la liste
                .andExpect(jsonPath("$.pieces[0].kind").value("TEXT"))
                .andExpect(jsonPath("$.pieces[0].title").value("Besoin initial du chantier 3"))
                .andExpect(jsonPath("$.pieces[?(@.id =~ /dep:.*/)].caption")
                        .value(hasItem("Voici la capture [Image #1] de l'erreur.")))
                .andExpect(jsonPath("$.pieces[?(@.id =~ /dep:.*/)].kind").value(hasItem("IMAGE")))
                .andExpect(jsonPath("$.pieces[?(@.id =~ /dep:.*/)].chantierLink").value(hasItem("MESSAGE")))
                .andExpect(jsonPath("$.pieces[?(@.id =~ /req:.*/)].captionSource").value(hasItem("REFORMULATION")))
                .andExpect(jsonPath("$.pieces[?(@.id == 'proof:" + proofHex + "')].caption")
                        .value(hasItem("Santé du portail — étape : Étape 4 — 1 commande, réussie")))
                .andExpect(jsonPath("$.pieces[?(@.id == 'proof:" + proofHex + "')].chantier").value(hasItem(3)))
                .andExpect(jsonPath("$.pieces[?(@.id == 'page:" + pageId + "@v2')].chantier").value(hasItem(3)))
                .andExpect(jsonPath("$.pieces[?(@.id =~ /send:.*/)].category").value(hasItem("PREUVE")))
                .andExpect(jsonPath("$.pieces[?(@.id =~ /img:.*/)].captionSource").value(hasItem("PROMPT")))
                .andExpect(jsonPath("$.pieces[*].open").value(not(hasItem(org.hamcrest.Matchers
                        .containsString("amazonaws")))));
    }

    @Test
    @DisplayName("filtres : catégorie, type, chantier, limite tronquée")
    void filters() throws Exception {
        call(aliceToken, "?subjectId=" + aliceSubject + "&category=preuve")
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.pieces[*].category").value(hasItems("PREUVE")));
        call(aliceToken, "?subjectId=" + aliceSubject + "&kind=image")
                .andExpect(jsonPath("$.total").value(3));
        call(aliceToken, "?subjectId=" + aliceSubject + "&chantier=99")
                .andExpect(jsonPath("$.total").value(0));
        call(aliceToken, "?subjectId=" + aliceSubject + "&limit=2")
                .andExpect(jsonPath("$.pieces.length()").value(2))
                .andExpect(jsonPath("$.truncated").value(true));
        call(aliceToken, "?subjectId=" + aliceSubject + "&kind=hologramme").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("ISOLATION A/B : B ne voit rien de A, et le sujet de A lui rend 404")
    void isolation() throws Exception {
        call(bobToken, "?subjectId=" + aliceSubject).andExpect(status().isNotFound());
        call(bobToken, "?subjectId=" + bobSubject).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        call(bobToken, "").andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        call(aliceToken, "").andExpect(jsonPath("$.total").value(7));
        mockMvc.perform(get("/api/jarvis/pieces").contextPath("/api")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("un dépôt sur un poste hors ligne est dit hors ligne, jamais remplacé")
    void hostOffline() throws Exception {
        User carol = user("pieces-carol");
        UUID onHost = subject(carol.getId(), WorkspaceExecutionTarget.RUNNER);
        deposits.save(AtelierDepositedFile.builder().userId(carol.getId()).workspaceId(onHost)
                .path(".atelier/entrees/demo.mp4").sizeBytes(99).build());
        call(jwt.generateToken(carol), "?subjectId=" + onHost)
                .andExpect(jsonPath("$.pieces[0].kind").value("VIDEO"))
                .andExpect(jsonPath("$.pieces[0].available").value("HOST_OFFLINE"));
    }

    @Test
    @DisplayName("SF-237-08 — l'outil pieces du cerveau rapide : ses pièces, jamais celles d'autrui")
    void fastBrainPiecesToolIsIsolated() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        UUID alice = workspaces.findById(aliceSubject).orElseThrow().getUserId();
        UUID bob = workspaces.findById(bobSubject).orElseThrow().getUserId();

        String mine = fastPieces.read(alice, aliceSubject, json.readTree("""
                {"sujet":"","chantier":3,"categorie":"preuves","type":"","recherche":""}"""));
        org.assertj.core.api.Assertions.assertThat(mine).contains("proof:" + proofHex).contains("Santé du portail");

        String theirs = fastPieces.read(bob, bobSubject, json.readTree("{\"sujet\":\"" + aliceSubject + "\"}"));
        org.assertj.core.api.Assertions.assertThat(theirs).isEqualTo("Sujet inconnu.");
        org.assertj.core.api.Assertions.assertThat(fastPieces.block(bob, aliceSubject)).isEmpty();
        org.assertj.core.api.Assertions.assertThat(fastPieces.block(alice, aliceSubject))
                .startsWith("## Pièces").contains("chantier 3 : besoin initial");
    }
}
