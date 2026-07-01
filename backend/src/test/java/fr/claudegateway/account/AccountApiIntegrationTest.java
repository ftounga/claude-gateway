package fr.claudegateway.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.chat.Conversation;
import fr.claudegateway.chat.ConversationRepository;
import fr.claudegateway.chat.Message;
import fr.claudegateway.chat.MessageRepository;
import fr.claudegateway.chat.MessageRole;
import fr.claudegateway.quota.UsageCounter;
import fr.claudegateway.quota.UsageCounterRepository;
import fr.claudegateway.upload.UploadedFile;
import fr.claudegateway.upload.UploadedFileRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration RGPD de F-11 sur {@code /api/account/**} : export complet, suppression
 * définitive, invalidation du JWT après suppression, et isolation {@code user_id}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private UploadedFileRepository uploadedFileRepository;
    @Autowired
    private UsageCounterRepository usageCounterRepository;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private JwtService jwtService;

    private User alice;
    private String aliceToken;
    private User bob;
    private String bobToken;

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        uploadedFileRepository.deleteAll();
        usageCounterRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        alice = seedUser("alice@example.com");
        aliceToken = jwtService.generateToken(alice);
        seedData(alice, "Analyse de contrat");

        bob = seedUser("bob@example.com");
        bobToken = jwtService.generateToken(bob);
        seedData(bob, "Note de Bob");
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder()
                .email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
    }

    private void seedData(User user, String conversationTitle) {
        Conversation conversation = conversationRepository.save(Conversation.builder()
                .userId(user.getId()).title(conversationTitle).model("claude-sonnet").build());
        messageRepository.save(Message.builder()
                .conversationId(conversation.getId()).userId(user.getId())
                .role(MessageRole.USER).content("Bonjour").build());
        uploadedFileRepository.save(UploadedFile.builder()
                .userId(user.getId()).providerFileId("file_" + user.getId())
                .filename("doc.pdf").mediaType("application/pdf").sizeBytes(1024L).build());
        usageCounterRepository.save(UsageCounter.builder()
                .userId(user.getId()).periodStart(LocalDate.now().withDayOfMonth(1))
                .inputTokens(100L).outputTokens(50L).build());
        subscriptionRepository.save(Subscription.builder()
                .userId(user.getId()).status(SubscriptionStatus.TRIALING).build());
    }

    @Test
    void exportReturnsOnlyOwnData() throws Exception {
        mockMvc.perform(get("/api/account/export").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.email", is("alice@example.com")))
                .andExpect(jsonPath("$.subscription.status", is("TRIALING")))
                .andExpect(jsonPath("$.usage[0].inputTokens", is(100)))
                .andExpect(jsonPath("$.conversations[0].title", is("Analyse de contrat")))
                .andExpect(jsonPath("$.conversations[0].messages[0].content", is("Bonjour")))
                .andExpect(jsonPath("$.uploadedFiles[0].filename", is("doc.pdf")))
                // Aucun champ sensible ne doit être exposé.
                .andExpect(jsonPath("$.account.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.subscription.stripeCustomerId").doesNotExist())
                .andExpect(jsonPath("$.uploadedFiles[0].providerFileId").doesNotExist());
    }

    @Test
    void exportRejectsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/account/export").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteRemovesAllOwnDataAndInvalidatesToken() throws Exception {
        mockMvc.perform(delete("/api/account").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        UUID aliceId = alice.getId();
        assertThat(userRepository.findById(aliceId)).isEmpty();
        assertThat(conversationRepository.findByUserIdOrderByUpdatedAtDesc(aliceId)).isEmpty();
        assertThat(uploadedFileRepository.findByUserId(aliceId)).isEmpty();
        assertThat(usageCounterRepository.findByUserId(aliceId)).isEmpty();
        assertThat(subscriptionRepository.findByUserId(aliceId)).isEmpty();

        // Bob n'est pas affecté (isolation).
        assertThat(userRepository.findById(bob.getId())).isPresent();
        assertThat(conversationRepository.findByUserIdOrderByUpdatedAtDesc(bob.getId())).hasSize(1);
        assertThat(uploadedFileRepository.findByUserId(bob.getId())).hasSize(1);

        // L'ancien JWT d'Alice ne donne plus accès (utilisateur introuvable → 401).
        mockMvc.perform(get("/api/account/export").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteRejectsUnauthenticated() throws Exception {
        mockMvc.perform(delete("/api/account").contextPath("/api"))
                .andExpect(status().isUnauthorized());

        // Aucune donnée n'a été supprimée.
        assertThat(userRepository.findById(alice.getId())).isPresent();
    }

    @Test
    void exportIsIsolatedBetweenUsers() throws Exception {
        mockMvc.perform(get("/api/account/export").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.email", is("bob@example.com")))
                .andExpect(jsonPath("$.conversations[0].title", is("Note de Bob")));
    }
}
