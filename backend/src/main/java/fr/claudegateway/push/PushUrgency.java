package fr.claudegateway.push;

/**
 * L'<b>urgence Web Push</b> (RFC 8030 §5.3, en-tête {@code Urgency}) — F-217 / SF-217-01. Un service push
 * peut différer une urgence {@code normal} quand l'appareil économise son énergie (Android en veille) ;
 * {@code high} le réveille aussitôt. Énumération propre au domaine : l'émetteur ne dépend pas de la
 * bibliothèque de transport.
 */
public enum PushUrgency {
    VERY_LOW, LOW, NORMAL, HIGH
}
