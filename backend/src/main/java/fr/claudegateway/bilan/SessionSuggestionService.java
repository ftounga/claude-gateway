package fr.claudegateway.bilan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import fr.claudegateway.quota.ProviderPricingProperties;

/**
 * <b>Les suggestions d'usage</b> (F-155 / SF-155-02) : ce qui aurait mieux valu, sur les trois axes
 * du PO — coût, temps, raisonnement.
 *
 * <p><b>Des détecteurs, pas un modèle.</b> Un appel modèle produirait des conseils <i>plausibles</i>
 * et <i>invérifiables</i> — exactement ce que le seuil d'impact interdit. Chaque suggestion naît
 * d'un détecteur déterministe qui lit le relevé et <b>calcule</b> son gain. Conséquence : tout est
 * testable sans dépenser un jeton, et deux bilans de la même session disent la même chose.</p>
 *
 * <p><b>Le seuil est structurel.</b> Sous lui, la suggestion est écartée — et leur <b>nombre</b> est
 * rendu, plutôt que de les diluer pour faire nombre. « Rien à signaler » est une conclusion valide :
 * une session bien menée doit pouvoir s'entendre dire qu'elle l'était, sinon le bilan devient un
 * bruit qu'on cesse de lire.</p>
 */
@Service
public class SessionSuggestionService {

    /**
     * La part de cache visée pour chiffrer le manque à gagner d'un cache froid.
     *
     * <p>Volontairement <b>prudente</b> : F-134 a mesuré des projets passés de 16 % à 99 %. Viser 90
     * % plutôt que 99 % sous-estime le gain — et une suggestion qui sous-promet vaut mieux qu'une
     * suggestion qu'on prend en défaut.</p>
     */
    static final int TARGET_CACHE_SHARE = 90;

    /** Un tour qui pèse plus que cela du coût de la session sort de la norme. */
    static final int OUTLIER_TURN_SHARE = 40;

    /** Un outil qui concentre plus que cela du temps d'outils domine la session. */
    static final int DOMINANT_TOOL_SHARE = 50;

    private final ProviderPricingProperties pricing;
    private final SessionBilanProperties settings;

    public SessionSuggestionService(ProviderPricingProperties pricing,
                                    SessionBilanProperties settings) {
        this.pricing = pricing;
        this.settings = settings;
    }

    /**
     * Ce que la session aurait pu faire autrement — et ce qui a été écarté faute d'impact.
     *
     * <p>Aucune lecture en base, aucun appel fournisseur : le relevé a déjà été produit sous
     * isolation (SF-155-01).</p>
     */
    public Verdict examine(SessionLedger ledger) {
        if (ledger == null || ledger.isEmpty()) {
            return new Verdict(List.of(), 0);
        }

        List<SessionSuggestion> all = new ArrayList<>();
        coldCache(ledger).ifPresent(all::add);
        outlierTurn(ledger).ifPresent(all::add);
        dominantTool(ledger).ifPresent(all::add);
        repeatedFailures(ledger).ifPresent(all::add);

        int threshold = settings.impactThresholdPct();
        List<SessionSuggestion> kept = all.stream()
                .filter(s -> s.gainPct() >= threshold)
                .sorted(Comparator.comparingInt(SessionSuggestion::gainPct).reversed())
                .toList();
        return new Verdict(kept, all.size() - kept.size());
    }

    // ---------------------------------------------------------------- COÛT

    /**
     * <b>Cache froid</b> : la consigne système est repayée plein tarif à chaque tour.
     *
     * <p>Le gain est calculé sur la <b>grille réelle du modèle servi</b> — un tour d'Opus coûte cinq
     * fois un tour de Haiku, et un tarif moyen se tromperait dès qu'un chemin change de modèle.</p>
     */
    private java.util.Optional<SessionSuggestion> coldCache(SessionLedger ledger) {
        long totalInput = ledger.inputTokens() + ledger.cacheReadTokens();
        if (ledger.turns() < settings.minTurnsForPatterns() || totalInput == 0
                || ledger.cacheShare() >= TARGET_CACHE_SHARE
                || ledger.costEur().signum() <= 0) {
            return java.util.Optional.empty();
        }

        ProviderPricingProperties.ModelPricing grid = ledger.model() == null
                ? pricing.fallbackPricing() : pricing.pricingOf(ledger.model());
        BigDecimal perToken = grid.input().subtract(grid.cacheRead());
        if (perToken.signum() <= 0) {
            return java.util.Optional.empty(); // grille mal réglée : on se tait plutôt que d'inventer
        }

        long movable = Math.round(totalInput * (TARGET_CACHE_SHARE - ledger.cacheShare()) / 100.0);
        BigDecimal savingUsd = perToken
                .multiply(BigDecimal.valueOf(movable))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        BigDecimal savingEur = savingUsd.multiply(pricing.usdToEur()).setScale(2, RoundingMode.HALF_UP);

        // Le gain peut être dérisoire : on le rend quand même, et c'est LE SEUIL qui écarte — et qui
        // COMPTE. Abandonner ici confondrait « je n'ai rien trouvé » avec « j'ai regardé, ça ne
        // valait pas la peine », et le bilan ne pourrait plus dire combien il a écarté.
        int gain = percent(savingEur, ledger.costEur());
        return java.util.Optional.of(SessionSuggestion.ofCost(
                "Gardez le début de la conversation stable d'un tour à l'autre : ce qui change à "
                        + "chaque message empêche le cache et fait repayer la consigne entière.",
                "cache lu : " + ledger.cacheShare() + " % de l'entrée sur " + ledger.turns()
                        + " tours (visé : " + TARGET_CACHE_SHARE + " %)",
                gain, savingEur));
    }

    /** <b>Tour hors norme</b> : un seul tour pèse une part démesurée de la facture. */
    private java.util.Optional<SessionSuggestion> outlierTurn(SessionLedger ledger) {
        if (ledger.turns() < settings.minTurnsForPatterns() || ledger.costliestTurns().isEmpty()
                || ledger.costEur().signum() <= 0) {
            return java.util.Optional.empty();
        }
        SessionLedger.CostlyTurn worst = ledger.costliestTurns().get(0);
        int share = percent(worst.costEur(), ledger.costEur());
        if (share < OUTLIER_TURN_SHARE) {
            return java.util.Optional.empty();
        }

        // Ce que la session économiserait si ce tour pesait comme les autres.
        BigDecimal others = ledger.costEur().subtract(worst.costEur());
        BigDecimal average = others.divide(BigDecimal.valueOf(ledger.turns() - 1L), 2, RoundingMode.HALF_UP);
        BigDecimal saving = worst.costEur().subtract(average).max(BigDecimal.ZERO);
        int gain = percent(saving, ledger.costEur()); // même règle : c'est le seuil qui écarte
        return java.util.Optional.of(SessionSuggestion.ofCost(
                "Un seul tour a porté l'essentiel du coût : découpez ce genre de demande, ou "
                        + "donnez-lui d'emblée le fichier qu'il a dû aller chercher.",
                "le tour du " + worst.occurredAt() + " a coûté " + worst.costEur() + " € sur "
                        + ledger.costEur() + " € (" + share + " % de la session)",
                gain, saving));
    }

    // ---------------------------------------------------------------- TEMPS

    /** <b>Outil dominant</b> : l'attente vient d'un seul endroit. */
    private java.util.Optional<SessionSuggestion> dominantTool(SessionLedger ledger) {
        Duration total = ledger.totalToolTime();
        if (ledger.toolCalls() < settings.minToolCallsForPatterns()
                || ledger.heaviestTools().isEmpty() || total.isZero() || total.isNegative()) {
            return java.util.Optional.empty();
        }
        SessionLedger.HeavyTool worst = ledger.heaviestTools().get(0);
        int share = (int) Math.round(100.0 * worst.total().toMillis() / total.toMillis());
        if (share < DOMINANT_TOOL_SHARE) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(SessionSuggestion.of(SessionSuggestion.Axis.TEMPS,
                "L'attente vient presque entièrement de « " + worst.tool() + " » : lancez-le en "
                        + "arrière-plan, ou réduisez ce qu'il a à parcourir.",
                "« " + worst.tool() + " » : " + worst.calls() + " appels, "
                        + worst.total().toSeconds() + " s sur " + total.toSeconds() + " s",
                share));
    }

    // ---------------------------------------------------------------- RAISONNEMENT

    /** <b>Échecs répétés</b> : la session a tourné en rond sur un même geste. */
    private java.util.Optional<SessionSuggestion> repeatedFailures(SessionLedger ledger) {
        if (ledger.toolCalls() < settings.minToolCallsForPatterns() || ledger.failedTools() == 0) {
            return java.util.Optional.empty();
        }
        int share = (int) Math.round(100.0 * ledger.failedTools() / ledger.toolCalls());
        String culprit = ledger.heaviestTools().stream()
                .filter(t -> t.failures() > 0)
                .max(Comparator.comparingInt(SessionLedger.HeavyTool::failures))
                .map(SessionLedger.HeavyTool::tool)
                .orElse(null);
        return java.util.Optional.of(SessionSuggestion.of(SessionSuggestion.Axis.RAISONNEMENT,
                culprit == null
                        ? "Une part des appels a échoué : dites d'emblée ce qui a déjà été tenté, "
                                + "pour ne pas refaire le même chemin."
                        : "« " + culprit + " » a échoué plusieurs fois : dites d'emblée l'état réel "
                                + "de la machine, pour ne pas refaire le même chemin.",
                ledger.failedTools() + " appels en échec sur " + ledger.toolCalls(),
                share));
    }

    // ----------------------------------------------------------------

    /** Un pourcentage entier, sans jamais diviser par zéro. */
    private static int percent(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() <= 0 || part == null || part.signum() <= 0) {
            return 0;
        }
        int pct = part.multiply(BigDecimal.valueOf(100))
                .divide(whole, 0, RoundingMode.HALF_UP)
                .intValue();
        return Math.min(pct, 100); // un gain ne dépasse pas ce qu'on a dépensé
    }

    /**
     * Ce que l'examen a donné.
     *
     * @param suggestions les suggestions retenues, du plus fort gain au plus faible
     * @param discarded   combien ont été écartées faute d'impact — le dire vaut mieux que les diluer
     */
    public record Verdict(List<SessionSuggestion> suggestions, int discarded) {

        /** « Rien à signaler » — une conclusion valide, pas un échec. */
        public boolean isClean() {
            return suggestions.isEmpty();
        }
    }
}
