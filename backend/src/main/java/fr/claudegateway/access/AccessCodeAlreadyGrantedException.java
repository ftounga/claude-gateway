package fr.claudegateway.access;

/**
 * Consommation refusée : un accès offert est déjà en cours sur ce compte (F-62). Mappée en
 * <b>409</b> ({@code access_code_already_granted}).
 *
 * <p>Le <b>cumul de codes est hors périmètre</b> (PRODUCT_SPEC F-62). Le refuser explicitement vaut
 * mieux que de le laisser arriver : sans ce garde-fou, deux codes enchaînés produiraient une durée
 * que personne n'a décidée, et une trace où l'on ne saurait plus lequel gouverne.</p>
 */
public class AccessCodeAlreadyGrantedException extends RuntimeException {

    public AccessCodeAlreadyGrantedException() {
        super("Un accès offert est déjà en cours sur ce compte.");
    }
}
