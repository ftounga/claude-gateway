package fr.claudegateway.quota;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglage des alertes de dépense (F-133 / SF-133-06). Valeur de pilotage, réversible, ajustable par
 * environnement — jamais un secret.
 *
 * <p><b>Distinct de {@code app.quota.alert}</b> (F-42), qui prévient l'<b>utilisateur</b> que son
 * quota commercial <b>mensuel</b> s'épuise et lui propose une recharge. Ici on prévient
 * l'<b>administrateur</b> d'une dépense <b>hebdomadaire</b> qu'il a lui-même budgétée, et il n'y a
 * rien à racheter. Partager le réglage aurait lié deux décisions qui n'ont rien à voir.</p>
 *
 * @param nearThreshold part du budget à partir de laquelle on prévient, dans {@code ]0, 1[} ;
 *                      défaut {@code 0.8}. Toute valeur hors bornes retombe sur le défaut — un
 *                      seuil à 0 alerterait en permanence, un seuil à 1 ne préviendrait qu'une fois
 *                      le budget déjà dépassé, ce que fait déjà l'autre niveau
 */
@ConfigurationProperties(prefix = "app.cost.alert")
public record CostAlertProperties(Double nearThreshold) {

    private static final double DEFAULT_NEAR_THRESHOLD = 0.8d;

    public CostAlertProperties {
        if (nearThreshold == null || nearThreshold <= 0d || nearThreshold >= 1d) {
            nearThreshold = DEFAULT_NEAR_THRESHOLD;
        }
    }

    /** Seuil en pourcentage entier, pour l'affichage. */
    public int nearThresholdPercent() {
        return (int) Math.round(nearThreshold * 100d);
    }
}
