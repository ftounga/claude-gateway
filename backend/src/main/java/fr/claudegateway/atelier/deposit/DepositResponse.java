package fr.claudegateway.atelier.deposit;

import java.util.List;
import java.util.UUID;

/**
 * Réponse d'un dépôt (F-115 / SF-115-01) : un chemin par fichier reçu, tel que l'agent le lira, avec
 * sa taille et la cible atteinte. Le fil (SF-115-02) l'affiche « fichier déposé : … — 2,3 Mo » ; le
 * binaire, lui, ne remonte jamais.
 */
public record DepositResponse(List<DepositedFile> files) {

    /**
     * @param id     identifiant du dépôt persisté (F-169 / SF-169-02) : c'est ce que le message renvoie
     *               dans {@code attachedDepositIds} pour joindre EXACTEMENT ce dépôt.
     * @param target {@code HOSTED} (workspace S3) ou {@code RUNNER} (poste).
     */
    public record DepositedFile(UUID id, String path, long size, String target) {
    }
}
