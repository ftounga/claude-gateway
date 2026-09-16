package fr.claudegateway.activity.cra;

import java.math.BigDecimal;

/**
 * Une ligne <b>extraite</b> d'un message de CRA par le modèle (F-124 / SF-124-03, enrichie en
 * SF-124-04), avant tout rapprochement, conversion ou validation par la Gateway.
 *
 * <p>Une ligne porte SOIT un nombre de {@code days} (« Free 20 jours »), SOIT une {@link CraRange}
 * (« du 10 à la fin du mois ») que la Gateway convertit elle-même en jours ouvrés. Si les deux sont
 * présents, {@code days} l'emporte (rétrocompatibilité).</p>
 *
 * @param client nom du client/poste cité tel que le modèle l'a lu (jamais deviné par la Gateway)
 * @param days   jours déclarés (demi-journées admises), ou {@code null} si une plage est donnée
 * @param month  mois {@code 'YYYY-MM'} précisé dans le message, ou {@code null} → mois courant
 * @param range  plage de dates décrite par le modèle, ou {@code null} si {@code days} est donné
 */
public record CraExtraction(String client, BigDecimal days, String month, CraRange range) {

    /** Forme historique sans plage (SF-124-03) — conservée pour les appelants et tests existants. */
    public CraExtraction(String client, BigDecimal days, String month) {
        this(client, days, month, null);
    }
}
