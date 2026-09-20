package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Le coût réel, de bout en bout (F-133 / SF-133-01) : un tour décompté laisse en base une ligne
 * <b>complète</b> — les quatre natures de tokens, le montant, le modèle, la grille.
 *
 * <p>Deux tests portent la promesse de non-régression, et ce sont les plus importants du lot :
 * {@code quotaCountersAreUntouched}, qui fige le décompte commercial, et l'assertion sur
 * {@code getInputTokens()} de {@code recordsTheFourNatures…}, qui vérifie que les colonnes de cache
 * <b>ventilent</b> l'entrée traitée au lieu de s'y ajouter. La subfeature <b>ajoute</b> une mesure ;
 * elle n'a le droit de rien déplacer de ce qui existait.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class UsageTurnCostIntegrationTest {

    @Autowired
    private QuotaService quotaService;
    @Autowired
    private UsageTurnRepository usageTurnRepository;
    @Autowired
    private UsageCounterRepository usageCounterRepository;
    @Autowired
    private UserRepository userRepository;

    private UUID userId;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.builder()
                .email("cout-reel-" + UUID.randomUUID() + "@example.com")
                .emailVerified(true)
                .provider(AuthProvider.LOCAL)
                .role(UserRole.USER)
                .build());
        userId = user.getId();
    }

    @Test
    void recordsTheFourNaturesTheCostTheModelAndTheGrid() {
        quotaService.recordUsage(userId, new TurnTokens(10_000L, 5_000L, 40_000L, 8_000L),
                null, "claude-opus-5", null, null);

        UsageTurn turn = onlyTurn();
        // Le VOLUME d'entrée reste le volume TRAITÉ, cache compris : les colonnes de cache le
        // ventilent, elles ne s'y ajoutent pas. Sans quoi F-16 et F-61 compteraient double.
        assertThat(turn.getInputTokens()).isEqualTo(58_000L);
        assertThat(turn.getOutputTokens()).isEqualTo(5_000L);
        assertThat(turn.getCacheReadTokens()).isEqualTo(40_000L);
        assertThat(turn.getCacheWriteTokens()).isEqualTo(8_000L);
        assertThat(turn.getProviderCostUsd()).isEqualByComparingTo("0.275000");
        assertThat(turn.getCostSource()).isEqualTo(TurnCost.Source.CALCULATED);
        assertThat(turn.getModel()).isEqualTo("claude-opus-5");
        assertThat(turn.getPricingVersion()).isEqualTo("2026-09-20");
        assertThat(turn.isPricingFallback()).isFalse();
    }

    @Test
    void theProviderReportedCostIsKeptAsIs() {
        quotaService.recordUsage(userId, new TurnTokens(1_000L, 500L, 0L, 0L),
                new BigDecimal("0.80"), "claude-opus-5", null, null);

        UsageTurn turn = onlyTurn();
        assertThat(turn.getProviderCostUsd()).isEqualByComparingTo("0.800000");
        assertThat(turn.getCostSource()).isEqualTo(TurnCost.Source.PROVIDER);
    }

    @Test
    void quotaCountersAreUntouched() {
        // NON-RÉGRESSION. Les valeurs attendues sont celles d'AVANT la subfeature, calculées par le
        // décompte commercial inchangé (F-63) : entrée traitée 58 000, sortie 5 000, et des tokens
        // facturés strictement positifs. Si ce test bouge, c'est que F-133 a débordé sur le quota.
        quotaService.recordUsage(userId, new TurnTokens(10_000L, 5_000L, 40_000L, 8_000L),
                null, "claude-opus-5", null, null);

        UsageCounter counter = usageCounterRepository.findAll().stream()
                .filter(c -> c.getUserId().equals(userId))
                .findFirst()
                .orElseThrow();
        assertThat(counter.getInputTokens()).isEqualTo(58_000L);
        assertThat(counter.getOutputTokens()).isEqualTo(5_000L);
        // 10 000×5 + 5 000×25 + 40 000×0,50 + 8 000×6,25 (tarif COMMERCIAL, pas celui de vérité)
        // = 245 000 µ$ ⇒ 0,245 $ ÷ 9 $ par million = 27 222 tokens de quota.
        assertThat(counter.getBilledTokens()).isEqualTo(27_222L);
    }

    @Test
    void theTwoGridsDisagreeOnPurpose() {
        // Le décompte commercial facture l'écriture de cache 6,25 (en faveur du client) ; la grille
        // de vérité dit 10,00. Les deux montants coexistent sur la même consommation, et c'est
        // exactement ce que la subfeature rend visible.
        quotaService.recordUsage(userId, new TurnTokens(0L, 0L, 0L, 1_000_000L),
                null, "claude-opus-5", null, null);

        assertThat(onlyTurn().getProviderCostUsd()).isEqualByComparingTo("10.000000");
        UsageCounter counter = usageCounterRepository.findAll().stream()
                .filter(c -> c.getUserId().equals(userId))
                .findFirst()
                .orElseThrow();
        // 1 000 000 × 6,25 ÷ 1e6 = 6,25 $ ÷ 9 $ par million = 694 444 tokens de quota.
        assertThat(counter.getBilledTokens()).isEqualTo(694_444L);
    }

    @Test
    void anUnknownModelIsMarkedAndNeverFailsTheTurn() {
        quotaService.recordUsage(userId, new TurnTokens(1_000_000L, 0L, 0L, 0L),
                null, "un-modele-inconnu", null, null);

        UsageTurn turn = onlyTurn();
        assertThat(turn.isPricingFallback()).isTrue();
        assertThat(turn.getProviderCostUsd()).isEqualByComparingTo("5.000000");
        assertThat(turn.getModel()).isEqualTo("un-modele-inconnu");
    }

    @Test
    void aTurnWithoutAModelIsStillPriced() {
        // Tous les chemins ne rapportent pas le modèle. Ne rien enregistrer serait pire que
        // d'enregistrer un montant approché et de le dire.
        quotaService.recordUsage(userId, new TurnTokens(1_000_000L, 0L, 0L, 0L),
                null, null, null, null);

        UsageTurn turn = onlyTurn();
        assertThat(turn.getModel()).isNull();
        assertThat(turn.isPricingFallback()).isTrue();
        assertThat(turn.getProviderCostUsd()).isEqualByComparingTo("5.000000");
    }

    // ------------------------------------------------ les dépenses hors tokens (SF-133-08)

    @Test
    void recordsWebSearchesAndChargesThem() {
        quotaService.recordUsage(userId, TurnTokens.of(10_000L, 5_000L), new TurnExtras(2L, 0L),
                null, "claude-opus-5", null, null);

        UsageTurn turn = onlyTurn();
        assertThat(turn.getWebSearchRequests()).isEqualTo(2L);
        // 0,175 $ de tokens + 2 × 0,01 $ de recherches.
        assertThat(turn.getProviderCostUsd()).isEqualByComparingTo("0.195000");
    }

    @Test
    void recordsSessionSecondsWithoutDoubleCountingTheProviderCost() {
        // Le coût rapporté par les Managed Agents comprend déjà leur temps de session : le
        // compteur est enregistré pour EXPLIQUER le montant, pas pour le gonfler.
        quotaService.recordUsage(userId, TurnTokens.of(1_000L, 500L), new TurnExtras(0L, 3_600L),
                new BigDecimal("0.80"), "claude-opus-5", null, null);

        UsageTurn turn = onlyTurn();
        assertThat(turn.getSandboxSeconds()).isEqualTo(3_600L);
        assertThat(turn.getProviderCostUsd()).isEqualByComparingTo("0.800000");
        assertThat(turn.getCostSource()).isEqualTo(TurnCost.Source.PROVIDER);
    }

    @Test
    void aTurnThatOnlySearchedIsStillRecorded() {
        quotaService.recordUsage(userId, TurnTokens.of(0L, 0L), new TurnExtras(4L, 0L),
                null, "claude-opus-5", null, null);

        UsageTurn turn = onlyTurn();
        assertThat(turn.getWebSearchRequests()).isEqualTo(4L);
        assertThat(turn.getProviderCostUsd()).isEqualByComparingTo("0.040000");
    }

    @Test
    void extrasNeverEnterTheQuota() {
        // NON-RÉGRESSION. F-133 mesure ce que NOUS payons ; il ne change pas ce que le CLIENT paie.
        // Deux tours identiques en tokens, l'un avec 100 recherches : même décompte de quota.
        quotaService.recordUsage(userId, TurnTokens.of(1_000L, 100L), new TurnExtras(100L, 7_200L),
                null, "claude-opus-5", null, null);

        UsageCounter counter = usageCounterRepository.findAll().stream()
                .filter(c -> c.getUserId().equals(userId))
                .findFirst()
                .orElseThrow();
        // 1 000×5 + 100×25 = 7 500 µ$ ⇒ 0,0075 $ ÷ 9 $ par million = 833 tokens de quota.
        // Exactement ce que vaudrait le même tour sans aucune recherche.
        assertThat(counter.getBilledTokens()).isEqualTo(833L);
    }

    private UsageTurn onlyTurn() {
        List<UsageTurn> turns = usageTurnRepository.findAll().stream()
                .filter(t -> t.getUserId().equals(userId))
                .toList();
        assertThat(turns).hasSize(1);
        return turns.get(0);
    }
}
