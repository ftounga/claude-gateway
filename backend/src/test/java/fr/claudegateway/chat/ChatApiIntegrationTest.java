package fr.claudegateway.chat;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.Executor;

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

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.AIProviderException;
import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration du proxy de chat et de la gestion des conversations sur {@code /api/**} :
 * flux nominal, validations, authentification, isolation {@code user_id}, non-fuite de la clé
 * plateforme et mapping des erreurs fournisseur. Le vrai {@link AIProvider} est remplacé par un
 * stub contrôlable pour ne jamais appeler Anthropic pendant les tests.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatApiIntegrationTest {

    /** Stub de fournisseur IA injecté à la place d'{@code AnthropicProvider} (bean @Primary). */
    static class StubAIProvider implements AIProvider {
        volatile RuntimeException toThrow;
        volatile String reply = "Bonjour, je suis l'assistant.";
        volatile ChatCompletionRequest lastRequest;

        void reset() {
            toThrow = null;
            reply = "Bonjour, je suis l'assistant.";
            lastRequest = null;
        }

        @Override
        public ChatCompletionResult complete(ChatCompletionRequest request) {
            this.lastRequest = request;
            if (toThrow != null) {
                throw toThrow;
            }
            return new ChatCompletionResult(reply, request.model(), 12, 8);
        }

        @Override
        public fr.claudegateway.ai.ProviderFileReference uploadFile(fr.claudegateway.ai.ProviderFileUpload upload) {
            if (toThrow != null) {
                throw toThrow;
            }
            return new fr.claudegateway.ai.ProviderFileReference("file_stub");
        }
    }

    @TestConfiguration
    static class StubProviderConfig {
        @Bean
        @Primary
        StubAIProvider stubAIProvider() {
            return new StubAIProvider();
        }

        /** Exécuteur SSE synchrone : le relais s'exécute inline pour des tests déterministes. */
        @Bean("chatStreamExecutor")
        @Primary
        Executor chatStreamExecutor() {
            return Runnable::run;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private fr.claudegateway.upload.UploadedFileRepository uploadedFileRepository;

    @Autowired
    private fr.claudegateway.ocr.DocumentRepository documentRepository;

    @Autowired
    private MessageLibraryDocumentRepository messageLibraryDocumentRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private StubAIProvider stubAIProvider;

    @Autowired
    private fr.claudegateway.byok.ByokKeyService byokKeyService;

    @Autowired
    private fr.claudegateway.byok.UserApiKeyRepository userApiKeyRepository;

    private User alice;
    private String aliceToken;
    private User bob;
    private String bobToken;

    @BeforeEach
    void setUp() {
        userApiKeyRepository.deleteAll();
        messageLibraryDocumentRepository.deleteAll();
        uploadedFileRepository.deleteAll();
        documentRepository.deleteAll();
        conversationRepository.deleteAll();
        userRepository.deleteAll();
        stubAIProvider.reset();

        alice = userRepository.save(User.builder()
                .email("alice@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);

        bob = userRepository.save(User.builder()
                .email("bob@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        bobToken = jwtService.generateToken(bob);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    // --------------------------------------------------------------------- POST /api/chat

    @Test
    void createsConversationOnFirstMessage() throws Exception {
        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bonjour Claude\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId", notNullValue()))
                .andExpect(jsonPath("$.model", is("claude-opus-4-8")))
                .andExpect(jsonPath("$.message.role", is("ASSISTANT")))
                .andExpect(jsonPath("$.message.content", is("Bonjour, je suis l'assistant.")))
                .andExpect(jsonPath("$.message.model", is("claude-opus-4-8")));
    }

    // --------------------------------------------------------------- POST /api/chat/stream (SF-02-04)

    @Test
    void streamsAssistantReplyAsSse() throws Exception {
        // Exécuteur SSE synchrone (config de test) : le relais s'exécute au retour du contrôleur, la
        // réponse est donc déjà écrite — on lit le corps directement (pas d'asyncDispatch).
        var result = mockMvc.perform(post("/api/chat/stream").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"Bonjour\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("token")                             // événement token (contenu relayé)
                .contains("Bonjour, je suis l'assistant.")     // texte relayé du fournisseur
                .contains("done");                             // événement de fin de flux
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentType())
                .contains("text/event-stream");
    }

    @Test
    void internalAsyncDispatchIsNotDeniedBySecurity() throws Exception {
        // Régression : le re-dispatch ASYNC interne (fin de flux SSE /chat/stream) ne doit pas être
        // refusé par l'AuthorizationFilter — sinon « response already committed » côté serveur. Le
        // JwtAuthenticationFilter ne s'exécute pas sur un dispatch ASYNC ; la règle
        // dispatcherTypeMatchers(ASYNC) le laisse passer (l'autorisation réelle est faite sur REQUEST).
        int status = mockMvc.perform(get("/api/conversations").contextPath("/api")
                        .with(req -> {
                            req.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);
                            return req;
                        }))
                .andReturn().getResponse().getStatus();

        // La sécurité ne refuse plus le dispatch interne (ni 401 ni 403). Avant le correctif : 403.
        org.assertj.core.api.Assertions.assertThat(status)
                .isNotEqualTo(org.springframework.http.HttpStatus.FORBIDDEN.value())
                .isNotEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void streamRejectsUnauthenticatedRequestBeforeOpeningFlux() throws Exception {
        mockMvc.perform(post("/api/chat/stream").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bonjour\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void streamReturns404ForConversationOwnedByAnotherUser() throws Exception {
        Conversation bobConversation = conversationRepository.save(Conversation.builder()
                .userId(bob.getId()).title("Privé de Bob").model("claude-opus-4-8").build());

        // Alice tente de streamer sur la conversation de Bob : 404 au pré-vol, aucun flux ouvert.
        mockMvc.perform(post("/api/chat/stream").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"coucou\",\"conversationId\":\"" + bobConversation.getId() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void usesByokKeyForProviderCallWhenActive() throws Exception {
        // Alice enregistre une clé BYOK (validée par le stub, chiffrée par le cipher local) => mode BYOK.
        byokKeyService.saveKey(alice.getId(), "sk-ant-alice-personal-KEY9");
        stubAIProvider.reset();

        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bonjour\"}"))
                .andExpect(status().isOk());

        // L'appel fournisseur a porté la clé déchiffrée de l'utilisateur (mode BYOK).
        org.assertj.core.api.Assertions.assertThat(stubAIProvider.lastRequest.apiKey())
                .isEqualTo("sk-ant-alice-personal-KEY9");
    }

    @Test
    void usesPlatformKeyWhenUserHasNoByokKey() throws Exception {
        // Bob n'a pas de clé => mode Hosted (apiKey null sur la requête fournisseur).
        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", bearer(bobToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bonjour\"}"))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(stubAIProvider.lastRequest.apiKey()).isNull();
    }

    @Test
    void rejectsBlankMessage() throws Exception {
        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
    }

    @Test
    void rejectsUnknownModel() throws Exception {
        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Salut\",\"model\":\"gpt-4o\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
    }

    @Test
    void rejectsUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Salut\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void attachesOwnedFileAndForwardsToProvider() throws Exception {
        fr.claudegateway.upload.UploadedFile file = uploadedFileRepository.save(
                fr.claudegateway.upload.UploadedFile.builder()
                        .userId(alice.getId()).providerFileId("file_alice")
                        .filename("doc.pdf").mediaType("application/pdf").sizeBytes(4).build());

        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Analyse\",\"attachmentIds\":[\"" + file.getId() + "\"]}"))
                .andExpect(status().isOk());

        // F-25 : la pièce jointe est portée par le message utilisateur (dernier message transmis).
        var messages = stubAIProvider.lastRequest.messages();
        var attachments = messages.get(messages.size() - 1).attachments();
        org.assertj.core.api.Assertions.assertThat(attachments).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(attachments.get(0).providerFileId()).isEqualTo("file_alice");
    }

    @Test
    void keepsAttachmentInContextOnLaterTurns() throws Exception {
        // Cœur de F-25 : un fichier joint au tour 1 doit rester transmis à Claude au tour 2, même si
        // le second message n'a aucune pièce jointe (comportement claude.ai).
        fr.claudegateway.upload.UploadedFile file = uploadedFileRepository.save(
                fr.claudegateway.upload.UploadedFile.builder()
                        .userId(alice.getId()).providerFileId("file_logo")
                        .filename("logo.png").mediaType("image/png").sizeBytes(4).build());

        // Tour 1 : message avec la pièce jointe -> crée la conversation.
        String body = mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Décris cette image\",\"attachmentIds\":[\"" + file.getId() + "\"]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID conversationId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.conversationId"));

        // Tour 2 : nouveau message SANS pièce jointe, dans la même conversation.
        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + conversationId + "\",\"message\":\"Et sa couleur dominante ?\"}"))
                .andExpect(status().isOk());

        // Au tour 2, l'image du tour 1 est TOUJOURS présente dans la requête relayée au fournisseur.
        boolean imagePresent = stubAIProvider.lastRequest.messages().stream()
                .flatMap(m -> m.attachments().stream())
                .anyMatch(a -> "file_logo".equals(a.providerFileId()));
        org.assertj.core.api.Assertions.assertThat(imagePresent)
                .as("la pièce jointe du tour 1 doit rester dans le contexte au tour 2")
                .isTrue();
    }

    @Test
    void cannotAttachAnotherUsersFile() throws Exception {
        fr.claudegateway.upload.UploadedFile bobFile = uploadedFileRepository.save(
                fr.claudegateway.upload.UploadedFile.builder()
                        .userId(bob.getId()).providerFileId("file_bob")
                        .filename("secret.pdf").mediaType("application/pdf").sizeBytes(4).build());

        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Analyse\",\"attachmentIds\":[\"" + bobFile.getId() + "\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("attachment_not_found")));
    }

    // ------------------------------------------------- F-24 : import bibliothèque → conversation

    private fr.claudegateway.ocr.Document saveDocument(UUID userId, String filename,
            fr.claudegateway.ocr.DocumentStatus status, String extractedText) {
        return documentRepository.save(fr.claudegateway.ocr.Document.builder()
                .userId(userId).filename(filename).mediaType("application/pdf").sizeBytes(4)
                .status(status).ocrMode(fr.claudegateway.ocr.OcrMode.SYNC)
                .extractedText(extractedText).build());
    }

    @Test
    void injectsLibraryDocumentTextIntoProviderContext() throws Exception {
        fr.claudegateway.ocr.Document doc = saveDocument(alice.getId(), "cv.pdf",
                fr.claudegateway.ocr.DocumentStatus.EXTRACTED, "Jean Dupont, développeur Java senior.");

        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Résume ce CV\",\"libraryDocumentIds\":[\"" + doc.getId() + "\"]}"))
                .andExpect(status().isOk());

        // Le texte du document est présent dans la requête relayée au fournisseur (préfixé au message
        // qui l'a importé — SF-24-03).
        boolean present = stubAIProvider.lastRequest.messages().stream()
                .anyMatch(m -> m.content().contains("cv.pdf")
                        && m.content().contains("Jean Dupont, développeur Java senior."));
        org.assertj.core.api.Assertions.assertThat(present).isTrue();
    }

    @Test
    void keepsLibraryDocumentInContextOnLaterTurns() throws Exception {
        // Cœur de SF-24-03 : un document importé au tour 1 doit rester dans le contexte au tour 2,
        // même si le second message ne référence aucun document (comportement claude.ai).
        fr.claudegateway.ocr.Document doc = saveDocument(alice.getId(), "contrat.pdf",
                fr.claudegateway.ocr.DocumentStatus.EXTRACTED, "Clause de confidentialité article 7.");

        // Tour 1 : import du document -> crée la conversation.
        String body = mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Résume ce contrat\",\"libraryDocumentIds\":[\"" + doc.getId() + "\"]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID conversationId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.conversationId"));

        // Tour 2 : message SANS libraryDocumentIds, dans la même conversation.
        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + conversationId + "\",\"message\":\"Et l'article 7 ?\"}"))
                .andExpect(status().isOk());

        // Au tour 2, le texte du document du tour 1 est TOUJOURS présent dans la requête fournisseur.
        boolean present = stubAIProvider.lastRequest.messages().stream()
                .anyMatch(m -> m.content().contains("Clause de confidentialité article 7."));
        org.assertj.core.api.Assertions.assertThat(present)
                .as("le document importé au tour 1 doit rester dans le contexte au tour 2")
                .isTrue();
    }

    @Test
    void cannotImportAnotherUsersLibraryDocument() throws Exception {
        fr.claudegateway.ocr.Document bobDoc = saveDocument(bob.getId(), "secret.pdf",
                fr.claudegateway.ocr.DocumentStatus.EXTRACTED, "Données confidentielles de Bob.");

        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Résume\",\"libraryDocumentIds\":[\"" + bobDoc.getId() + "\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("not_found")));
    }

    @Test
    void rejectsLibraryDocumentNotYetExtracted() throws Exception {
        fr.claudegateway.ocr.Document doc = saveDocument(alice.getId(), "scan.pdf",
                fr.claudegateway.ocr.DocumentStatus.PROCESSING, null);

        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Résume\",\"libraryDocumentIds\":[\"" + doc.getId() + "\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("document_not_ready")));
    }

    @Test
    void cannotChatOnAnotherUsersConversation() throws Exception {
        Conversation bobConversation = conversationRepository.save(Conversation.builder()
                .userId(bob.getId()).title("Bob").model("claude-opus-4-8").build());

        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + bobConversation.getId() + "\",\"message\":\"Coucou\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("not_found")));
    }

    @Test
    void mapsProviderUnavailableTo503AndNeverLeaksKey() throws Exception {
        stubAIProvider.toThrow = new AIProviderUnavailableException("dormant");

        String responseBody = mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Salut\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error", is("provider_unavailable")))
                .andReturn().getResponse().getContentAsString();

        // La réponse ne contient jamais d'en-tête/valeur de clé plateforme.
        org.assertj.core.api.Assertions.assertThat(responseBody)
                .doesNotContain("api-key").doesNotContain("x-api-key");
    }

    @Test
    void mapsProviderErrorTo502() throws Exception {
        stubAIProvider.toThrow = new AIProviderException("boom");

        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Salut\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error", is("provider_error")));
    }

    @Test
    void modelsEndpointReturnsWhitelist() throws Exception {
        mockMvc.perform(get("/api/chat/models").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultModel", is("claude-opus-4-8")))
                .andExpect(jsonPath("$.models[0]", is("claude-opus-4-8")));
    }

    // --------------------------------------------------------------- /api/conversations

    @Test
    void listsOnlyOwnConversations() throws Exception {
        conversationRepository.save(Conversation.builder()
                .userId(alice.getId()).title("A").model("claude-opus-4-8").build());
        conversationRepository.save(Conversation.builder()
                .userId(bob.getId()).title("B").model("claude-opus-4-8").build());

        mockMvc.perform(get("/api/conversations").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$[0].title", is("A")));
    }

    @Test
    void detailReturns404ForAnotherUsersConversation() throws Exception {
        Conversation bobConversation = conversationRepository.save(Conversation.builder()
                .userId(bob.getId()).title("Bob").model("claude-opus-4-8").build());

        mockMvc.perform(get("/api/conversations/" + bobConversation.getId()).contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void renameUpdatesOwnConversation() throws Exception {
        Conversation conversation = conversationRepository.save(Conversation.builder()
                .userId(alice.getId()).title("Ancien").model("claude-opus-4-8").build());

        mockMvc.perform(patch("/api/conversations/" + conversation.getId()).contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Nouveau titre\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("Nouveau titre")));
    }

    @Test
    void renameReturns404ForAnotherUsersConversation() throws Exception {
        Conversation bobConversation = conversationRepository.save(Conversation.builder()
                .userId(bob.getId()).title("Bob").model("claude-opus-4-8").build());

        mockMvc.perform(patch("/api/conversations/" + bobConversation.getId()).contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Piraté\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRemovesOwnConversationAndCascadesMessages() throws Exception {
        // Crée une conversation avec messages via le flux chat.
        String body = mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Premier message\"}"))
                .andReturn().getResponse().getContentAsString();
        UUID conversationId = UUID.fromString(
                com.jayway.jsonpath.JsonPath.read(body, "$.conversationId"));

        mockMvc.perform(delete("/api/conversations/" + conversationId).contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/conversations/" + conversationId).contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturns404ForAnotherUsersConversation() throws Exception {
        Conversation bobConversation = conversationRepository.save(Conversation.builder()
                .userId(bob.getId()).title("Bob").model("claude-opus-4-8").build());

        mockMvc.perform(delete("/api/conversations/" + bobConversation.getId()).contextPath("/api")
                        .header("Authorization", bearer(bobToken)))
                .andExpect(status().isNoContent());
        // (Sanity) Alice ne peut pas le supprimer.
        Conversation another = conversationRepository.save(Conversation.builder()
                .userId(bob.getId()).title("Bob2").model("claude-opus-4-8").build());
        mockMvc.perform(delete("/api/conversations/" + another.getId()).contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isNotFound());
    }

    // --------------------------------------------------- GET /api/conversations/{id}/files (F-23)

    @Test
    void listsFilesAttachedToConversation() throws Exception {
        fr.claudegateway.upload.UploadedFile file = uploadedFileRepository.save(
                fr.claudegateway.upload.UploadedFile.builder()
                        .userId(alice.getId()).providerFileId("file_alice")
                        .filename("rapport.pdf").mediaType("application/pdf").sizeBytes(4).build());

        // Le premier message avec pièce jointe crée la conversation et rattache le fichier (F-23).
        String body = mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Analyse\",\"attachmentIds\":[\"" + file.getId() + "\"]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID conversationId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.conversationId"));

        mockMvc.perform(get("/api/conversations/" + conversationId + "/files").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$[0].filename", is("rapport.pdf")))
                .andExpect(jsonPath("$[0].mediaType", is("application/pdf")))
                .andExpect(jsonPath("$[0].sizeBytes", is(4)))
                // Ne fuite jamais l'identifiant fournisseur ni le user_id.
                .andExpect(jsonPath("$[0].providerFileId").doesNotExist())
                .andExpect(jsonPath("$[0].userId").doesNotExist());
    }

    @Test
    void filesReturnsEmptyForConversationWithoutFiles() throws Exception {
        Conversation conversation = conversationRepository.save(Conversation.builder()
                .userId(alice.getId()).title("Sans fichier").model("claude-opus-4-8").build());

        mockMvc.perform(get("/api/conversations/" + conversation.getId() + "/files").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(0)));
    }

    @Test
    void filesReturns404ForAnotherUsersConversation() throws Exception {
        Conversation bobConversation = conversationRepository.save(Conversation.builder()
                .userId(bob.getId()).title("Bob").model("claude-opus-4-8").build());

        mockMvc.perform(get("/api/conversations/" + bobConversation.getId() + "/files").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void detailContainsPersistedMessages() throws Exception {
        String body = mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Question ?\"}"))
                .andReturn().getResponse().getContentAsString();
        UUID conversationId = UUID.fromString(
                com.jayway.jsonpath.JsonPath.read(body, "$.conversationId"));

        mockMvc.perform(get("/api/conversations/" + conversationId).contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.messages.length()", is(2)))
                .andExpect(jsonPath("$.messages[0].role", is("USER")))
                .andExpect(jsonPath("$.messages[0].content", is("Question ?")))
                .andExpect(jsonPath("$.messages[1].role", is("ASSISTANT")));
    }
}
