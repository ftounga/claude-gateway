package fr.claudegateway.chat;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

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
@SpringBootTest
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
    }

    @TestConfiguration
    static class StubProviderConfig {
        @Bean
        @Primary
        StubAIProvider stubAIProvider() {
            return new StubAIProvider();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private StubAIProvider stubAIProvider;

    private User alice;
    private String aliceToken;
    private User bob;
    private String bobToken;

    @BeforeEach
    void setUp() {
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
