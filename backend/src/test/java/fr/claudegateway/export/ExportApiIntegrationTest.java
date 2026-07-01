package fr.claudegateway.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.chat.Conversation;
import fr.claudegateway.chat.ConversationRepository;
import fr.claudegateway.chat.Message;
import fr.claudegateway.chat.MessageRepository;
import fr.claudegateway.chat.MessageRole;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration des exports (F-14) sur {@code /api/**} : rendu Markdown/PDF d'une conversation
 * et d'une réponse citée, en-têtes de téléchargement, validation du format, authentification et
 * isolation {@code user_id}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExportApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private JwtService jwtService;

    private String aliceToken;
    private String bobToken;
    private Conversation aliceConversation;

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        userRepository.deleteAll();

        User alice = userRepository.save(User.builder()
                .email("alice@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);

        User bob = userRepository.save(User.builder()
                .email("bob@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        bobToken = jwtService.generateToken(bob);

        aliceConversation = conversationRepository.save(Conversation.builder()
                .userId(alice.getId()).title("Analyse du contrat").model("claude-opus-4-8").build());
        messageRepository.save(Message.builder().conversationId(aliceConversation.getId())
                .userId(alice.getId()).role(MessageRole.USER).content("Quelle est la durée ?").build());
        messageRepository.save(Message.builder().conversationId(aliceConversation.getId())
                .userId(alice.getId()).role(MessageRole.ASSISTANT).content("La durée est de 5 ans.")
                .model("claude-opus-4-8").build());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    // ------------------------------------------------- GET /conversations/{id}/export

    @Test
    void exportsConversationAsMarkdown() throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/conversations/" + aliceConversation.getId() + "/export?format=markdown")
                                .contextPath("/api").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/markdown;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("conversation-" + aliceConversation.getId() + ".md")))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("Analyse du contrat")
                .contains("Quelle est la durée ?").contains("La durée est de 5 ans.");
    }

    @Test
    void exportsConversationAsPdf() throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/conversations/" + aliceConversation.getId() + "/export?format=pdf")
                                .contextPath("/api").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body).isNotEmpty();
        assertThat(new String(body, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void defaultsToMarkdownWhenFormatMissing() throws Exception {
        mockMvc.perform(get("/api/conversations/" + aliceConversation.getId() + "/export")
                        .contextPath("/api").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/markdown;charset=UTF-8"));
    }

    @Test
    void rejectsUnknownFormat() throws Exception {
        mockMvc.perform(get("/api/conversations/" + aliceConversation.getId() + "/export?format=docx")
                        .contextPath("/api").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
    }

    @Test
    void cannotExportAnotherUsersConversation() throws Exception {
        mockMvc.perform(get("/api/conversations/" + aliceConversation.getId() + "/export?format=markdown")
                        .contextPath("/api").header("Authorization", bearer(bobToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("not_found")));
    }

    @Test
    void rejectsUnauthenticatedConversationExport() throws Exception {
        mockMvc.perform(get("/api/conversations/" + aliceConversation.getId() + "/export")
                        .contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------ POST /export/answer

    @Test
    void exportsAnswerAsMarkdown() throws Exception {
        String payload = """
                {"question":"Quelle durée ?","answer":"La durée est de 5 ans.","model":"claude-opus-4-8",
                 "grounded":true,
                 "citations":[{"documentId":null,"filename":"contrat.pdf","page":3,"chunkIndex":0,"snippet":"Clause de durée."}]}
                """;

        MvcResult result = mockMvc.perform(post("/api/export/answer?format=markdown").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/markdown;charset=UTF-8"))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("La durée est de 5 ans.").contains("[contrat.pdf:3:0]");
    }

    @Test
    void exportsAnswerAsPdf() throws Exception {
        String payload = "{\"question\":\"Q ?\",\"answer\":\"R.\",\"grounded\":false,\"citations\":[]}";

        MvcResult result = mockMvc.perform(post("/api/export/answer?format=pdf").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(new String(body, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void rejectsBlankAnswer() throws Exception {
        String payload = "{\"question\":\"Q ?\",\"answer\":\"   \"}";

        mockMvc.perform(post("/api/export/answer").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
    }

    @Test
    void rejectsUnauthenticatedAnswerExport() throws Exception {
        String payload = "{\"question\":\"Q ?\",\"answer\":\"R.\"}";

        mockMvc.perform(post("/api/export/answer").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnauthorized());
    }
}
