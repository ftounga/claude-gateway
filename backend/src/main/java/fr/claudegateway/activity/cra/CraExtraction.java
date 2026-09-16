package fr.claudegateway.activity.cra;

import java.math.BigDecimal;

/**
 * Une ligne <b>extraite</b> d'un message de CRA par le modèle (F-124 / SF-124-03), avant tout
 * rapprochement ou validation par la Gateway.
 *
 * @param client nom du client/poste cité tel que le modèle l'a lu (jamais deviné par la Gateway)
 * @param days   jours déclarés (demi-journées admises), ou {@code null} si le modèle n'en a pas donné
 * @param month  mois {@code 'YYYY-MM'} précisé dans le message, ou {@code null} → mois courant
 */
public record CraExtraction(String client, BigDecimal days, String month) {
}
