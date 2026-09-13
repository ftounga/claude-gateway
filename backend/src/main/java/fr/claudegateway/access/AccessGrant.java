package fr.claudegateway.access;

import java.time.OffsetDateTime;

import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.PlanCode;

/**
 * Un <b>droit offert</b> en cours (F-62), tel que l'écran du plan et le contrôle d'accès le lisent.
 * Indépendant du transport : le DTO REST s'en déduit.
 *
 * @param grantedPlanCode plan dont le droit est ouvert ({@code GOLD})
 * @param grantedUntil    terme du droit — l'unique mécanisme d'expiration
 * @param previousPlanCode plan que porte réellement l'abonnement, et qui reprend seul la main au
 *                         terme ; {@code null} si le compte n'avait aucun plan payant
 * @param label           libellé donné par l'admin à l'émission (« démo prospect Dupont »)
 * @param space           espace ouvert (F-107 / SF-107-04) ; {@code null} = tous les espaces (code d'avant F-107)
 * @param redeemedAt      consommation du code : début de l'essai et de sa réserve, ou {@code null}
 */
public record AccessGrant(
        PlanCode grantedPlanCode,
        OffsetDateTime grantedUntil,
        PlanCode previousPlanCode,
        String label,
        EntitlementSpace space,
        OffsetDateTime redeemedAt) {

    /** Droit d'avant F-107 : tous les espaces, sans début connu. */
    public AccessGrant(PlanCode grantedPlanCode, OffsetDateTime grantedUntil, PlanCode previousPlanCode,
            String label) {
        this(grantedPlanCode, grantedUntil, previousPlanCode, label, null, null);
    }

    /** Vrai si ce droit ouvre cet espace : le sien, ou tous pour un code sans espace. */
    public boolean opens(EntitlementSpace requested) {
        return space == null || space == requested;
    }
}
