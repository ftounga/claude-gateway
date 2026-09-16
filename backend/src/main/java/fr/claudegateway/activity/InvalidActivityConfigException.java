package fr.claudegateway.activity;

/**
 * Levée quand un réglage de suivi d'activité est invalide (F-124 / SF-124-01) : un TJM négatif ou
 * hors bornes, un mois de départ mal formé. Mappée en 400 — c'est une faute de saisie, pas une panne.
 */
public class InvalidActivityConfigException extends RuntimeException {

    public InvalidActivityConfigException(String message) {
        super(message);
    }
}
