package fr.claudegateway.jarvis.pieces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceSource;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.images.GeneratedImage;
import fr.claudegateway.images.GeneratedImageRepository;
import fr.claudegateway.images.GeneratedImageStatus;
import fr.claudegateway.images.GeneratedImageStore;
import fr.claudegateway.images.ImageSpace;
import fr.claudegateway.mail.ClientEmail;
import fr.claudegateway.mail.ClientEmailRepository;
import fr.claudegateway.mail.ClientEmailStatus;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-237 / SF-237-02 — les contenus manquants : vignette réduite, corps texte d'un courriel (jamais le HTML), et
 * l'isolation A/B (404 pour un autre compte, comme pour une pièce inexistante).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisPieceContentApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private GeneratedImageRepository images;
    @Autowired private GeneratedImageStore imageStore;
    @Autowired private ClientEmailRepository emails;
    @Autowired private JwtService jwt;

    private String aliceToken;
    private String bobToken;
    private UUID aliceSubject;
    private UUID imageId;
    private UUID mailId;

    private User user(String prefix) {
        return users.save(User.builder().email(prefix + "-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).createdAt(OffsetDateTime.now()).build());
    }

    @BeforeEach
    void seed() throws Exception {
        User alice = user("content-alice");
        User bob = user("content-bob");
        aliceToken = jwt.generateToken(alice);
        bobToken = jwt.generateToken(bob);
        UUID a = alice.getId();
        aliceSubject = workspaces.save(Workspace.builder().userId(a).name("Edenred").projectPath("p-" + UUID.randomUUID())
                .source(WorkspaceSource.ARCHIVE).executionTarget(WorkspaceExecutionTarget.SANDBOX).build()).getId();
        GeneratedImage image = images.save(GeneratedImage.builder().userId(a).space(ImageSpace.FORGE)
                .workspaceId(aliceSubject).prompt("Un portail").size("1024x1024").status(GeneratedImageStatus.READY)
                .build());
        imageId = image.getId();
        imageStore.putImage(a, imageId, PieceIdAndThumbnailsTest.png(1024, 512));
        RunnerHost host = hosts.save(RunnerHost.builder().userId(a).name("pc").rootName("dev").os("linux")
                .shell("posix").elevated(false).lastSeenAt(OffsetDateTime.now()).build());
        mailId = emails.save(ClientEmail.builder().userId(a).hostId(host.getId()).workspaceId(aliceSubject)
                .kind(ClientEmail.Kind.AGENT).clientName("Edenred").recipient("zahi@edenred.fr")
                .recipientVerified(true).subject("Portail rétabli").sizeBytes(10).attachmentCount(0)
                .bodyText("Bonjour Zahi,\nle portail répond.").bodyHtml("<script>alert(1)</script>")
                .status(ClientEmailStatus.SENT).attempts(1).sentAt(OffsetDateTime.now())
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build()).getId();
    }

    private ResultActions call(String token, String path) throws Exception {
        return mockMvc.perform(get("/api/jarvis/pieces/" + path).contextPath("/api")
                .header("Authorization", "Bearer " + token));
    }

    @Test
    @DisplayName("vignette PNG réduite à 320 px pour son propriétaire ; l'index la désigne")
    void thumbnail() throws Exception {
        byte[] png = call(aliceToken, "img:" + imageId + "/thumb")
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage read = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(read.getWidth()).isEqualTo(320);
        mockMvc.perform(get("/api/jarvis/pieces?subjectId=" + aliceSubject + "&kind=image").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.pieces[0].thumb").value("/jarvis/pieces/img:" + imageId + "/thumb"));
    }

    @Test
    @DisplayName("courriel : le corps TEXTE seulement, jamais le HTML")
    void mailText() throws Exception {
        call(aliceToken, "mail:" + mailId + "/text")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string("Bonjour Zahi,\nle portail répond."));
        mockMvc.perform(get("/api/jarvis/pieces?subjectId=" + aliceSubject + "&category=preuve").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.pieces[0].open").value("/jarvis/pieces/mail:" + mailId + "/text"))
                .andExpect(jsonPath("$.pieces[0].caption")
                        .value("« Portail rétabli » à zahi@edenred.fr — Bonjour Zahi, le portail répond."));
    }

    @Test
    @DisplayName("ISOLATION A/B : la pièce d'un autre compte est introuvable, comme une pièce inexistante")
    void isolation() throws Exception {
        call(bobToken, "img:" + imageId + "/thumb").andExpect(status().isNotFound());
        call(bobToken, "mail:" + mailId + "/text").andExpect(status().isNotFound());
        call(bobToken, "req:" + UUID.randomUUID() + "/attachments/1").andExpect(status().isNotFound());
        call(aliceToken, "img:" + UUID.randomUUID() + "/thumb").andExpect(status().isNotFound());
        call(aliceToken, "dep:" + UUID.randomUUID() + "/thumb").andExpect(status().isNotFound());
        call(aliceToken, "pas-un-identifiant/thumb").andExpect(status().isNotFound());
        call(aliceToken, "img:" + imageId + "/text").andExpect(status().isNotFound());
        mockMvc.perform(get("/api/jarvis/pieces/img:" + imageId + "/thumb").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }
}
