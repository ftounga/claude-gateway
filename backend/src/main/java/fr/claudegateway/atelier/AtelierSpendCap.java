package fr.claudegateway.atelier;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * <b>Plafond en dollars d'un tour armé</b> (F-183 / SF-183-03, D3) — le budget par exécution d'une
 * routine, qui tourne sans humain.
 *
 * <p>Distinct d'{@link AtelierTurnBudget} (en tokens traités, pour tout message) : celui-ci n'existe que
 * pour un tour <b>armé</b> par son lanceur, et il parle la monnaie dans laquelle l'utilisateur a fixé son
 * budget. Les deux s'appliquent ensemble.</p>
 *
 * <p>Mêmes règles que le plafond de tokens : on renonce <b>avant</b> l'appel qui ferait franchir le
 * plafond, l'itération à venir étant majorée par la plus chère déjà observée (D-L8-2) ; la
 * <b>première itération est toujours permise</b> (D-L8-3). Le plafond <b>coupe</b>, il ne dégrade
 * jamais : ni le modèle, ni l'effort de raisonnement, ni les outils ne changent (justesse avant coût).</p>
 *
 * <p><b>F-224 / SF-224-06</b> : le plafond s'applique en dollars (la devise des coûts du fournisseur) mais se
 * <b>dit</b> en euros, au taux unique de la passerelle — l'utilisateur a saisi son budget en euros.</p>
 *
 * @param capUsd   plafond en dollars de ce tour (strictement positif)
 * @param usdToEur taux de lecture en euros ({@code app.cost.provider.usd-to-eur}) ; absent ou non positif ⇒ 0,92
 */
public record AtelierSpendCap(BigDecimal capUsd, BigDecimal usdToEur) {

    static final BigDecimal DEFAULT_USD_TO_EUR = new BigDecimal("0.92");

    public AtelierSpendCap {
        if (capUsd == null || capUsd.signum() <= 0) {
            capUsd = new BigDecimal("0.01");
        }
        if (usdToEur == null || usdToEur.signum() <= 0) {
            usdToEur = DEFAULT_USD_TO_EUR;
        }
    }

    /** Plafond au taux par défaut (formes historiques et tests). */
    public AtelierSpendCap(BigDecimal capUsd) {
        this(capUsd, null);
    }

    /** Le plafond en euros, au centime. */
    public BigDecimal capEur() {
        return capUsd.multiply(usdToEur).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Dit si l'itération suivante doit être renoncée.
     *
     * @param spentUsd     dépense du tour jusqu'ici ({@code null} = inconnue : on ne coupe pas)
     * @param projectedUsd majorant de l'itération à venir ; {@code null} ou nul tant qu'aucune n'a été
     *                     observée — la première itération passe toujours
     */
    public boolean exceededBy(BigDecimal spentUsd, BigDecimal projectedUsd) {
        if (spentUsd == null || projectedUsd == null || projectedUsd.signum() <= 0) {
            return false;
        }
        return spentUsd.add(projectedUsd).compareTo(capUsd) > 0;
    }

    /** La réponse du tour coupé net : marqué INCOMPLET, le travail fait conservé. */
    public String reply() {
        return "INCOMPLET — la routine a atteint son budget de "
                + capEur().toPlainString().replace('.', ',')
                + " € pour cette exécution ; le travail déjà fait est conservé.";
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "AtelierSpendCap[%s $]", capUsd.toPlainString());
    }
}
