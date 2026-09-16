package fr.claudegateway.atelier.deposit;

import java.util.List;

/**
 * Réponse d'un dépôt (F-115 / SF-115-01) : un chemin par fichier reçu, tel que l'agent le lira, avec
 * sa taille et la cible atteinte. Le fil (SF-115-02) l'affiche « fichier déposé : … — 2,3 Mo » ; le
 * binaire, lui, ne remonte jamais.
 */
public record DepositResponse(List<DepositedFile> files) {

    /** @param target {@code HOSTED} (workspace S3) ou {@code RUNNER} (poste). */
    public record DepositedFile(String path, long size, String target) {
    }
}
