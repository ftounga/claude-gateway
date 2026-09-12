package fr.claudegateway.fileformats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.ocr.OcrProperties;
import fr.claudegateway.upload.UploadProperties;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration de {@code GET /api/file-formats} (F-85 / SF-85-01).
 *
 * <p><b>Le test qui empêche l'écran et le serveur de diverger.</b> La configuration de ce test
 * <b>ajoute</b> un type ({@code image/bmp}) aux deux listes blanches. Le test vérifie qu'il
 * apparaît dans la réponse de l'endpoint — donc dans l'attribut {@code accept} du sélecteur, qui
 * en est dérivé — <b>et</b> dans l'ensemble dont la validation se sert pour refuser. Aucune ligne
 * de frontend n'a été touchée pour cela : c'est précisément ce qu'on veut démontrer.
 *
 * <p>Le type ajouté ici ne change <b>aucune</b> liste blanche du produit : il ne vit que dans la
 * configuration de ce test.
 */
@SpringBootTest(properties = {
        "app.ocr.allowed-types=application/pdf,image/png,image/jpeg,image/tiff,image/BMP",
        "app.upload.allowed-types=application/pdf,image/png,image/BMP"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FileFormatsApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private OcrProperties ocrProperties;

    @Autowired
    private UploadProperties uploadProperties;

    private String token;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        User alice = userRepository.save(User.builder()
                .email("alice@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        token = jwtService.generateToken(alice);
    }

    @Test
    void publishesTheServerWhitelistsInConfigurationOrder() throws Exception {
        mockMvc.perform(get("/api/file-formats").contextPath("/api")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents.mediaTypes", contains(
                        "application/pdf", "image/png", "image/jpeg", "image/tiff", "image/bmp")))
                .andExpect(jsonPath("$.attachments.mediaTypes", contains(
                        "application/pdf", "image/png", "image/bmp")))
                .andExpect(jsonPath("$.documents.maxBytes", is((int) ocrProperties.maxBytes())))
                .andExpect(jsonPath("$.attachments.maxBytes", is((int) uploadProperties.maxBytes())));
    }

    @Test
    void aTypeAddedToTheServerWhitelistIsBothPublishedAndAccepted() throws Exception {
        // Publié : l'écran le proposera, sans qu'on ait touché au frontend.
        mockMvc.perform(get("/api/file-formats").contextPath("/api")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents.mediaTypes[4]", is("image/bmp")));

        // Accepté : c'est l'ensemble que la validation interroge pour refuser (DocumentService,
        // UploadService). Les deux vues viennent de la même liste — elles ne peuvent pas diverger.
        assertThat(ocrProperties.allowedTypeSet()).contains("image/bmp");
        assertThat(uploadProperties.allowedTypeSet()).contains("image/bmp");
        assertThat(ocrProperties.allowedTypeSet())
                .containsExactlyInAnyOrderElementsOf(ocrProperties.normalizedAllowedTypes());
        assertThat(uploadProperties.allowedTypeSet())
                .containsExactlyInAnyOrderElementsOf(uploadProperties.normalizedAllowedTypes());
    }

    @Test
    void rejectsAnonymousCallers() throws Exception {
        mockMvc.perform(get("/api/file-formats").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }
}
