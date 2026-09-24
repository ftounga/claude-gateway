package fr.claudegateway.bilan;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import fr.claudegateway.quota.ProviderPricingProperties;

/**
 * Les suggestions d'usage (F-155 / SF-155-02).
 *
 * <p>Ce que ces tests tiennent — et qu'un appel modèle rendrait intenable : chaque suggestion cite
 * une <b>mesure</b>, son gain est <b>calculé</b> sur la grille réelle, le <b>seuil</b> écarte
 * vraiment, et « rien à signaler » est une conclusion valide. <b>Aucun jeton dépensé.</b></p>
 */
class SessionSuggestionServiceTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-24T09:00:00Z");

    /** Une grille où un jeton d'entrée coûte 10 fois un jeton lu en cache — l'écart réel d'Opus. */
    private static final ProviderPricingProperties PRICING = new ProviderPricingProperties(
            "2026-09-20", "claude-opus-5",
            Map.of("claude-opus-5", new ProviderPricingProperties.ModelPricing(
                    new BigDecimal("15.00"), new BigDecimal("75.00"),
                    new BigDecimal("1.50"), new BigDecimal("18.75"))),
            null, null, new BigDecimal("1.00")); // 1 USD = 1 EUR : les chiffres restent lisibles

    /** Le genre attendu d'une suggestion retenue, pour dire quel détecteur a parlé. */
    private static SessionSuggestion.Kind kindOf(List<SessionSuggestion> kept, int index) {
        return kept.get(index).kind();
    }

    private SessionSuggestionService service(Integer threshold) {
        return new SessionSuggestionService(PRICING,
                SessionBilanProperties.ofImpact(threshold, null, null));
    }

    private SessionLedger ledger(int turns, String costEur, long input, long cacheRead,
                                 int toolCalls, int failed, Duration toolTime,
                                 List<SessionLedger.CostlyTurn> costly,
                                 List<SessionLedger.HeavyTool> tools) {
        long total = input + cacheRead;
        int share = total == 0 ? 0 : (int) Math.round(100.0 * cacheRead / total);
        return new SessionLedger(T0, T0.plusHours(2), turns, Duration.ofHours(2), toolCalls, failed,
                0, new BigDecimal(costEur), input, 1000, cacheRead, 0, share, 0,
                "claude-opus-5", toolTime, costly, tools);
    }

    // ---------------------------------------------------------------- le seuil, qui commande

    @Nested
    @DisplayName("le seuil d'impact")
    class Threshold {

        @Test
        @DisplayName("écarte vraiment ce qui est sous le seuil — et DIT combien")
        void discardsAndCounts() {
            // Cache à 88 % : il ne reste que 2 points à gagner, très loin des 10 % de gain.
            SessionLedger tiny = ledger(10, "100.00", 120_000, 880_000, 0, 0, Duration.ZERO,
                    List.of(), List.of());

            SessionSuggestionService.Verdict verdict = service(null).examine(tiny);

            assertThat(verdict.suggestions()).isEmpty();
            assertThat(verdict.discarded()).as("écartée, et comptée — jamais diluée pour faire nombre")
                    .isEqualTo(1);
            assertThat(verdict.isClean()).isTrue();
        }

        @Test
        @DisplayName("est CONFIGURABLE : un seuil plus bas laisse passer ce que 10 % écartait")
        void isConfigurable() {
            // Cache à 60 % : viser 90 % déplace 300 000 jetons → 4,05 € sur 100 € = 4 %.
            // Écarté à 10 %, retenu à 1 % — c'est exactement ce que le réglage doit changer.
            SessionLedger modest = ledger(10, "100.00", 400_000, 600_000, 0, 0, Duration.ZERO,
                    List.of(), List.of());

            assertThat(service(10).examine(modest).suggestions()).isEmpty();
            assertThat(service(10).examine(modest).discarded()).isEqualTo(1);
            assertThat(service(1).examine(modest).suggestions()).hasSize(1);
            assertThat(service(1).examine(modest).suggestions().get(0).gainPct()).isEqualTo(4);
        }

        @Test
        @DisplayName("un seuil absent ou absurde retombe sur 10 % — le chiffre du PO")
        void defaultsToTheOwnersNumber() {
            assertThat(SessionBilanProperties.defaults().impactThresholdPct()).isEqualTo(10);
            assertThat(SessionBilanProperties.ofImpact(0, null, null).impactThresholdPct()).isEqualTo(10);
            assertThat(SessionBilanProperties.ofImpact(-5, null, null).impactThresholdPct()).isEqualTo(10);
        }
    }

    // ---------------------------------------------------------------- COÛT

    @Test
    @DisplayName("cache froid : le gain est CALCULÉ sur la grille réelle du modèle servi")
    void coldCacheIsComputedOnTheRealGrid() {
        // 1 000 000 jetons d'entrée, 0 % de cache. Viser 90 % déplace 900 000 jetons de 15 $ à 1,50 $
        // le million : 900 000 × 13,50 / 1e6 = 12,15 €. Sur 40 € de session : 30 %.
        SessionLedger cold = ledger(10, "40.00", 1_000_000, 0, 0, 0, Duration.ZERO,
                List.of(), List.of());

        List<SessionSuggestion> kept = service(null).examine(cold).suggestions();

        assertThat(kept).hasSize(1);
        SessionSuggestion s = kept.get(0);
        assertThat(s.axis()).isEqualTo(SessionSuggestion.Axis.COUT);
        assertThat(s.kind()).as("le genre, stable, est ce qu'on comptera d'un bilan à l'autre")
                .isEqualTo(SessionSuggestion.Kind.CACHE_FROID);
        assertThat(s.gainEur()).isEqualByComparingTo("12.15");
        assertThat(s.gainPct()).isEqualTo(30);
        assertThat(s.measure()).as("elle cite SA mesure, pas un adjectif")
                .contains("cache lu : 0 %").contains("10 tours");
    }

    @Test
    @DisplayName("un cache déjà chaud ne produit rien : il n'y a plus rien à gagner")
    void aWarmCacheSaysNothing() {
        SessionLedger warm = ledger(10, "40.00", 50_000, 950_000, 0, 0, Duration.ZERO,
                List.of(), List.of());
        assertThat(service(null).examine(warm).suggestions()).isEmpty();
    }

    @Test
    @DisplayName("tour hors norme : le gain est ce que la session économiserait s'il pesait comme les autres")
    void anOutlierTurnIsMeasuredAgainstTheOthers() {
        // 4 tours, 100 € : un tour à 70 €, les trois autres à 10 € chacun.
        SessionLedger outlier = ledger(4, "100.00", 10_000, 90_000, 0, 0, Duration.ZERO,
                List.of(new SessionLedger.CostlyTurn(T0, "claude-opus-5", new BigDecimal("70.00"),
                        5_000, 500, 1_000)),
                List.of());

        List<SessionSuggestion> kept = service(null).examine(outlier).suggestions();

        assertThat(kept).extracting(SessionSuggestion::axis)
                .containsExactly(SessionSuggestion.Axis.COUT);
        assertThat(kindOf(kept, 0)).isEqualTo(SessionSuggestion.Kind.TOUR_HORS_NORME);
        assertThat(kept.get(0).gainEur()).isEqualByComparingTo("60.00"); // 70 − (30/3)
        assertThat(kept.get(0).measure()).contains("70.00 €").contains("70 % de la session");
    }

    // ---------------------------------------------------------------- TEMPS

    @Test
    @DisplayName("outil dominant : l'attente vient d'un seul endroit, et la part est dite")
    void aDominantToolIsNamed() {
        SessionLedger slow = ledger(1, "1.00", 1_000, 9_000, 20, 0, Duration.ofSeconds(300),
                List.of(),
                List.of(new SessionLedger.HeavyTool("bash", 3, Duration.ofSeconds(280), 0),
                        new SessionLedger.HeavyTool("read_file", 17, Duration.ofSeconds(20), 0)));

        List<SessionSuggestion> kept = service(null).examine(slow).suggestions();

        assertThat(kept).extracting(SessionSuggestion::axis)
                .containsExactly(SessionSuggestion.Axis.TEMPS);
        assertThat(kindOf(kept, 0)).isEqualTo(SessionSuggestion.Kind.OUTIL_DOMINANT);
        assertThat(kept.get(0).gainPct()).isEqualTo(93);
        assertThat(kept.get(0).advice()).contains("bash");
        assertThat(kept.get(0).measure()).contains("280 s sur 300 s");
    }

    @Test
    @DisplayName("un temps réparti ne désigne personne")
    void spreadTimeNamesNobody() {
        SessionLedger even = ledger(1, "1.00", 1_000, 9_000, 20, 0, Duration.ofSeconds(300),
                List.of(),
                List.of(new SessionLedger.HeavyTool("bash", 10, Duration.ofSeconds(110), 0),
                        new SessionLedger.HeavyTool("grep", 10, Duration.ofSeconds(100), 0)));
        assertThat(service(null).examine(even).suggestions()).isEmpty();
    }

    // ---------------------------------------------------------------- RAISONNEMENT

    @Test
    @DisplayName("échecs répétés : la part d'appels perdus, et l'outil fautif nommé")
    void repeatedFailuresNameTheCulprit() {
        SessionLedger stuck = ledger(1, "1.00", 1_000, 9_000, 20, 6, Duration.ofSeconds(100),
                List.of(),
                List.of(new SessionLedger.HeavyTool("bash", 10, Duration.ofSeconds(60), 5),
                        new SessionLedger.HeavyTool("grep", 10, Duration.ofSeconds(40), 1)));

        List<SessionSuggestion> kept = service(null).examine(stuck).suggestions();

        assertThat(kept).extracting(SessionSuggestion::axis)
                .contains(SessionSuggestion.Axis.RAISONNEMENT);
        SessionSuggestion s = kept.stream()
                .filter(x -> x.axis() == SessionSuggestion.Axis.RAISONNEMENT).findFirst().orElseThrow();
        assertThat(s.kind()).isEqualTo(SessionSuggestion.Kind.ECHECS_REPETES);
        assertThat(s.gainPct()).isEqualTo(30); // 6 / 20
        assertThat(s.advice()).contains("bash");
        assertThat(s.measure()).isEqualTo("6 appels en échec sur 20");
    }

    // ---------------------------------------------------------------- les garde-fous

    @Test
    @DisplayName("une anecdote n'est pas un motif : sous le minimum, les détecteurs se taisent")
    void oneTurnIsAnecdoteNotPattern() {
        SessionLedger single = ledger(1, "40.00", 1_000_000, 0, 2, 2, Duration.ofSeconds(100),
                List.of(new SessionLedger.CostlyTurn(T0, "claude-opus-5", new BigDecimal("40.00"),
                        1_000_000, 500, 0)),
                List.of(new SessionLedger.HeavyTool("bash", 2, Duration.ofSeconds(100), 2)));

        assertThat(service(null).examine(single).suggestions())
                .as("ni cache froid, ni tour hors norme, ni outil dominant, ni échecs")
                .isEmpty();
    }

    @Test
    @DisplayName("relevé vide, nul, ou coût nul : aucune suggestion, aucune exception")
    void nothingToSayIsSafe() {
        assertThat(service(null).examine(null).suggestions()).isEmpty();
        assertThat(service(null).examine(SessionLedger.empty(T0, T0)).isClean()).isTrue();

        SessionLedger free = ledger(10, "0.00", 1_000_000, 0, 0, 0, Duration.ZERO,
                List.of(), List.of());
        assertThat(service(null).examine(free).suggestions()).isEmpty();
    }

    @Test
    @DisplayName("les suggestions sortent du plus fort gain au plus faible")
    void sortedByGain() {
        SessionLedger messy = ledger(10, "100.00", 1_000_000, 0, 20, 4, Duration.ofSeconds(300),
                List.of(new SessionLedger.CostlyTurn(T0, "claude-opus-5", new BigDecimal("60.00"),
                        900_000, 500, 0)),
                List.of(new SessionLedger.HeavyTool("bash", 3, Duration.ofSeconds(280), 4)));

        List<Integer> gains = service(null).examine(messy).suggestions().stream()
                .map(SessionSuggestion::gainPct).toList();

        assertThat(gains).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(gains).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("un gain ne dépasse jamais ce qui a été dépensé")
    void aGainNeverExceedsTheSpend() {
        SessionLedger cheapButCold = ledger(10, "0.01", 5_000_000, 0, 0, 0, Duration.ZERO,
                List.of(), List.of());
        assertThat(service(null).examine(cheapButCold).suggestions().get(0).gainPct())
                .isLessThanOrEqualTo(100);
    }
}
