package fr.claudegateway.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.docx.DocxFixtures;
import fr.claudegateway.rag.ChunkRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * La quatrième voie, de bout en bout (F-86 / SF-86-02) : un {@code .docx} déposé sur
 * {@code POST /api/documents} entre, son texte est extrait <b>sur la machine</b>, et ce qui n'est
 * pas un document Word est refusé sur son <b>contenu</b>.
 *
 * <p>Le fournisseur OCR du profil de test ({@code StubOcrProvider}) reste en place et ne doit
 * jamais être sollicité par ce chemin : le texte obtenu vient de l'extraction Word, pas du bouchon
 * — c'est ce que vérifie l'assertion sur le tableau en Markdown, que le bouchon ne saurait pas
 * produire.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocxDocumentApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private JwtService jwtService;

    private User alice;
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() {
        chunkRepository.deleteAll();
        documentRepository.deleteAll();
        userRepository.deleteAll();
        alice = userRepository.save(User.builder()
                .email("alice@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        User bob = userRepository.save(User.builder()
                .email("bob@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(bob);
    }

    private static MockMultipartFile docx(String filename, byte[] content) {
        return new MockMultipartFile("file", filename, OcrProperties.DOCX_MEDIA_TYPE, content);
    }

    /** Le vrai document de référence, celui que SF-86-01 a versionné. */
    private static byte[] realDocument() throws IOException {
        try (InputStream stream = DocxDocumentApiIntegrationTest.class
                .getResourceAsStream("/docx/contrat-reel.docx")) {
            assertThat(stream).isNotNull();
            return stream.readAllBytes();
        }
    }

    @Test
    void aRealWordDocumentIsAcceptedAndExtractedOnTheMachine() throws Exception {
        mockMvc.perform(multipart("/api/documents").file(docx("contrat.docx", realDocument()))
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename", is("contrat.docx")))
                .andExpect(jsonPath("$.mediaType", is(OcrProperties.DOCX_MEDIA_TYPE)))
                .andExpect(jsonPath("$.status", is("EXTRACTED")));

        Document saved = documentRepository.findAll().get(0);
        assertThat(saved.getUserId()).isEqualTo(alice.getId());
        assertThat(saved.getOcrMode()).isEqualTo(OcrMode.LOCAL);
        // Aucun job fournisseur, aucun brut Textract : rien n'est sorti de la machine.
        assertThat(saved.getProviderJobId()).isNull();
        assertThat(saved.getTextractRaw()).isNull();
        // Le tableau en Markdown : un texte que le bouchon OCR ne saurait pas inventer.
        assertThat(saved.getExtractedText())
                .contains("| Prestation | Prix |")
                .contains("| Audit | 1200 EUR |")
                .contains("[1] Resiliable avec un preavis de trois mois.")
                .startsWith("[1 image de ce document n'a pas été lue.]")
                .doesNotContain("EN-TETE-CONFIDENTIEL")
                .doesNotContain("PIED-DE-PAGE-REPETE");
    }

    @Test
    void theExtractedTextIsReadableOnTheDetailEndpointAndStaysIsolated() throws Exception {
        mockMvc.perform(multipart("/api/documents").file(docx("contrat.docx", realDocument()))
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isCreated());
        String id = documentRepository.findAll().get(0).getId().toString();

        mockMvc.perform(get("/api/documents/" + id).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extractedText", containsString("| Prestation | Prix |")));

        // Isolation user_id : le document d'Alice n'existe pas pour Bob.
        mockMvc.perform(get("/api/documents/" + id).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/documents").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(0)));
    }

    @Test
    void aWordDocumentDeclaredAsOctetStreamIsAcceptedOnItsContent() throws Exception {
        MockMultipartFile undeclared = new MockMultipartFile("file", "contrat.docx",
                "application/octet-stream",
                DocxFixtures.docx(DocxFixtures.paragraph("Reconnu sur son contenu.")));

        mockMvc.perform(multipart("/api/documents").file(undeclared).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mediaType", is(OcrProperties.DOCX_MEDIA_TYPE)))
                .andExpect(jsonPath("$.status", is("EXTRACTED")));
    }

    // ---------------------------------------------------------------- refus

    @Test
    void aCorruptWordDocumentIsRefusedWithAUsefulMessageAndNothingIsStored() throws Exception {
        byte[] whole = DocxFixtures.docx(DocxFixtures.paragraph("Un document coupé en deux."));
        byte[] truncated = java.util.Arrays.copyOf(whole, whole.length / 2);

        mockMvc.perform(multipart("/api/documents").file(docx("contrat.docx", truncated))
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("invalid_document")))
                .andExpect(jsonPath("$.message", containsString("corrompu ou incomplet")))
                // Jamais une trace technique dans la réponse.
                .andExpect(jsonPath("$.message", not(containsString("Exception"))))
                .andExpect(jsonPath("$.message", not(containsString("java."))))
                .andExpect(jsonPath("$.message", not(containsString("fr.claudegateway"))));

        assertThat(documentRepository.findAll()).isEmpty();
    }

    @Test
    void aFileRenamedAsDocxIsRefusedOnItsContentNotOnItsName() throws Exception {
        MockMultipartFile renamed = docx("faux-contrat.docx",
                "Un simple fichier texte, renommé.".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/documents").file(renamed).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("n'est pas une archive Office")));

        assertThat(documentRepository.findAll()).isEmpty();
    }

    @Test
    void aZipRenamedAsDocxIsRefusedBecauseItHoldsNoWordDocument() throws Exception {
        MockMultipartFile zip = docx("archive.docx",
                DocxFixtures.archive(Map.of("notes.txt", "rien de Word ici")));

        mockMvc.perform(multipart("/api/documents").file(zip).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("pas un document Word (.docx) valide")));

        assertThat(documentRepository.findAll()).isEmpty();
    }

    @Test
    void aZipBombIsRefusedAndTheServiceKeepsAnswering() throws Exception {
        MockMultipartFile bomb = docx("bombe.docx", DocxFixtures.archiveWithManyEntries(5000));

        mockMvc.perform(multipart("/api/documents").file(bomb).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("trop volumineux une fois décompressé")));

        assertThat(documentRepository.findAll()).isEmpty();

        // Le service répond encore : la bombe n'a pas emporté le pod avec elle.
        mockMvc.perform(multipart("/api/documents").file(docx("contrat.docx", realDocument()))
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isCreated());
    }

    @Test
    void anXmlExternalEntityIsRefusedAndTheTargetFileIsNeverRead() throws Exception {
        MockMultipartFile hostile = docx("piege.docx", DocxFixtures.archive(Map.of(
                "word/document.xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE w:document [ <!ENTITY xxe SYSTEM "file:///etc/hostname"> ]>
                        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                          <w:body><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:body>
                        </w:document>
                        """)));

        mockMvc.perform(multipart("/api/documents").file(hostile).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("contenu interne est corrompu")));

        assertThat(documentRepository.findAll()).isEmpty();
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(multipart("/api/documents").file(docx("contrat.docx", realDocument()))
                        .contextPath("/api"))
                .andExpect(status().isUnauthorized());
        assertThat(documentRepository.findAll()).isEmpty();
    }
}
