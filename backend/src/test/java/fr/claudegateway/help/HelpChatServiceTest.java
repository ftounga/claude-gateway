package fr.claudegateway.help;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ChatRole;
import fr.claudegateway.ai.ModelCatalog;
import fr.claudegateway.ai.ProviderFileReference;
import fr.claudegateway.ai.ProviderFileUpload;
import fr.claudegateway.help.dto.HelpChatResponse;

/**
 * Service d'aide produit (F-54 / SF-54-01) : consigne système fondée sur la documentation, modèle
 * rapide du catalogue, sortie bornée, et garde-fou de débit.
 */
class HelpChatServiceTest {

    /** Fournisseur bouchonné : enregistre la requête reçue, sans aucun appel réseau. */
    private static final class StubProvider implements AIProvider {
        ChatCompletionRequest lastRequest;
        RuntimeException failure;

        @Override
        public ChatCompletionResult complete(ChatCompletionRequest request) {
            this.lastRequest = request;
            if (failure != null) {
                throw failure;
            }
            return new ChatCompletionResult("Prenez le paquet autonome.", request.model(), 10, 5);
        }

        @Override
        public ProviderFileReference uploadFile(ProviderFileUpload upload) {
            return new ProviderFileReference("stub");
        }
    }

    /** Catalogue bouchonné : distingue explicitement modèle par défaut et modèle rapide. */
    private static final class StubCatalog implements ModelCatalog {
        @Override
        public String defaultModel() {
            return "modele-par-defaut";
        }

        @Override
        public List<String> availableModels() {
            return List.of("modele-par-defaut", "modele-rapide");
        }

        @Override
        public String fastModel() {
            return "modele-rapide";
        }
    }

    private static final HelpProperties PROPERTIES =
            new HelpProperties(2, Duration.ofMinutes(60), 512);

    private StubProvider provider;
    private HelpChatService service;
    private UUID user;

    @BeforeEach
    void setUp() {
        provider = new StubProvider();
        service = new HelpChatService(provider, new StubCatalog(),
                new HelpRateLimiter(PROPERTIES, Clock.systemUTC()), PROPERTIES,
                new HelpDocumentLoader());
        user = UUID.randomUUID();
    }

    @Test
    void laConsigneSystemePorteLaDocumentationProduit() {
        service.answer(user, "quel fichier je télécharge sur Windows ?");

        assertThat(provider.lastRequest.system())
                .contains("Télécharger le runner")
                .contains("claude-runner-windows-x64.zip");
    }

    @Test
    void laConsigneInterditDInventerEtBorneLeSujet() {
        service.answer(user, "bonjour");

        String system = provider.lastRequest.system();
        assertThat(system).contains("N'invente RIEN");
        assertThat(system).contains("UNIQUEMENT à partir de la documentation");
        assertThat(system).contains("AUCUNE donnée de l'utilisateur");
    }

    @Test
    void appelleLeModeleRapideAvecUneSortieBornee() {
        service.answer(user, "comment appairer ?");

        assertThat(provider.lastRequest.model()).isEqualTo("modele-rapide");
        assertThat(provider.lastRequest.maxTokens()).isEqualTo(512);
    }

    @Test
    void transmetLaQuestionCommeUniqueMessageUtilisateur() {
        service.answer(user, "  pourquoi Java 8 ne marche pas ?  ");

        assertThat(provider.lastRequest.messages()).hasSize(1);
        assertThat(provider.lastRequest.messages().get(0).role()).isEqualTo(ChatRole.USER);
        assertThat(provider.lastRequest.messages().get(0).content())
                .isEqualTo("pourquoi Java 8 ne marche pas ?");
        // Aucune clé BYOK, aucune pièce jointe : l'aide est un appel plateforme nu.
        assertThat(provider.lastRequest.apiKey()).isNull();
        assertThat(provider.lastRequest.attachments()).isEmpty();
    }

    @Test
    void renvoieLaReponseDuFournisseur() {
        HelpChatResponse response = service.answer(user, "et sur Mac ?");

        assertThat(response.answer()).isEqualTo("Prenez le paquet autonome.");
    }

    @Test
    void propageLIndisponibiliteDuFournisseur() {
        provider.failure = new AIProviderUnavailableException("dormant");

        assertThatThrownBy(() -> service.answer(user, "une question"))
                .isInstanceOf(AIProviderUnavailableException.class);
    }

    @Test
    void refuseAuDelaDuPlafondDeDebit() {
        service.answer(user, "question 1");
        service.answer(user, "question 2");

        assertThatThrownBy(() -> service.answer(user, "question 3"))
                .isInstanceOf(HelpRateLimitExceededException.class);
    }
}
