package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Valeurs de repli des réglages de l'Atelier — en particulier le plafond d'allers-retours de la
 * boucle d'agent (F-28 / SF-28-19), calibré sur l'usage réel et borné pour rester lisible.
 */
class AtelierPropertiesTest {

    private AtelierProperties withMaxIterations(Integer value) {
        return new AtelierProperties(null, null, null, null, null, null, value, null, null, null, null, null, true);
    }

    @Test
    void defaultsToThirtySteps() {
        assertThat(withMaxIterations(null).maxIterations()).isEqualTo(30);
    }

    @Test
    void fallsBackToTheDefaultWhenNonPositive() {
        assertThat(withMaxIterations(0).maxIterations()).isEqualTo(30);
        assertThat(withMaxIterations(-5).maxIterations()).isEqualTo(30);
    }

    @Test
    void honoursAConfiguredValue() {
        assertThat(withMaxIterations(12).maxIterations()).isEqualTo(12);
        assertThat(withMaxIterations(50).maxIterations()).isEqualTo(50);
    }

    @Test
    void capsUnreasonableValuesAtOneHundred() {
        // Au-delà, le budget de temps du tour aurait tranché de toute façon.
        assertThat(withMaxIterations(150).maxIterations()).isEqualTo(100);
    }

    // ------------------------------------------------- SF-39-10 : modèle et effort de la boucle

    private AtelierProperties withReasoning(String model, String effort) {
        return new AtelierProperties(null, null, null, null, null, null, null, model, effort, null, null, null, true);
    }

    @Test
    void defaultsToTheHarnessModelAndTheProviderEffort() {
        AtelierProperties properties = withReasoning(null, null);
        assertThat(properties.model()).isEqualTo("claude-opus-5");
        // `high` est déjà le défaut du fournisseur : l'écrire ne change rien, mais le rend réglable.
        assertThat(properties.effort()).isEqualTo("high");
    }

    @Test
    void honoursAConfiguredModelAndEffort() {
        AtelierProperties properties = withReasoning("claude-opus-4-8", "xhigh");
        assertThat(properties.model()).isEqualTo("claude-opus-4-8");
        assertThat(properties.effort()).isEqualTo("xhigh");
    }

    @Test
    void fallsBackToTheDefaultEffortWhenTheConfiguredOneIsUnknown() {
        // Une faute de frappe en configuration ne doit pas empêcher un tour de partir.
        assertThat(withReasoning(null, "turbo").effort()).isEqualTo("high");
        assertThat(withReasoning(null, "  ").effort()).isEqualTo("high");
    }

    @Test
    void fallsBackToTheDefaultModelWhenTheConfiguredOneIsBlank() {
        assertThat(withReasoning("   ", null).model()).isEqualTo("claude-opus-5");
    }

    // ------------------------------------------- SF-39-12 : écartement des résultats périmés

    @Test
    void prunesStaleToolResultsByDefault() {
        assertThat(withMaxIterations(null).contextPruning()).isTrue();
    }

    @Test
    void honoursTheCircuitBreaker() {
        // Capacité beta dans un chemin critique : le coupe-circuit rétablit le service sans
        // livraison si le fournisseur retirait l'option (D-L6-11).
        AtelierProperties off =
                new AtelierProperties(null, null, null, null, null, null, null, null, null, false, null, null, true);
        assertThat(off.contextPruning()).isFalse();
    }

    /** Plafond de consommation d'un message (F-39 / SF-39-15). */
    private static AtelierProperties withTurnCap(Long value) {
        return new AtelierProperties(null, null, null, null, null, null, null, null, null, null, value, null, true);
    }

    @Test
    void defaultsToTheMeasuredTurnCeiling() {
        // Calibré sur l'usage réel du cadrage : contexte maximal observé 900 519 tokens, tour de
        // 30 itérations estimé à ~1,35 M tokens d'entrée. Un tour ordinaire ne le voit jamais.
        assertThat(withTurnCap(null).maxTurnTokens())
                .isEqualTo(AtelierProperties.DEFAULT_MAX_TURN_TOKENS);
        assertThat(withTurnCap(0L).maxTurnTokens()).isEqualTo(AtelierProperties.DEFAULT_MAX_TURN_TOKENS);
        assertThat(withTurnCap(-1L).maxTurnTokens()).isEqualTo(AtelierProperties.DEFAULT_MAX_TURN_TOKENS);
    }

    @Test
    void capsTheTurnCeilingSoItStaysMeaningful() {
        // Au-delà, le plafond d'étapes et le budget de temps auraient tranché de toute façon :
        // mieux vaut une borne lisible qu'un plafond qui n'a jamais l'occasion de s'appliquer.
        assertThat(withTurnCap(50_000_000L).maxTurnTokens())
                .isEqualTo(AtelierProperties.MAX_TURN_TOKENS_CEILING);
        assertThat(withTurnCap(250_000L).maxTurnTokens()).isEqualTo(250_000L);
    }

    @Test
    void leavesTheOtherLimitsUntouched() {
        AtelierProperties properties = withMaxIterations(30);
        assertThat(properties.maxEntries()).isEqualTo(2000);
        assertThat(properties.maxFileBytes()).isEqualTo(2L * 1024 * 1024);
        assertThat(properties.storage()).isEqualTo("in-memory");
    }

    @Test
    void delegationsDefaultToFivePerMessage() {
        // Défaut relevé de 3 à 5 (F-148 / SF-148-01) : une investigation multi-zones tient en un tour ;
        // au-delà, c'est le travail principal qu'il faut redécouper (F-39 / SF-39-14).
        assertThat(new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, true).maxDelegations()).isEqualTo(5);
    }

    @Test
    void delegationsCanBeDisabledEntirely() {
        // Zéro est une valeur légitime : elle retire l'outil de la liste déclarée au modèle.
        assertThat(new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                null, 0, true).maxDelegations()).isZero();
    }

    @Test
    void negativeDelegationsFallBackToTheDefault() {
        assertThat(new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                null, -2, true).maxDelegations()).isEqualTo(5);
    }

    @Test
    void storageExecutionIsClosedByDefault() {
        // Le défaut décrit ce que la production doit faire (F-39 / SF-39-16, D2).
        assertThat(new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, null).storageExecution()).isFalse();
    }

    @Test
    void storageExecutionCanBeReopenedWithoutADeployment() {
        assertThat(new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, true).storageExecution()).isTrue();
    }

    // ------------------------------------------- F-116 / SF-116-01 : appel modèle en flux

    @Test
    void streamsByDefault() {
        // Absent => flux actif : le texte défile mot à mot, comme Claude Code (F-116).
        assertThat(withMaxIterations(null).streaming()).isTrue();
        assertThat(new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null).streaming()).isTrue();
    }

    @Test
    void streamingCanBeDisabledWithoutADeployment() {
        // Coupe-circuit : `false` rétablit l'appel complet (texte en fin de tour) par variable
        // d'environnement, sans livraison.
        AtelierProperties off = new AtelierProperties(null, null, null, null, null, null, null, null,
                null, null, null, null, null, false);
        assertThat(off.streaming()).isFalse();
    }

    // ------------------------------------------- F-118 / SF-118-01 : effort adaptatif à l'étape

    /** Réglages d'effort adaptatif (step-effort, adaptive-effort) — les deux derniers composants. */
    private static AtelierProperties withAdaptiveEffort(String stepEffort, Boolean adaptiveEffort) {
        return new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, stepEffort, adaptiveEffort);
    }

    @Test
    void continuationEffortDefaultsToMediumAndAdaptiveIsOn() {
        // Défaut porté de `low` à `medium` (F-148 / SF-148-01) : la continuation tient la trajectoire
        // sans « réfléchir fort » comme le premier tour ; l'effort suit toujours l'étape.
        AtelierProperties properties = withAdaptiveEffort(null, null);
        assertThat(properties.stepEffort()).isEqualTo("medium");
        assertThat(properties.adaptiveEffort()).isTrue();
        // Le constructeur de compatibilité (sans ces deux réglages) applique les mêmes défauts.
        AtelierProperties legacy = new AtelierProperties(null, null, null, null, null, null, null,
                null, null, null, null, null, true);
        assertThat(legacy.stepEffort()).isEqualTo("medium");
        assertThat(legacy.adaptiveEffort()).isTrue();
    }

    @Test
    void continuationEffortFallsBackToMediumWhenUnknownOrBlank() {
        // Même repli que `effort` : une faute de config ne casse pas les tours (F-118, D-118-1).
        assertThat(withAdaptiveEffort("turbo", null).stepEffort()).isEqualTo("medium");
        assertThat(withAdaptiveEffort("  ", null).stepEffort()).isEqualTo("medium");
    }

    @Test
    void continuationEffortHonoursAConfiguredValue() {
        // Réglable dans les deux sens : `low` reste possible (repli), et l'on peut monter plus haut.
        assertThat(withAdaptiveEffort("low", null).stepEffort()).isEqualTo("low");
        assertThat(withAdaptiveEffort("high", null).stepEffort()).isEqualTo("high");
    }

    @Test
    void adaptiveEffortCanBeDisabledWithoutADeployment() {
        // Coupe-circuit : `false` rétablit l'effort normal à chaque étape (comportement d'avant F-118).
        assertThat(withAdaptiveEffort(null, false).adaptiveEffort()).isFalse();
    }

    // ------------------------------------------- F-118 / SF-118-03 : budget de temps configurable

    /** Budget de temps du message (F-118 / SF-118-03) — le 17e et dernier composant. */
    private static AtelierProperties withTurnBudget(Duration value) {
        return new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, value);
    }

    @Test
    void turnBudgetDefaultsToTenMinutes() {
        // Absent => 10 min, le comportement livré : le poser en config ne change rien sans réglage.
        assertThat(withTurnBudget(null).turnBudget()).isEqualTo(Duration.ofMinutes(10));
        assertThat(withTurnBudget(null).turnBudget()).isEqualTo(AtelierProperties.DEFAULT_TURN_BUDGET);
        // Le constructeur de compatibilité (sans ce réglage) applique le même défaut.
        AtelierProperties legacy = new AtelierProperties(null, null, null, null, null, null, null,
                null, null, null, null, null, true, null, null, null);
        assertThat(legacy.turnBudget()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void turnBudgetHonoursAConfiguredValue() {
        // La production porte le budget à 60 min par APP_ATELIER_TURN_BUDGET=PT60M.
        assertThat(withTurnBudget(Duration.ofMinutes(60)).turnBudget()).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void turnBudgetFallsBackToTheDefaultWhenZeroOrNegative() {
        // Une faute de config ne doit ni couper les tours à zéro, ni les rendre infinis.
        assertThat(withTurnBudget(Duration.ZERO).turnBudget()).isEqualTo(Duration.ofMinutes(10));
        assertThat(withTurnBudget(Duration.ofMinutes(-5)).turnBudget()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void turnBudgetIsCappedAtTheHardCeiling() {
        // Au-delà, le plafond d'itérations et la durée de vie du flux SSE auraient tranché de toute
        // façon : mieux vaut une borne lisible qu'un plafond sans effet.
        assertThat(withTurnBudget(Duration.ofHours(10)).turnBudget()).isEqualTo(Duration.ofHours(2));
        assertThat(withTurnBudget(Duration.ofHours(10)).turnBudget())
                .isEqualTo(AtelierProperties.TURN_BUDGET_CEILING);
    }

    // ------------------------------------------- F-119 / SF-119-01 : explore-effort & escalate-on-signal

    /** Réglages F-119 (18e et 19e composants) : effort d'exploration et ré-escalade sur signal. */
    private static AtelierProperties withF119(String exploreEffort, Boolean escalateOnSignal) {
        return new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, exploreEffort, escalateOnSignal);
    }

    @Test
    void exploreEffortDefaultsToLowAndEscalationIsOn() {
        AtelierProperties properties = withF119(null, null);
        assertThat(properties.exploreEffort()).isEqualTo("low");
        assertThat(properties.exploreEffort()).isEqualTo(AtelierProperties.DEFAULT_EXPLORE_EFFORT);
        assertThat(properties.escalateOnSignal()).isTrue();
        // Le constructeur de compatibilité (sans ces réglages) applique les mêmes défauts.
        AtelierProperties legacy = new AtelierProperties(null, null, null, null, null, null, null,
                null, null, null, null, null, true, null, null, null, null);
        assertThat(legacy.exploreEffort()).isEqualTo("low");
        assertThat(legacy.escalateOnSignal()).isTrue();
    }

    @Test
    void exploreEffortFallsBackToLowWhenUnknownOrBlank() {
        assertThat(withF119("turbo", null).exploreEffort()).isEqualTo("low");
        assertThat(withF119("  ", null).exploreEffort()).isEqualTo("low");
    }

    @Test
    void exploreEffortHonoursAConfiguredValue() {
        assertThat(withF119("medium", null).exploreEffort()).isEqualTo("medium");
        assertThat(withF119("xhigh", null).exploreEffort()).isEqualTo("xhigh");
    }

    @Test
    void escalationCanBeDisabledWithoutADeployment() {
        assertThat(withF119(null, false).escalateOnSignal()).isFalse();
        assertThat(withF119(null, true).escalateOnSignal()).isTrue();
    }

    // ------------------------------------------- F-119 / SF-119-03 : fenêtre de rejeu des trajectoires

    /** Fenêtre de rejeu (20e composant) : la profondeur d'historique rejoué avec ses outils. */
    private static AtelierProperties withReplayedTraceTurns(Integer value) {
        return new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, value);
    }

    @Test
    void replayedTraceTurnsDefaultsToTwelve() {
        assertThat(withReplayedTraceTurns(null).replayedTraceTurns()).isEqualTo(12);
        assertThat(withReplayedTraceTurns(null).replayedTraceTurns())
                .isEqualTo(AtelierProperties.DEFAULT_REPLAYED_TRACE_TURNS);
        // Le constructeur de compatibilité (sans ce réglage) applique le même défaut.
        AtelierProperties legacy = new AtelierProperties(null, null, null, null, null, null, null,
                null, null, null, null, null, true, null, null, null, null, null, null);
        assertThat(legacy.replayedTraceTurns()).isEqualTo(12);
    }

    @Test
    void replayedTraceTurnsFallsBackToTheDefaultWhenNonPositive() {
        assertThat(withReplayedTraceTurns(0).replayedTraceTurns()).isEqualTo(12);
        assertThat(withReplayedTraceTurns(-3).replayedTraceTurns()).isEqualTo(12);
    }

    @Test
    void replayedTraceTurnsHonoursAConfiguredValueAndIsCapped() {
        assertThat(withReplayedTraceTurns(15).replayedTraceTurns()).isEqualTo(15);
        assertThat(withReplayedTraceTurns(200).replayedTraceTurns())
                .isEqualTo(AtelierProperties.MAX_REPLAYED_TRACE_TURNS);
    }

    // ------------------------------------------- F-119 / SF-119-05 : aide-mémoire d'état de fichier

    /** Aide-mémoire d'état de fichier (21ᵉ composant). */
    private static AtelierProperties withFileStateHints(Boolean value) {
        return new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, value, null);
    }

    /** Effort transmis par message (22ᵉ et dernier composant, F-134 / SF-134-05). */
    private static AtelierProperties withPerMessageEffort(Boolean value) {
        return new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, value);
    }

    @Test
    void perMessageEffortIsOnByDefaultAndCanBeTurnedOff() {
        // Le réglage n'existe que pour revenir en arrière si la bêta du fournisseur venait à
        // fermer : un cache inefficace, jamais un service en panne (F-134 / SF-134-05).
        assertThat(withPerMessageEffort(null).perMessageEffort()).isTrue();
        assertThat(withPerMessageEffort(Boolean.FALSE).perMessageEffort()).isFalse();
    }

    @Test
    void fileStateHintsDefaultToOn() {
        assertThat(withFileStateHints(null).fileStateHints()).isTrue();
        // Le constructeur de compatibilité (sans ce réglage) applique le même défaut.
        AtelierProperties legacy = new AtelierProperties(null, null, null, null, null, null, null,
                null, null, null, null, null, true, null, null, null, null, null, null, null);
        assertThat(legacy.fileStateHints()).isTrue();
    }

    @Test
    void fileStateHintsCanBeDisabledWithoutADeployment() {
        assertThat(withFileStateHints(false).fileStateHints()).isFalse();
        assertThat(withFileStateHints(true).fileStateHints()).isTrue();
    }

    // ------------------------------------------- F-39 / SF-39-21 : parallélisme d'exploration

    /** Parallélisme d'exploration (23ᵉ et dernier composant, F-39 / SF-39-21). */
    private static AtelierProperties withExploreParallelism(Integer value) {
        return new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, value);
    }

    @Test
    void exploreParallelismDefaultsToThree() {
        assertThat(withExploreParallelism(null).exploreParallelism()).isEqualTo(3);
        assertThat(withExploreParallelism(null).exploreParallelism())
                .isEqualTo(AtelierProperties.DEFAULT_EXPLORE_PARALLELISM);
        // Le constructeur de compatibilité (forme F-134, sans ce réglage) applique le même défaut.
        AtelierProperties legacy = new AtelierProperties(null, null, null, null, null, null, null,
                null, null, null, null, null, true, null, null, null, null, null, null, null, null, null);
        assertThat(legacy.exploreParallelism()).isEqualTo(3);
    }

    @Test
    void exploreParallelismFallsBackToTheDefaultWhenBelowOne() {
        // Une faute de config ne doit pas couper la concurrence à zéro (elle repart du défaut).
        assertThat(withExploreParallelism(0).exploreParallelism()).isEqualTo(3);
        assertThat(withExploreParallelism(-4).exploreParallelism()).isEqualTo(3);
    }

    @Test
    void exploreParallelismHonoursAConfiguredValueAndIsCapped() {
        assertThat(withExploreParallelism(1).exploreParallelism()).isEqualTo(1);
        assertThat(withExploreParallelism(5).exploreParallelism()).isEqualTo(5);
        // Au-delà, on lancerait plus de sous-boucles que de délégations utiles : borne lisible.
        assertThat(withExploreParallelism(100).exploreParallelism())
                .isEqualTo(AtelierProperties.MAX_EXPLORE_PARALLELISM);
    }

    @Test
    void theFullCanonicalConstructorCarriesTheParallelism() {
        // La forme SF-39-21 (23 composants) est honorée telle quelle (compat sans exploreModel).
        AtelierProperties full = new AtelierProperties(null, null, null, null, null, null, null,
                null, null, null, null, null, true, null, null, null, null, null, null, null, null, null, 7);
        assertThat(full.exploreParallelism()).isEqualTo(7);
    }

    // ------------------------------------------- F-149 / SF-149-03 : modèle de la sous-boucle

    @Test
    void exploreModelIsNullByDefaultSoTheSubLoopFollowsTheMainModel() {
        // Repli SÛR : le record ne force pas Sonnet (le défaut de prod vit dans application.yml) ;
        // null => la sous-boucle suit le modèle principal (repli appliqué côté service).
        assertThat(withMaxIterations(null).exploreModel()).isNull();
        // Le constructeur de compatibilité SF-39-21 (23 composants) laisse aussi exploreModel à null.
        AtelierProperties legacy = new AtelierProperties(null, null, null, null, null, null, null,
                null, null, null, null, null, true, null, null, null, null, null, null, null, null, null, 3);
        assertThat(legacy.exploreModel()).isNull();
    }

    @Test
    void theFullCanonicalConstructorCarriesTheExploreModel() {
        // La forme complète (24 composants) porte le modèle d'exploration tel quel.
        AtelierProperties full = new AtelierProperties(null, null, null, null, null, null, null,
                null, null, null, null, null, true, null, null, null, null, null, null, null, null, null, 3,
                AtelierProperties.DEFAULT_EXPLORE_MODEL);
        assertThat(full.exploreModel()).isEqualTo("claude-sonnet-5");
    }

    // ------------------------------------------- F-121 / SF-121-08 : plafond de la ré-escalade

    /** Plafond de ré-escalade (26ᵉ et dernier composant, F-121 / SF-121-08). */
    private static AtelierProperties withEscalateEffort(String effort, String escalateEffort) {
        return new AtelierProperties(null, null, null, null, null, null, null, null, effort, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, escalateEffort);
    }

    @Test
    void escalateEffortFollowsTheNormalEffortWhenNotExpressed() {
        // Repli SÛR : sans réglage, « remonter » l'effort veut dire revenir au normal — exactement le
        // comportement d'avant SF-121-08.
        assertThat(withEscalateEffort(null, null).escalateEffort()).isEqualTo("high");
        assertThat(withEscalateEffort(null, null).escalateEffort())
                .isEqualTo(AtelierProperties.DEFAULT_EFFORT);
    }

    @Test
    void escalateEffortFollowsAnAlreadyRaisedNormalEffort() {
        // Le repli vise `effort`, PAS la constante `high` : une exploitation qui a déjà relevé son
        // régime ordinaire ne doit pas se retrouver avec une escalade PLUS BASSE que lui.
        assertThat(withEscalateEffort("max", null).escalateEffort()).isEqualTo("max");
        assertThat(withEscalateEffort("xhigh", "").escalateEffort()).isEqualTo("xhigh");
    }

    @Test
    void escalateEffortFallsBackWhenTheValueIsUnknown() {
        // Même tolérance que `effort`/`step-effort` : une faute de frappe ne fait échouer ni le
        // démarrage ni les tours, elle retombe sur le repli.
        assertThat(withEscalateEffort(null, "tres-fort").escalateEffort()).isEqualTo("high");
        assertThat(withEscalateEffort(null, "XHIGH").escalateEffort()).isEqualTo("high");
        assertThat(withEscalateEffort("medium", "  ").escalateEffort()).isEqualTo("medium");
    }

    @Test
    void escalateEffortHonoursAConfiguredCeiling() {
        // Le levier : l'incident réfléchit plus fort que le régime ordinaire.
        assertThat(withEscalateEffort(null, "xhigh").escalateEffort()).isEqualTo("xhigh");
        assertThat(withEscalateEffort(null, "max").escalateEffort()).isEqualTo("max");
        // Un plafond plus BAS que le régime ordinaire est honoré tel quel : réglage d'exploitation
        // assumé, jamais corrigé en silence.
        assertThat(withEscalateEffort("high", "low").escalateEffort()).isEqualTo("low");
    }

    @Test
    void theCompatibilityConstructorLeavesTheCeilingOnItsFallback() {
        // La forme SF-150-04 (25 composants, jusqu'à taskModel) reste appelable et applique le repli.
        AtelierProperties legacy = new AtelierProperties(null, null, null, null, null, null, null,
                null, "xhigh", null, null, null, true, null, null, null, null, null, null, null, null,
                null, 3, AtelierProperties.DEFAULT_EXPLORE_MODEL, "claude-sonnet-5");
        assertThat(legacy.taskModel()).isEqualTo("claude-sonnet-5");
        assertThat(legacy.escalateEffort()).isEqualTo("xhigh");
        // Et les formes plus anciennes aussi (celle de SF-39-10, 13 composants).
        assertThat(withReasoning(null, null).escalateEffort()).isEqualTo("high");
    }
}
