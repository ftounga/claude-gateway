package fr.claudegateway.access;

import java.time.OffsetDateTime;

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
 */
public record AccessGrant(
        PlanCode grantedPlanCode,
        OffsetDateTime grantedUntil,
        PlanCode previousPlanCode,
        String label) {
}
