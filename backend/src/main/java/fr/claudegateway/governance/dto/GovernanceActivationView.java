package fr.claudegateway.governance.dto;

import java.time.OffsetDateTime;

/**
 * Un paquet actif sur un projet (F-51 / SF-51-02).
 *
 * @param pkg            le paquet, tel qu'il est publié aujourd'hui
 * @param appliedVersion la version appliquée sur ce projet — figée à l'activation
 * @param outdated       vrai si le paquet a été republié depuis : le projet applique une version
 *                       antérieure, et rien ne l'a mis à jour dans son dos
 * @param status         {@code PENDING} tant qu'il reste des fichiers à déposer, sinon {@code APPLIED}
 * @param appliedAt      dernier dépôt abouti, ou {@code null}
 */
public record GovernanceActivationView(GovernancePackageView pkg, int appliedVersion,
        boolean outdated, String status, OffsetDateTime appliedAt) {
}
