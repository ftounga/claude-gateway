package fr.claudegateway.quota;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration de l'alerte de quota (F-42). Comme {@link QuotaProperties}, ces valeurs décrivent
 * une structure commerciale — à partir de quelle consommation on prévient, et quel pack de recharge
 * on propose — jamais un secret : elles sont externalisées et ajustables par environnement sans
 * changement de code.
 *
 * @param threshold  fraction du quota effectif à partir de laquelle l'utilisateur est prévenu,
 *                   dans {@code ]0, 1]} ; défaut {@code 0.8}. Un seuil de {@code 1.0} est légal et
 *                   signifie « préviens-moi quand le quota est atteint ». Toute valeur hors bornes
 *                   (absente, nulle, négative, supérieure à 1) retombe sur le défaut.
 * @param topUpPack  code du pack de recharge proposé depuis l'alerte (voir
 *                   {@code TopUpCatalog}) ; défaut {@code STANDARD}. Un code inconnu du catalogue
 *                   n'empêche pas l'alerte : elle informe, sans bouton de recharge.
 */
@ConfigurationProperties(prefix = "app.quota.alert")
public record QuotaAlertProperties(Double threshold, String topUpPack) {

    /** Seuil par défaut : 80 % du quota effectif de la période. */
    private static final double DEFAULT_THRESHOLD = 0.8d;

    /**
     * Pack recommandé par défaut : la recharge de 1 M jetons plutôt que le pass journée de 200 k.
     * L'alerte se déclenche à 80 % d'un quota <b>mensuel</b> : 200 k ne tiendraient souvent pas
     * jusqu'à la fin du mois.
     */
    private static final String DEFAULT_TOP_UP_PACK = "STANDARD";

    public QuotaAlertProperties {
        if (threshold == null || threshold <= 0d || threshold > 1d) {
            threshold = DEFAULT_THRESHOLD;
        }
        if (topUpPack == null || topUpPack.isBlank()) {
            topUpPack = DEFAULT_TOP_UP_PACK;
        } else {
            topUpPack = topUpPack.trim();
        }
    }

    /** Seuil exprimé en pourcentage entier, pour l'affichage (ex. {@code 0.8} → {@code 80}). */
    public int thresholdPercent() {
        return (int) Math.round(threshold * 100d);
    }
}
