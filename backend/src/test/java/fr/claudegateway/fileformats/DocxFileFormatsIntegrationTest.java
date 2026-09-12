package fr.claudegateway.fileformats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
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
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * <b>Le test qui démontre qu'aucune ligne de frontend n'était nécessaire</b> (F-86 / SF-86-02).
 *
 * <p>{@code FileFormatsApiIntegrationTest} prouve le mécanisme en général, avec une configuration
 * de test qui ajoute un type fictif. Celui-ci le prouve <b>pour F-86, avec la configuration réelle
 * du produit</b> : aucune propriété n'est surchargée ici. Le type {@code .docx} apparaît dans
 * {@code GET /api/file-formats} parce qu'il a été ajouté à {@code app.ocr.allowed-types}, et
 * l'écran en dérive son attribut {@code accept} et sa phrase « formats acceptés » (SF-85-01).
 *
 * <p>C'est aussi le garde-fou inverse : si quelqu'un retirait un jour le type de la liste blanche
 * en croyant ne toucher qu'au serveur, ce test tomberait — et dirait que l'écran vient de changer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocxFileFormatsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private OcrProperties ocrProperties;

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
    void wordIsPublishedToTheScreenByTheDefaultProductConfiguration() throws Exception {
        mockMvc.perform(get("/api/file-formats").contextPath("/api")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents.mediaTypes", hasItem(OcrProperties.DOCX_MEDIA_TYPE)));
    }

    @Test
    void theSameListIsTheOneValidationUsesToRefuse() {
        // Une seule liste, deux vues : ce que l'écran propose est exactement ce que le serveur
        // accepte. Publier un type sans l'accepter serait le défaut que F-85 existe pour empêcher.
        assertThat(ocrProperties.normalizedAllowedTypes()).contains(OcrProperties.DOCX_MEDIA_TYPE);
        assertThat(ocrProperties.allowedTypeSet()).contains(OcrProperties.DOCX_MEDIA_TYPE);
        assertThat(ocrProperties.allowedTypeSet())
                .containsExactlyInAnyOrderElementsOf(ocrProperties.normalizedAllowedTypes());
    }

    @Test
    void wordIsRoutedToTheLocalPathAndToNoOcrAtAll() {
        assertThat(ocrProperties.isLocalType(OcrProperties.DOCX_MEDIA_TYPE)).isTrue();
        assertThat(ocrProperties.isSyncType(OcrProperties.DOCX_MEDIA_TYPE)).isFalse();
    }
}
