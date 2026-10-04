package fr.claudegateway.governance.map.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.governance.map.index.HostMapSectionSplitter.FactLine;

/** La couche sémantique de l'extraction (F-174 / SF-174-02, D2 b) : poser la question, vérifier la réponse. */
class HostMapSectionExtractorTest {

    private final AIProvider provider = mock(AIProvider.class);
    private final ByokKeyService byok = mock(ByokKeyService.class);
    private final HostMapIndexProperties properties =
            new HostMapIndexProperties(null, null, null, null, null, null, null, null, null, null, null, null);
    private final HostMapSectionExtractor extractor =
            new HostMapSectionExtractor(provider, byok, properties, new ObjectMapper());
    private final UUID userId = UUID.randomUUID();

    private final List<FactLine> facts = List.of(
            new FactLine(4, "- compte 123456789012 hébergé par le cluster eks-prod-1"),
            new FactLine(5, "- piège : le proxy Netskope coupe les websockets"),
            new FactLine(6, "- jeton GitLab, à renouveler avant fin octobre"));

    @Test
    @DisplayName("passe par AIProvider avec le modèle D4 (Sonnet 5.5) et la consigne en cache")
    void asksThroughTheProviderInterface() {
        when(byok.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        when(provider.complete(any())).thenReturn(new ChatCompletionResult(
                "raisonnement…\n===CARTE===\n{\"entites\":[],\"relations\":[],\"pieges\":[],\"echeances\":[]}",
                "claude-sonnet-5-5", 900, 40));

        HostMapSectionExtractor.Result result = extractor.extract(userId, "plateformes.md", "Comptes", facts);

        ArgumentCaptor<ChatCompletionRequest> request = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(provider).complete(request.capture());
        assertThat(request.getValue().model()).isEqualTo("claude-sonnet-5-5");
        assertThat(request.getValue().apiKey()).isNull();
        assertThat(request.getValue().cacheSystem()).isTrue();
        assertThat(request.getValue().messages().get(0).content())
                .contains("L5 : - piège : le proxy Netskope coupe les websockets");
        assertThat(result.ok()).isTrue();
        assertThat(result.inputTokens()).isEqualTo(900);
        assertThat(result.outputTokens()).isEqualTo(40);
    }

    @Test
    @DisplayName("garde ce qui est vérifiable, écarte le reste : identifiant absent du texte, ligne inconnue, date illisible")
    void keepsOnlyWhatTheTextSays() {
        String answer = """
                ===CARTE===
                {"entites": [
                   {"type": "compte_aws", "libelle": "Compte prod", "identifiants": ["123456789012", "999999999999"],
                    "environnement": "prod", "etat": "actif"},
                   {"type": "cluster", "libelle": "eks-prod-1", "identifiants": ["eks-prod-1"]},
                   {"libelle": ""}
                 ],
                 "relations": [{"de": "eks-prod-1", "vers": "Compte prod", "nature": "dans"}, {"de": "x"}],
                 "pieges": [5, 42],
                 "echeances": [{"ligne": 6, "date": "2026-10-31"}, {"ligne": 4, "date": "fin octobre"}]}
                """;
        HostMapSectionExtractor.Extraction extraction = extractor.parse(answer, facts);

        assertThat(extraction.entities()).hasSize(2);
        assertThat(extraction.entities().get(0).identifiers()).containsExactly("123456789012");
        assertThat(extraction.entities().get(0).environment()).isEqualTo("prod");
        assertThat(extraction.entities().get(1).kind()).isEqualTo("cluster");
        assertThat(extraction.relations()).hasSize(1);
        assertThat(extraction.pitfallLines()).containsExactly(5);
        assertThat(extraction.deadlines()).hasSize(1);
        assertThat(extraction.deadlines().get(0).date()).isEqualTo(LocalDate.parse("2026-10-31"));
    }

    @Test
    @DisplayName("une réponse illisible n'est pas une extraction")
    void unreadableIsNull() {
        assertThat(extractor.parse("je ne sais pas", facts)).isNull();
        assertThat(extractor.parse("===CARTE===\n{pas du json", facts)).isNull();
        assertThat(extractor.parse(null, facts)).isNull();
    }

    @Test
    @DisplayName("sans clé, le fournisseur est dit indisponible ; une panne n'est jamais une exception")
    void neverThrows() {
        when(byok.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        when(provider.complete(any())).thenThrow(new AIProviderUnavailableException("pas de clé"));
        assertThat(extractor.extract(userId, "a.md", "S", facts).providerUnavailable()).isTrue();

        org.mockito.Mockito.doThrow(new IllegalStateException("panne")).when(provider).complete(any());
        HostMapSectionExtractor.Result failed = extractor.extract(userId, "a.md", "S", facts);
        assertThat(failed.ok()).isFalse();
        assertThat(failed.providerUnavailable()).isFalse();
    }
}
