package fr.claudegateway.help;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ProviderFileReference;
import fr.claudegateway.ai.ProviderFileUpload;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration de {@code POST /api/help/chat} (F-54 / SF-54-01) : flux nominal, consigne
 * portant la documentation, validation, authentification, garde-fou de débit et indisponibilité du
 * fournisseur. Le fournisseur est bouchonné — aucun appel réseau.
 *
 * <p>Le plafond de débit est ramené à 2 questions par la configuration du test : le vérifier au
 * plafond réel demanderait 21 appels pour n'en démontrer qu'un.</p>
 */
@SpringBootTest(properties = "app.help.max-questions=2")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HelpChatApiIntegrationTest {

    /** Fournisseur IA bouchonné, pilotable : réponse fixe, ou panne au choix du test. */
    static class StubAIProvider implements AIProvider {
        volatile ChatCompletionRequest lastRequest;
        volatile RuntimeException failure;

        @Override
        public ChatCompletionResult complete(ChatCompletionRequest request) {
            this.lastRequest = request;
            if (failure != null) {
                throw failure;
            }
            return new ChatCompletionResult("Réponse d'aide de test.", request.model(), 10, 5);
        }

        @Override
        public ProviderFileReference uploadFile(ProviderFileUpload upload) {
            return new ProviderFileReference("file_stub");
        }
    }

    @TestConfiguration
    static class StubConfig {
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
    private JwtService jwtService;
    @Autowired
    private StubAIProvider stubAIProvider;

    private String token;

    @BeforeEach
    void setUp() {
        stubAIProvider.failure = null;
        stubAIProvider.lastRequest = null;
        // Un utilisateur neuf à chaque test : le compteur de débit est indexé user_id, deux tests
        // ne peuvent donc pas se gêner.
        User user = userRepository.save(User.builder()
                .email("aide-" + java.util.UUID.randomUUID() + "@example.com")
                .emailVerified(true)
                .provider(AuthProvider.LOCAL)
                .role(UserRole.USER)
                .build());
        token = jwtService.generateToken(user);
    }

    private static String body(String message) {
        return "{\"message\":\"" + message + "\"}";
    }

    private org.springframework.test.web.servlet.ResultActions ask(String message) throws Exception {
        return mockMvc.perform(post("/api/help/chat").contextPath("/api")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(message)));
    }

    @Test
    void refuseUneQuestionSansAuthentification() throws Exception {
        mockMvc.perform(post("/api/help/chat").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("comment appairer une machine ?")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void repondAUnCompteConnecte() throws Exception {
        ask("comment appairer une machine ?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", is("Réponse d'aide de test.")))
                .andExpect(jsonPath("$.answer", notNullValue()));
    }

    @Test
    void laConsigneTransmiseAuFournisseurPorteLaDocumentation() throws Exception {
        ask("quel fichier télécharger sur Mac ?").andExpect(status().isOk());

        assertThat(stubAIProvider.lastRequest.system())
                .contains("Télécharger le runner")
                .contains("Proxy, réseau et pare-feu");
        assertThat(stubAIProvider.lastRequest.maxTokens()).isEqualTo(512);
    }

    @Test
    void refuseUneQuestionVide() throws Exception {
        ask("   ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
    }

    @Test
    void refuseUneQuestionTropLongue() throws Exception {
        ask("a".repeat(501)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
    }

    @Test
    void accepteUneQuestionDeLaLongueurMaximale() throws Exception {
        ask("a".repeat(500)).andExpect(status().isOk());
    }

    @Test
    void renvoie503QuandLeFournisseurEstIndisponible() throws Exception {
        stubAIProvider.failure = new AIProviderUnavailableException("fournisseur dormant");

        ask("une question")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error", is("provider_unavailable")))
                // Aucune trace d'exception ne remonte au client.
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void refuseAuDelaDuPlafondDeDebit() throws Exception {
        ask("question 1").andExpect(status().isOk());
        ask("question 2").andExpect(status().isOk());

        ask("question 3")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error", is("help_rate_limited")));
    }

    @Test
    void laConsigneNeDependDAucuneDonneeUtilisateur() throws Exception {
        // Deux comptes distincts, même question : la consigne système est rigoureusement la même.
        ask("comment appairer ?").andExpect(status().isOk());
        String premiere = stubAIProvider.lastRequest.system();

        User autre = userRepository.save(User.builder()
                .email("aide-autre-" + java.util.UUID.randomUUID() + "@example.com")
                .emailVerified(true)
                .provider(AuthProvider.LOCAL)
                .role(UserRole.USER)
                .build());
        token = jwtService.generateToken(autre);

        ask("comment appairer ?").andExpect(status().isOk());

        assertThat(stubAIProvider.lastRequest.system()).isEqualTo(premiere);
    }
}
