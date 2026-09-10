package fr.claudegateway.governance;

/**
 * Où en est l'application d'un paquet sur un projet (F-51 / SF-51-02).
 *
 * <p>La distinction n'existe que pour les <b>fichiers</b>. Les règles et les contrôles, eux,
 * s'appliquent dès l'activation : ils n'ont besoin d'aucun disque. Le dépôt d'un gabarit ou d'un
 * skill, lui, peut attendre — un projet en cible runner vit sur une machine qui peut être éteinte, et
 * refuser l'activation reviendrait à exiger que la machine soit allumée pour composer son catalogue
 * (décision D2 du cadrage).</p>
 */
public enum GovernanceActivationStatus {

    /** Le paquet s'applique ; il reste des fichiers à déposer, faute d'avoir pu écrire. */
    PENDING,

    /** Tout ce que le paquet apporte est en place : plus rien à écrire. */
    APPLIED
}
