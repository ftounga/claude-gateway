package fr.claudegateway.runner;

/**
 * Levée lorsqu'un code d'appairage est inconnu, expiré ou déjà consommé (F-38 / SF-38-01). Mappée en
 * <b>401</b> ({@code pairing_invalid}) : réponse générique qui ne distingue pas les trois cas (pas
 * d'oracle sur l'existence d'un code).
 */
public class PairingInvalidException extends RuntimeException {

    public PairingInvalidException() {
        super("Code d'appairage invalide.");
    }
}
