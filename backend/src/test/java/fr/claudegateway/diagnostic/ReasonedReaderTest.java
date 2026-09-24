package fr.claudegateway.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;

/**
 * La lecture raisonnée du code (F-157 / SF-157-04) — la seule partie du diagnostic qui coûte.
 *
 * <p>Ce que ces tests tiennent : l'appel passe par l'<b>interface abstraite</b>, <b>aucun appel
 * fournisseur</b> quand il n'y a rien à lire (donc zéro coût), la troncature est <b>dite</b>, le
 * coût est <b>rendu</b>, et une panne <b>ne casse rien</b>.</p>
 */
class ReasonedReaderTest {

    private final AIProvider provider = mock(AIProvider.class);

    private ReasonedReader reader;

    @BeforeEach
    void setUp() {
        reader = new ReasonedReader(provider, DiagnosticProperties.defaults());
    }

    private CapabilityFinding finding(String capabilityId) {
        return new CapabilityFinding(capabilityId, capabilityId, CapabilityVerdict.DORMANTE,
                "Aucun déclenchement sur la période.", List.of(), "la condition", null);
    }

    /** Les fichiers de la capacité « plan », tous lus. */
    private Map<String, SourceRead> planSources(String body) {
        ProductCapability plan = CapabilityMap.byId("plan").orElseThrow();
        return plan.paths().stream().collect(java.util.stream.Collectors.toMap(
                p -> p, p -> SourceRead.read(p, body)));
    }

    private void answers(String text) {
        when(provider.complete(any())).thenReturn(
                new ChatCompletionResult(text, "claude-opus-5", 4200, 380, 0, 0));
    }

    @Test
    @DisplayName("appel nominal : la question porte la capacité, sa condition et le constat ; le coût est rendu")
    void theQuestionCarriesWhatIsAlreadyKnown() {
        answers("Il manque un appel à X.");

        Optional<SourceHypothesis> hypothesis = reader.read(finding("plan"), planSources("du code"));

        assertThat(hypothesis).isPresent();
        assertThat(hypothesis.get().text()).isEqualTo("Il manque un appel à X.");
        assertThat(hypothesis.get().model()).isEqualTo("claude-opus-5");
        assertThat(hypothesis.get().inputTokens()).isEqualTo(4200);
        assertThat(hypothesis.get().outputTokens()).isEqualTo(380);

        ArgumentCaptor<ChatCompletionRequest> sent =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(provider).complete(sent.capture());
        String question = sent.getValue().messages().get(0).content();
        assertThat(question)
                .contains("Tenir un plan")
                .contains("Sa condition d'activation")
                .contains("Aucun déclenchement sur la période")
                .contains("du code");
        assertThat(sent.getValue().system())
                .as("la consigne interdit d'affirmer")
                .contains("HYPOTHÈSE")
                .contains("je ne peux pas le savoir ici");
    }

    @Test
    @DisplayName("seuls les fichiers DE CETTE capacité sont envoyés — jamais ceux d'une autre")
    void onlyThisCapabilityFilesAreSent() {
        answers("peu importe");
        Map<String, SourceRead> mixed = new java.util.HashMap<>(planSources("CODE-DU-PLAN"));
        mixed.put("backend/src/main/java/fr/claudegateway/agent/AnthropicAgentProvider.java",
                SourceRead.read("…/AnthropicAgentProvider.java", "CODE-DU-CACHE"));

        reader.read(finding("plan"), mixed);

        ArgumentCaptor<ChatCompletionRequest> sent =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(provider).complete(sent.capture());
        String question = sent.getValue().messages().get(0).content();
        assertThat(question).contains("CODE-DU-PLAN");
        assertThat(question).doesNotContain("CODE-DU-CACHE");
    }

    @Test
    @DisplayName("AUCUN fichier lu → AUCUN appel fournisseur, donc zéro coût")
    void nothingToReadCostsNothing() {
        assertThat(reader.read(finding("plan"), Map.of())).isEmpty();
        assertThat(reader.read(finding("plan"), null)).isEmpty();
        assertThat(reader.read(finding("plan"),
                Map.of("un/chemin.java", SourceRead.absent("un/chemin.java", "illisible")))).isEmpty();

        verify(provider, never()).complete(any());
    }

    @Test
    @DisplayName("une capacité inconnue de la carte n'appelle rien")
    void anUnmappedCapabilityCallsNothing() {
        assertThat(reader.read(finding("inventée"), planSources("du code"))).isEmpty();
        verify(provider, never()).complete(any());
    }

    @Test
    @DisplayName("la troncature est DITE — un extrait qu'on croit complet ferait raisonner de travers")
    void truncationIsStated() {
        ReasonedReader tiny = new ReasonedReader(provider,
                new DiagnosticProperties(null, 50, null));
        answers("réponse");

        Optional<SourceHypothesis> hypothesis =
                tiny.read(finding("plan"), planSources("x".repeat(500)));

        assertThat(hypothesis).isPresent();
        assertThat(hypothesis.get().truncated()).isTrue();

        ArgumentCaptor<ChatCompletionRequest> sent =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(provider).complete(sent.capture());
        assertThat(sent.getValue().messages().get(0).content())
                .as("le message lui-même prévient le modèle")
                .contains("TRONQUÉ");
    }

    @Test
    @DisplayName("une panne fournisseur ne casse rien : l'hypothèse manque, le diagnostic reste")
    void aProviderFailureBreaksNothing() {
        when(provider.complete(any()))
                .thenThrow(new AIProviderUnavailableException("fournisseur indisponible"));

        assertThat(reader.read(finding("plan"), planSources("du code"))).isEmpty();
    }

    @Test
    @DisplayName("une réponse vide n'est pas une hypothèse vide")
    void anEmptyAnswerIsNoHypothesis() {
        when(provider.complete(any()))
                .thenReturn(new ChatCompletionResult("   ", "claude-opus-5", 10, 0, 0, 0));
        assertThat(reader.read(finding("plan"), planSources("du code"))).isEmpty();

        when(provider.complete(any())).thenReturn(null);
        assertThat(reader.read(finding("plan"), planSources("du code"))).isEmpty();
    }

    @Test
    @DisplayName("le résultat se présente comme une HYPOTHÈSE, et le rappel existe pour l'écran")
    void theResultIsAnHypothesis() {
        assertThat(SourceHypothesis.CAVEAT)
                .contains("Hypothèse")
                .contains("ce n'est pas un constat mesuré");
    }

    @Test
    @DisplayName("les réglages ont des défauts explicites, et refusent les valeurs absurdes")
    void settingsHaveExplicitDefaults() {
        DiagnosticProperties defaults = DiagnosticProperties.defaults();
        assertThat(defaults.model()).isEqualTo(DiagnosticProperties.DEFAULT_MODEL);
        assertThat(defaults.maxCodeChars()).isEqualTo(DiagnosticProperties.DEFAULT_MAX_CODE_CHARS);
        assertThat(defaults.maxAnswerTokens())
                .isEqualTo(DiagnosticProperties.DEFAULT_MAX_ANSWER_TOKENS);

        DiagnosticProperties absurd = new DiagnosticProperties("  ", -1, 0);
        assertThat(absurd.model()).isEqualTo(DiagnosticProperties.DEFAULT_MODEL);
        assertThat(absurd.maxCodeChars()).isEqualTo(DiagnosticProperties.DEFAULT_MAX_CODE_CHARS);
        assertThat(absurd.maxAnswerTokens())
                .isEqualTo(DiagnosticProperties.DEFAULT_MAX_ANSWER_TOKENS);
    }
}
