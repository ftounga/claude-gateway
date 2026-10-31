package fr.claudegateway.atelier.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

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
 * F-228 / SF-228-01 — l'aperçu d'une pièce : servie seulement si elle est inscrite pour ce compte et ce terminal ;
 * le type vient de l'extension ; un HTML n'est jamais servi comme page ; Bob → 404.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DepositContentApiIntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13};

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private AtelierDepositedFileRepository deposits;
    @Autowired private JwtService jwtService;

    private String aliceToken;
    private String bobToken;
    private String workspaceId;

    @BeforeEach
    void setUp() throws Exception {
        deposits.deleteAll();
        workspaceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        aliceToken = tokenFor("alice-apercu@ex.com");
        bobToken = tokenFor("bob-apercu@ex.com");
        workspaceId = createWorkspace(aliceToken);
    }

    @Test
    @DisplayName("une capture déposée se relit telle quelle, en image, sans exécution possible")
    void servesTheDepositedImage() throws Exception {
        String depositId = deposit("capture.png", "image/png", PNG);
        byte[] body = mockMvc.perform(get(url(depositId)).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.startsWith("sandbox")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(body).isEqualTo(PNG);
    }

    @Test
    @DisplayName("un HTML n'est jamais servi comme page ; inconnu → 404 ; Bob → 404")
    void neverHtmlAndIsolated() throws Exception {
        String html = deposit("page.html", "text/html", "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(get(url(html)).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"));
        mockMvc.perform(get(url(UUID.randomUUID().toString())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(url(html)).contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("le type suit l'extension, en minuscules ; l'inconnu se télécharge")
    void typesByExtension() {
        assertThat(DepositContentService.typeOf(".atelier/entrees/Rapport.PDF")).isEqualTo("application/pdf");
        assertThat(DepositContentService.typeOf("entrees/budget.xlsx"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(DepositContentService.typeOf("entrees/schema.svg")).isEqualTo("image/svg+xml");
        assertThat(DepositContentService.typeOf("entrees/note.m4a")).isEqualTo("audio/mp4");
        assertThat(DepositContentService.typeOf("entrees/sans-extension")).isEqualTo("application/octet-stream");
        assertThat(DepositContentService.typeOf("entrees/x.htm")).isEqualTo("application/octet-stream");
        assertThat(DepositContentService.nameOf(".atelier/entrees/capture.png")).isEqualTo("capture.png");
    }

    @Test
    @DisplayName("SF-228-04 : le téléchargement rend les octets à enregistrer, jamais à ouvrir ; inconnu et Bob → 404")
    void downloadsAsAttachmentAndIsolated() throws Exception {
        String html = deposit("page.html", "text/html", "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));
        byte[] body = mockMvc.perform(get(downloadUrl(html)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("page.html")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("<script>alert(1)</script>");
        mockMvc.perform(get(downloadUrl(UUID.randomUUID().toString())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(downloadUrl(html)).contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }

    // ---- outillage ----

    private String downloadUrl(String depositId) {
        return "/api/workspaces/" + workspaceId + "/deposits/" + depositId + "/download";
    }

    private String url(String depositId) {
        return "/api/workspaces/" + workspaceId + "/deposits/" + depositId + "/content";
    }

    private String deposit(String name, String type, byte[] content) throws Exception {
        mockMvc.perform(multipart("/api/workspaces/" + workspaceId + "/deposit")
                        .file(new MockMultipartFile("files", name, type, content)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
        return deposits.findAll().stream().filter(d -> d.getPath().endsWith(name)).findFirst().orElseThrow()
                .getId().toString();
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
                        .param("name", "Apercu")
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
}
