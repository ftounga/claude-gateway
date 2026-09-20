package fr.claudegateway.quota;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import fr.claudegateway.admin.AdminService;

/**
 * Ce que l'écran a le droit d'afficher du coût d'un tour (F-133 / SF-133-02).
 *
 * <p><b>Le montant ne sort que pour l'administrateur.</b> Et il ne sort pas « masqué » : quand
 * l'appelant n'est pas administrateur, la valeur est {@code null} et ne quitte donc jamais le
 * serveur. Un coût caché en CSS resterait lisible dans le flux réseau — or F-133 est une fonction
 * d'administration, pas une information cliente : un consultant n'a pas à savoir ce que sa mission
 * coûte à la plateforme.</p>
 *
 * <p><b>La décision « qui est administrateur » n'est pas réécrite ici</b> : elle est déléguée à
 * {@link AdminService}, qui la porte déjà pour la console. En écrire une seconde reviendrait à en
 * avoir deux, et un jour deux qui divergent.</p>
 *
 * <p><b>L'euro est une commodité de lecture</b>, pas la vérité : le fournisseur facture en dollars.
 * Le taux vit en configuration et le montant affiché en dépend — c'est assumé et documenté, plutôt
 * qu'un arrondi caché dans le code.</p>
 */
@Component
public class TurnCostView {

    private static final Logger log = LoggerFactory.getLogger(TurnCostView.class);

    /** En dessous, un montant exact induirait en erreur : « 0,00 € » se lit « gratuit ». */
    private static final BigDecimal ONE_CENT = new BigDecimal("0.01");

    private final AdminService adminService;
    private final ProviderPricingProperties pricing;

    public TurnCostView(AdminService adminService, ProviderPricingProperties pricing) {
        this.adminService = adminService;
        this.pricing = pricing;
    }

    /**
     * Le montant à afficher pour le tour, ou {@code null} s'il ne doit pas être montré — appelant
     * non administrateur, coût nul, ou calcul impossible.
     *
     * <p><b>Ne lève jamais.</b> Le tour est déjà rendu quand cette méthode s'exécute : un problème
     * d'affichage de coût ne peut pas le faire échouer.</p>
     *
     * @param costUsd coût réel du tour en dollars, ou {@code null}
     * @return le montant formaté en euros (ex. {@code "0,42 €"}, {@code "< 0,01 €"}), ou {@code null}
     */
    public String labelFor(BigDecimal costUsd) {
        try {
            if (costUsd == null || costUsd.signum() <= 0 || !isAdmin()) {
                return null;
            }
            BigDecimal eur = costUsd.multiply(pricing.usdToEur());
            if (eur.compareTo(ONE_CENT) < 0) {
                return "< 0,01 €";
            }
            return eur.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',') + " €";
        } catch (RuntimeException failure) {
            log.warn("Coût du tour non affiché ({}) : le tour, lui, est intact.",
                    failure.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Convertit un montant du dollar vers l'euro, <b>sans</b> la garde d'affichage
     * (F-133 / SF-133-06).
     *
     * <p>Séparée de {@link #labelFor(BigDecimal)} parce que les deux répondent à deux questions :
     * celle-là demande « a-t-on le droit de montrer ce montant, et comment l'écrire », celle-ci
     * « combien cela fait-il en euros ». Les alertes ont déjà passé leur propre garde et n'ont
     * besoin que de la seconde.</p>
     */
    public BigDecimal toEur(BigDecimal costUsd) {
        if (costUsd == null || costUsd.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return costUsd.multiply(pricing.usdToEur()).setScale(2, RoundingMode.HALF_UP);
    }

    /** Vrai si l'appelant courant est administrateur. Jamais d'exception : l'absence vaut non. */
    private boolean isAdmin() {
        try {
            adminService.assertAdmin();
            return true;
        } catch (RuntimeException notAdmin) {
            return false;
        }
    }
}
