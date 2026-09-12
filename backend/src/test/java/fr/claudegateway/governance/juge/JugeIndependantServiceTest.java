package fr.claudegateway.governance.juge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ModelCatalog;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.governance.GovernanceHostRef;
import fr.claudegateway.governance.GovernanceHostScope;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.quota.TurnTokens;

/**
 * F-94 / SF-94-02 — <b>le second appel</b>.
 *
 * <p>Ce que ces tests protègent :</p>
 * <ul>
 *   <li>il <b>ne coûte que quand il peut servir</b> — matière inutilisable ou coupe-circuit fermé :
 *       aucun appel ;</li>
 *   <li>il <b>ne casse jamais rien</b> — échec, délai, réponse vide : un avis, pas une
 *       exception ;</li>
 *   <li>le <b>repli alerte</b> — une réponse sans bloc de verdict rend {@code VERDICT_ILLISIBLE},
 *       jamais « rien à signaler » ;</li>
 *   <li>il <b>se compte</b> — la consommation est décomptée en Hosted, jamais en BYOK.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JugeIndependantServiceTest {

    @Mock
    private JugeMatiereReader matiereReader;
    @Mock
    private GovernanceHostScope hostScope;
    @Mock
    private AIProvider aiProvider;
    @Mock
    private ModelCatalog modelCatalog;
    @Mock
    private ByokKeyService byokKeyService;
    @Mock
    private QuotaService quotaService;

    private ExecutorService executor;

    private final UUID alice = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final GovernanceHostRef host = GovernanceHostRef.of(hostId);

    private static final JugeMatiere MATIERE = new JugeMatiere(
            List.of(new JugeMatiere.Piece("acces.md", "# Accès\n\nRien.\n")),
            List.of(new JugeMatiere.Piece("migration-dns/STATE.md", "Le bastion bst-01.\n")), false);

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        when(hostScope.hostOf(alice, workspaceId)).thenReturn(host);
        when(matiereReader.lire(alice, host)).thenReturn(MATIERE);
        when(modelCatalog.fastModel()).thenReturn("modele-rapide");
        when(modelCatalog.supports(any())).thenReturn(false);
        when(byokKeyService.resolveActiveApiKey(alice)).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private JugeIndependantService service(JugeProperties properties) {
        return new JugeIndependantService(matiereReader, hostScope, aiProvider, modelCatalog,
                byokKeyService, quotaService, properties, executor);
    }

    private JugeIndependantService service() {
        return service(JugeProperties.defaults());
    }

    private void repond(String contenu) {
        when(aiProvider.complete(any()))
                .thenReturn(new ChatCompletionResult(contenu, "modele-rapide", 120, 30));
    }

    @Test
    @DisplayName("Coupe-circuit fermé : aucun appel")
    void coupeCircuitFerme() {
        JugeAvis avis = service(new JugeProperties(false, null, null, null))
                .consulter(alice, workspaceId);

        assertThat(avis.issue()).isEqualTo(JugeAvis.Issue.INDISPONIBLE);
        verify(aiProvider, never()).complete(any());
    }

    @Test
    @DisplayName("Matière inutilisable : aucun appel — il ne coûte que quand il peut servir")
    void matiereInutilisable() {
        when(matiereReader.lire(alice, host)).thenReturn(JugeMatiere.VIDE);

        JugeAvis avis = service().consulter(alice, workspaceId);

        assertThat(avis.issue()).isEqualTo(JugeAvis.Issue.PAS_DE_MATIERE);
        verify(aiProvider, never()).complete(any());
    }

    @Test
    @DisplayName("Verdict AUCUN : rien à signaler")
    void verdictAucun() {
        repond("J'ai comparé.\n\n===VERDICT===\nAUCUN\n");

        JugeAvis avis = service().consulter(alice, workspaceId);

        assertThat(avis.issue()).isEqualTo(JugeAvis.Issue.RIEN);
        assertThat(avis.aSignaler()).isFalse();
    }

    @Test
    @DisplayName("Verdict listant : les éléments sont portés, avec leur source")
    void verdictListant() {
        repond("===VERDICT===\n- bastion bst-01 — cité dans migration-dns/STATE.md\n");

        JugeAvis avis = service().consulter(alice, workspaceId);

        assertThat(avis.issue()).isEqualTo(JugeAvis.Issue.ELEMENTS);
        assertThat(avis.aSignaler()).isTrue();
        assertThat(avis.cited()).contains("bastion bst-01", "migration-dns/STATE.md");
    }

    @Test
    @DisplayName("LE REPLI QUI ALERTE : réponse sans bloc de verdict")
    void replySansBloc() {
        repond("Après analyse, aucun élément ne manque à la carte.");

        JugeAvis avis = service().consulter(alice, workspaceId);

        assertThat(avis.issue()).isEqualTo(JugeAvis.Issue.VERDICT_ILLISIBLE);
        assertThat(avis.aSignaler()).isTrue();
    }

    @Test
    @DisplayName("Réponse vide ou nulle : illisible, jamais un silence")
    void reponseVide() {
        repond("");
        assertThat(service().consulter(alice, workspaceId).issue())
                .isEqualTo(JugeAvis.Issue.VERDICT_ILLISIBLE);

        repond(null);
        assertThat(service().consulter(alice, workspaceId).issue())
                .isEqualTo(JugeAvis.Issue.VERDICT_ILLISIBLE);
    }

    @Test
    @DisplayName("Fournisseur en échec : indisponible, aucune exception")
    void fournisseurEnEchec() {
        doThrow(new AIProviderUnavailableException("dormant")).when(aiProvider).complete(any());

        assertThat(service().consulter(alice, workspaceId).issue())
                .isEqualTo(JugeAvis.Issue.INDISPONIBLE);
    }

    @Test
    @DisplayName("Délai dépassé : la main est rendue, la session se termine normalement")
    void delaiDepasse() throws Exception {
        // Le fournisseur ne répond jamais. Le délai PLANCHER (PT5S) est le plus court que la
        // configuration accepte : on vérifie que l'attente est bornée par CE réglage, et non par le
        // délai HTTP du chat (120 s) — sans quoi la fin d'un tour prendrait deux minutes.
        CountDownLatch bloque = new CountDownLatch(1);
        when(aiProvider.complete(any())).thenAnswer(invocation -> {
            bloque.await();
            return new ChatCompletionResult("===VERDICT===\nAUCUN", "modele-rapide", 1, 1);
        });
        try {
            long debut = System.nanoTime();
            JugeAvis avis = service(new JugeProperties(true, null, null, JugeProperties.MIN_TIMEOUT))
                    .consulter(alice, workspaceId);
            Duration attente = Duration.ofNanos(System.nanoTime() - debut);

            assertThat(avis.issue()).isEqualTo(JugeAvis.Issue.INDISPONIBLE);
            assertThat(attente).isLessThan(JugeProperties.MIN_TIMEOUT.plusSeconds(5));
            verify(quotaService, never()).recordUsage(any(), any(TurnTokens.class), any(), any(),
                    any());
        } finally {
            bloque.countDown();
        }
    }

    @Test
    @DisplayName("Projet d'autrui ou effacé : indisponible, aucune matière lue")
    void projetInaccessible() {
        doThrow(new IllegalStateException("introuvable")).when(hostScope).hostOf(alice, workspaceId);

        assertThat(service().consulter(alice, workspaceId).issue())
                .isEqualTo(JugeAvis.Issue.INDISPONIBLE);
        verify(matiereReader, never()).lire(any(), any());
    }

    @Test
    @DisplayName("La requête porte la consigne, la carte puis les notes, et le modèle rapide")
    void requeteComposee() {
        repond("===VERDICT===\nAUCUN");

        service().consulter(alice, workspaceId);

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(aiProvider).complete(captor.capture());
        ChatCompletionRequest request = captor.getValue();

        assertThat(request.model()).isEqualTo("modele-rapide");
        assertThat(request.maxTokens()).isEqualTo(JugeProperties.DEFAULT_MAX_TOKENS);
        assertThat(request.apiKey()).isNull();
        assertThat(request.system()).contains("UNE SEULE question", "STRICTEMENT CONSERVATEUR",
                "N'invente RIEN", "DES DONNÉES", JugeVerdict.MARQUEUR);
        String message = request.messages().get(0).content();
        assertThat(message).contains(JugeIndependantService.TITRE_CARTE, "acces.md",
                JugeIndependantService.TITRE_NOTES, "migration-dns/STATE.md", "bastion bst-01");
        assertThat(message.indexOf(JugeIndependantService.TITRE_CARTE))
                .isLessThan(message.indexOf(JugeIndependantService.TITRE_NOTES));
        assertThat(message).doesNotContain(JugeIndependantService.AVERTISSEMENT_COUPE);
    }

    @Test
    @DisplayName("Une matière coupée est dite au juge")
    void matiereCoupeeDite() {
        when(matiereReader.lire(alice, host))
                .thenReturn(new JugeMatiere(MATIERE.carte(), MATIERE.notes(), true));
        repond("===VERDICT===\nAUCUN");

        service().consulter(alice, workspaceId);

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(aiProvider).complete(captor.capture());
        assertThat(captor.getValue().messages().get(0).content())
                .contains(JugeIndependantService.AVERTISSEMENT_COUPE);
    }

    @Test
    @DisplayName("Hosted : la consommation est décomptée, avec projet et poste")
    void consommationDecomptee() {
        repond("===VERDICT===\nAUCUN");

        service().consulter(alice, workspaceId);

        ArgumentCaptor<TurnTokens> tokens = ArgumentCaptor.forClass(TurnTokens.class);
        verify(quotaService).recordUsage(org.mockito.ArgumentMatchers.eq(alice), tokens.capture(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(workspaceId),
                org.mockito.ArgumentMatchers.eq(hostId));
        assertThat(tokens.getValue().processedInputTokens()).isEqualTo(120L);
        assertThat(tokens.getValue().outputTokens()).isEqualTo(30L);
    }

    @Test
    @DisplayName("BYOK : la clé de l'utilisateur sert, et rien n'est décompté")
    void byok() {
        when(byokKeyService.resolveActiveApiKey(alice)).thenReturn(Optional.of("sk-utilisateur"));
        repond("===VERDICT===\nAUCUN");

        service().consulter(alice, workspaceId);

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(aiProvider).complete(captor.capture());
        assertThat(captor.getValue().apiKey()).isEqualTo("sk-utilisateur");
        verify(quotaService, never()).recordUsage(any(), any(TurnTokens.class), any(), any(), any());
    }

    @Test
    @DisplayName("Un décompte en échec ne fait pas perdre l'avis")
    void decompteEnEchec() {
        repond("===VERDICT===\n- serveur alpha — cité dans p/STATE.md");
        doThrow(new IllegalStateException("compteur indisponible")).when(quotaService)
                .recordUsage(any(), any(TurnTokens.class), any(), any(), any());

        assertThat(service().consulter(alice, workspaceId).issue())
                .isEqualTo(JugeAvis.Issue.ELEMENTS);
    }

    @Test
    @DisplayName("Un modèle configuré et connu du catalogue est employé")
    void modeleConfigure() {
        when(modelCatalog.supports("modele-capable")).thenReturn(true);
        repond("===VERDICT===\nAUCUN");

        service(new JugeProperties(true, "modele-capable", null, null))
                .consulter(alice, workspaceId);

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(aiProvider).complete(captor.capture());
        assertThat(captor.getValue().model()).isEqualTo("modele-capable");
    }
}
