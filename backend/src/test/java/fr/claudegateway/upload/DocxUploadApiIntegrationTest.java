package fr.claudegateway.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ProviderFileReference;
import fr.claudegateway.ai.ProviderFileUpload;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.docx.DocxFixtures;
import fr.claudegateway.ocr.OcrProperties;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * La pièce jointe Word, de bout en bout (F-86 / SF-86-03).
 *
 * <p>Le fournisseur est un bouchon qui <b>retient ce qu'on lui a envoyé</b> : c'est lui qui permet
 * de vérifier le point central de cette subfeature — ce qui part est du <b>texte</b>, jamais le zip,
 * parce qu'un bloc {@code document} ne sait pas lire un {@code .docx}. Et pour un fichier illisible,
 * il doit n'avoir <b>rien</b> reçu du tout.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocxUploadApiIntegrationTest {

    /** Bouchon de fournisseur : retient le dernier envoi, n'appelle jamais Anthropic. */
    static class CapturingAIProvider implements AIProvider {
        volatile ProviderFileUpload lastUpload;

        @Override
        public ChatCompletionResult complete(ChatCompletionRequest request) {
            return new ChatCompletionResult("ok", request.model(), 1, 1);
        }

        @Override
        public ProviderFileReference uploadFile(ProviderFileUpload upload) {
            this.lastUpload = upload;
            return new ProviderFileReference("file_stub_docx");
        }
    }

    @TestConfiguration
    static class CapturingProviderConfig {
        @Bean
        @Primary
        CapturingAIProvider capturingAIProvider() {
            return new CapturingAIProvider();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UploadedFileRepository uploadedFileRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private CapturingAIProvider provider;

    private User alice;
    private String aliceToken;

    @BeforeEach
    void setUp() {
        uploadedFileRepository.deleteAll();
        userRepository.deleteAll();
        provider.lastUpload = null;
        alice = userRepository.save(User.builder()
                .email("alice@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);
    }

    private static MockMultipartFile docx(String filename, byte[] content) {
        return new MockMultipartFile("file", filename, OcrProperties.DOCX_MEDIA_TYPE, content);
    }

    private static byte[] realDocument() throws IOException {
        try (InputStream stream = DocxUploadApiIntegrationTest.class
                .getResourceAsStream("/docx/contrat-reel.docx")) {
            assertThat(stream).isNotNull();
            return stream.readAllBytes();
        }
    }

    @Test
    void aWordAttachmentTravelsAsTextWhileItsMetadataKeepsItsOwnIdentity() throws Exception {
        byte[] original = realDocument();

        mockMvc.perform(multipart("/api/upload").file(docx("contrat.docx", original)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                // Les métadonnées décrivent l'attachement de L'UTILISATEUR : il a joint un Word, et
                // c'est un Word qu'il relira dans sa conversation.
                .andExpect(jsonPath("$.filename", is("contrat.docx")))
                .andExpect(jsonPath("$.mediaType", is(OcrProperties.DOCX_MEDIA_TYPE)))
                .andExpect(jsonPath("$.sizeBytes", is(original.length)));

        // Ce qui est PARTI, lui, est du texte : le fournisseur ne lit pas les .docx.
        assertThat(provider.lastUpload).isNotNull();
        assertThat(provider.lastUpload.mediaType()).isEqualTo("text/plain");
        assertThat(provider.lastUpload.filename()).isEqualTo("contrat.docx.txt");
        assertThat(provider.lastUpload.content()).isNotEqualTo(original);
        String sent = new String(provider.lastUpload.content(), StandardCharsets.UTF_8);
        assertThat(sent)
                .contains("Contrat de prestation de services.")
                .contains("| Prestation | Prix |")
                .contains("[1] Resiliable avec un preavis de trois mois.")
                .startsWith("[1 image de ce document n'a pas été lue.]")
                .doesNotContain("EN-TETE-CONFIDENTIEL");

        UploadedFile saved = uploadedFileRepository.findAll().get(0);
        // Isolation : le user_id vient du contexte de sécurité, jamais d'un paramètre client.
        assertThat(saved.getUserId()).isEqualTo(alice.getId());
        assertThat(saved.getProviderFileId()).isEqualTo("file_stub_docx");
    }

    @Test
    void anotherTypeStillTravelsUntouched() throws Exception {
        byte[] pdfBytes = { '%', 'P', 'D', 'F', 1, 2, 3, 4 };

        mockMvc.perform(multipart("/api/upload")
                        .file(new MockMultipartFile("file", "rapport.pdf", "application/pdf", pdfBytes))
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());

        assertThat(provider.lastUpload.mediaType()).isEqualTo("application/pdf");
        assertThat(provider.lastUpload.filename()).isEqualTo("rapport.pdf");
        assertThat(provider.lastUpload.content()).isEqualTo(pdfBytes);
    }

    @Test
    void aCorruptWordAttachmentIsRefusedBeforeReachingTheProvider() throws Exception {
        byte[] whole = DocxFixtures.docx(DocxFixtures.paragraph("Un document coupé en deux."));

        mockMvc.perform(multipart("/api/upload")
                        .file(docx("contrat.docx", Arrays.copyOf(whole, whole.length / 2)))
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("invalid_document")))
                .andExpect(jsonPath("$.message", containsString("corrompu ou incomplet")))
                .andExpect(jsonPath("$.message", not(containsString("Exception"))))
                .andExpect(jsonPath("$.message", not(containsString("java."))));

        assertThat(provider.lastUpload).as("rien ne doit atteindre le fournisseur").isNull();
        assertThat(uploadedFileRepository.findAll()).isEmpty();
    }

    @Test
    void aFileRenamedAsDocxIsRefusedOnItsContent() throws Exception {
        mockMvc.perform(multipart("/api/upload")
                        .file(docx("faux.docx", "texte renommé".getBytes(StandardCharsets.UTF_8)))
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("n'est pas une archive Office")));

        assertThat(provider.lastUpload).isNull();
        assertThat(uploadedFileRepository.findAll()).isEmpty();
    }

    @Test
    void aZipBombIsRefusedBeforeReachingTheProvider() throws Exception {
        mockMvc.perform(multipart("/api/upload")
                        .file(docx("bombe.docx", DocxFixtures.archiveWithManyEntries(5000)))
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("trop volumineux une fois décompressé")));

        assertThat(provider.lastUpload).isNull();
        assertThat(uploadedFileRepository.findAll()).isEmpty();
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(multipart("/api/upload").file(docx("contrat.docx", realDocument()))
                        .contextPath("/api"))
                .andExpect(status().isUnauthorized());
        assertThat(provider.lastUpload).isNull();
        assertThat(uploadedFileRepository.findAll()).isEmpty();
    }
}
