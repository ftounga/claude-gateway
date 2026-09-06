package fr.claudegateway.byok;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ProviderFileReference;
import fr.claudegateway.ai.ProviderFileUpload;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.chat.MessageRepository;
import fr.claudegateway.quota.UsageCounterRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-41 / SF-41-02 — le point dur de la feature, vérifié endpoint par endpoint : <b>que se passe-t-il
 * quand l'offre est BYOK et qu'aucune clé n'est enregistrée ?</b>
 *
 * <p>Sans garde-fou, l'appel repartirait avec la clé de la <b>plateforme</b> : tous les appelants
 * font {@code resolveActiveApiKey(userId).orElse(null)}, et {@code null} signifie « mode Hosted ».
 * Le client ne paie aucun jeton à la gateway, et depuis SF-41-01 son quota nul ne le bloque plus non
 * plus. Ce test exige donc un refus <b>nommé</b>, servi <b>avant</b> tout appel fournisseur.</p>
 *
 * <p>Les trois endpoints <b>SSE</b> sont traités à part : leur refus voyage <i>dans le flux</i>. Un
 * {@code internal_error} y serait un échec — c'est exactement ce que produirait leur
 * {@code catch (RuntimeException)} si le refus n'était pas nommé.</p>
 */
@TestPropertySource(properties = {
        "app.atelier.storage-execution=true",
        // Managed Agents allumé : sans cela le flag refuserait AVANT le pré-vol (agent_disabled)
        // et le chemin qu'on veut mesurer resterait inatteignable.
        "app.atelier.agent.enabled=true"})
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ByokKeyRequiredApiIntegrationTest {

    /** Fournisseur IA bouchonné : garde la dernière requête pour vérifier QUELLE clé a servi. */
    static class StubAIProvider implements AIProvider {
        volatile ChatCompletionRequest lastRequest;

        @Override
        public ChatCompletionResult complete(ChatCompletionRequest request) {
            this.lastRequest = request;
            return new ChatCompletionResult("Réponse", request.model(), 12, 8);
        }

        @Override
        public ProviderFileReference uploadFile(ProviderFileUpload upload) {
            return new ProviderFileReference("file_stub");
        }
    }

    @TestConfiguration
    static class StubsConfig {
        @Bean
        @Primary
        StubAIProvider stubAIProvider() {
            return new StubAIProvider();
        }

        @Bean
        @Primary
        StubAiAgentProvider stubAiAgentProvider() {
            return new StubAiAgentProvider();
        }

        /** Exécuteur SSE synchrone : le relais s'exécute inline (corps lisible directement). */
        @Bean("chatStreamExecutor")
        @Primary
        Executor chatStreamExecutor() {
            return Runnable::run;
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private UserApiKeyRepository userApiKeyRepository;
    @Autowired private UsageCounterRepository usageCounterRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private ByokKeyCipher cipher;
    @Autowired private JwtService jwtService;
    @Autowired private StubAIProvider aiProvider;

    /** Abonnée BYOK <b>sans</b> clé : celle pour qui tout doit se dire clairement. */
    private User nokey;
    private String nokeyToken;
    /** Abonné BYOK <b>avec</b> clé : le témoin — rien ne doit changer pour lui. */
    private User withkey;
    private String withkeyToken;

    private UUID nokeyWorkspace;

    @BeforeEach
    void setUp() throws Exception {
        workspaceRepository.deleteAll();
        messageRepository.deleteAll();
        usageCounterRepository.deleteAll();
        userApiKeyRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        nokey = userRepository.save(User.builder().email("sanscle@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        nokeyToken = jwtService.generateToken(nokey);
        withkey = userRepository.save(User.builder().email("avecle@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        withkeyToken = jwtService.generateToken(withkey);

        // Le stub est un singleton de contexte : sans remise à zéro, un test lirait la requête
        // laissée par le précédent — et « aucun appel fournisseur » deviendrait indémontrable.
        aiProvider.lastRequest = null;

        subscribe(nokey, PlanCode.BYOK);
        nokeyWorkspace = createWorkspace(nokey);
    }

    // ------------------------------------------------------------------ outillage

    private void subscribe(User user, PlanCode plan) {
        subscribe(user, plan, SubscriptionStatus.ACTIVE);
    }

    private void subscribe(User user, PlanCode plan, SubscriptionStatus status) {
        Subscription existing = subscriptionRepository.findByUserId(user.getId())
                .orElseGet(() -> Subscription.builder().userId(user.getId()).build());
        existing.setPlanCode(plan);
        existing.setStatus(status);
        subscriptionRepository.save(existing);
    }

    /** Dépose une clé BYOK active, chiffrée par le vrai chiffreur du profil de test. */
    private void giveActiveKey(User user, String rawKey) {
        EncryptedKey encrypted = cipher.encrypt(rawKey);
        userApiKeyRepository.save(UserApiKey.builder()
                .userId(user.getId()).provider(ByokProvider.ANTHROPIC).active(true)
                .encryptedDataKey(encrypted.encryptedDataKey())
                .cipherIv(encrypted.iv()).ciphertext(encrypted.ciphertext())
                .keyLast4(rawKey.substring(rawKey.length() - 4)).build());
    }

    private UUID createWorkspace(User user) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            zip.putNextEntry(new ZipEntry("notes.txt"));
            zip.write("contenu".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return workspaceService.create(user.getId(), "projet", baos.toByteArray()).workspace().getId();
    }

    /** Corps SSE lu après démarrage de l'asynchrone (l'exécuteur du test est synchrone). */
    private String sse(String path, String token, String body) throws Exception {
        return mockMvc.perform(post(path).contextPath("/api")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(body))
                .andExpect(request().asyncStarted())
                .andReturn().getResponse().getContentAsString();
    }

    // ------------------------------------------------------------ 1. chat REST

    @Test
    @DisplayName("POST /chat — refus nommé, actionnable, et rien n'est écrit")
    void chatRefusesWithAnActionableMessageAndWritesNothing() throws Exception {
        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", "Bearer " + nokeyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bonjour\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("byok_key_required")))
                // Le message dit OÙ aller : un refus qu'on ne peut pas corriger n'en est pas un.
                .andExpect(jsonPath("$.message", containsString("Paramètres")));

        // Refus posé sur le pré-vol : aucun appel fournisseur, aucun message, aucune consommation.
        assertThat(aiProvider.lastRequest).as("aucun appel fournisseur").isNull();
        assertThat(messageRepository.findAll()).isEmpty();
        assertThat(usageCounterRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("POST /chat — une clé enregistrée, et l'appel repart, servi par CETTE clé")
    void chatIsServedByTheCustomerKeyOnceRegistered() throws Exception {
        subscribe(withkey, PlanCode.BYOK);
        giveActiveKey(withkey, "sk-ant-cle-du-client-9876");

        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", "Bearer " + withkeyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bonjour\"}"))
                .andExpect(status().isOk());

        assertThat(aiProvider.lastRequest.apiKey()).isEqualTo("sk-ant-cle-du-client-9876");
    }

    @Test
    @DisplayName("POST /chat — un abonné Hosted sans clé reste servi par la plateforme")
    void hostedSubscriberWithoutAKeyIsUntouched() throws Exception {
        // Non-régression : le repli sur la clé plateforme est le comportement CORRECT en Hosted.
        subscribe(withkey, PlanCode.SOLO);

        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", "Bearer " + withkeyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bonjour\"}"))
                .andExpect(status().isOk());

        assertThat(aiProvider.lastRequest.apiKey()).isNull();
    }

    @Test
    @DisplayName("POST /chat — abonnement BYOK résilié : c'est le quota qui refuse, pas la clé")
    void canceledByokIsRefusedOnTheSubscriptionFirst() throws Exception {
        subscribe(nokey, PlanCode.BYOK, SubscriptionStatus.CANCELED);

        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", "Bearer " + nokeyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bonjour\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error", is("quota_exceeded")));
    }

    @Test
    @DisplayName("Isolation — l'absence de clé chez l'un ne refuse jamais l'autre")
    void oneMissingKeyNeverRefusesAnotherUser() throws Exception {
        subscribe(withkey, PlanCode.BYOK);
        giveActiveKey(withkey, "sk-ant-cle-du-client-9876");

        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", "Bearer " + withkeyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bonjour\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", "Bearer " + nokeyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bonjour\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("byok_key_required")));
    }

    // ----------------------------------------------------------- 2. chat SSE

    @Test
    @DisplayName("POST /chat/stream — le refus voyage dans le flux, nommé, jamais internal_error")
    void chatStreamCarriesTheNamedRefusalInsideTheStream() throws Exception {
        String body = sse("/api/chat/stream", nokeyToken, "{\"message\":\"Bonjour\"}");

        assertThat(body).contains("byok_key_required");
        assertThat(body).doesNotContain("internal_error");
    }

    // -------------------------------------------------------- 3. Atelier SSE

    @Test
    @DisplayName("POST /workspaces/{id}/chat/stream — refus nommé dans le flux")
    void atelierChatStreamCarriesTheNamedRefusal() throws Exception {
        String body = sse("/api/workspaces/" + nokeyWorkspace + "/chat/stream", nokeyToken,
                "{\"message\":\"salut\"}");

        assertThat(body).contains("byok_key_required");
        assertThat(body).doesNotContain("internal_error");
    }

    @Test
    @DisplayName("POST /workspaces/{id}/agent/stream — refus nommé avant toute création de session")
    void atelierAgentStreamCarriesTheNamedRefusal() throws Exception {
        String body = sse("/api/workspaces/" + nokeyWorkspace + "/agent/stream", nokeyToken,
                "{\"message\":\"salut\"}");

        assertThat(body).contains("byok_key_required");
        assertThat(body).doesNotContain("internal_error");
    }
}
