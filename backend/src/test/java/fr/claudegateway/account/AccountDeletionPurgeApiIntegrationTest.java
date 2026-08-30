package fr.claudegateway.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.ocr.Document;
import fr.claudegateway.ocr.DocumentRepository;
import fr.claudegateway.ocr.DocumentStatus;
import fr.claudegateway.ocr.OcrMode;
import fr.claudegateway.rag.Chunk;
import fr.claudegateway.rag.ChunkRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Suppression de compte — purge complète (F-11 / SF-11-03). Workspaces, messages d'Atelier,
 * documents OCR et chunks d'embeddings survivaient au compte : ce test les fait tomber, et prouve
 * qu'un second utilisateur ne perd rien au passage.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountDeletionPurgeApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private AtelierMessageRepository atelierMessageRepository;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private ChunkRepository chunkRepository;
    @Autowired
    private JwtService jwtService;

    private User owner;
    private String ownerJwt;
    private User other;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(user("purge-owner-" + UUID.randomUUID() + "@example.com"));
        ownerJwt = jwtService.generateToken(owner);
        seed(owner.getId());

        other = userRepository.save(user("purge-other-" + UUID.randomUUID() + "@example.com"));
        seed(other.getId());
    }

    @Test
    void deletingTheAccountPurgesWorkspacesMessagesDocumentsAndChunks() throws Exception {
        mockMvc.perform(delete("/api/account").contextPath("/api")
                        .header("Authorization", "Bearer " + ownerJwt))
                .andExpect(status().isOk());

        assertThat(workspaceRepository.findByUserIdOrderByCreatedAtDesc(owner.getId())).isEmpty();
        assertThat(documentRepository.findByUserIdOrderByCreatedAtDesc(owner.getId())).isEmpty();
        assertThat(atelierMessageRepository.findAll())
                .as("aucun message d'Atelier du compte supprimé")
                .noneMatch(m -> m.getUserId().equals(owner.getId()));
        assertThat(chunkRepository.findAll())
                .as("aucun embedding du compte supprimé")
                .noneMatch(c -> c.getUserId().equals(owner.getId()));

        // L'autre utilisateur garde tout : la purge est filtrée sur user_id.
        assertThat(workspaceRepository.findByUserIdOrderByCreatedAtDesc(other.getId())).hasSize(1);
        assertThat(documentRepository.findByUserIdOrderByCreatedAtDesc(other.getId())).hasSize(1);
        assertThat(atelierMessageRepository.findAll())
                .anyMatch(m -> m.getUserId().equals(other.getId()));
        assertThat(chunkRepository.findAll())
                .anyMatch(c -> c.getUserId().equals(other.getId()));
    }

    private void seed(UUID userId) {
        Workspace workspace = workspaceRepository.save(Workspace.builder()
                .userId(userId).name("projet").createdAt(OffsetDateTime.now()).build());
        atelierMessageRepository.save(AtelierMessage.builder()
                .workspaceId(workspace.getId()).userId(userId)
                .role("user").content("bonjour").createdAt(OffsetDateTime.now()).build());
        Document document = documentRepository.save(Document.builder()
                .userId(userId).filename("note.pdf").mediaType("application/pdf").sizeBytes(12L)
                .status(DocumentStatus.INDEXED).ocrMode(OcrMode.SYNC)
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build());
        chunkRepository.save(Chunk.builder()
                .documentId(document.getId()).userId(userId).chunkIndex(0).text("extrait")
                .createdAt(OffsetDateTime.now()).build());
    }

    private static User user(String email) {
        return User.builder().email(email).emailVerified(true).provider(AuthProvider.LOCAL)
                .role(UserRole.USER).createdAt(OffsetDateTime.now()).build();
    }
}
