package fr.claudegateway.access.dto;

import java.time.OffsetDateTime;
import java.util.Optional;

import fr.claudegateway.access.AccessGrant;

/**
 * Le droit offert de l'utilisateur courant (F-62 / SF-62-01), tel que l'écran du plan le lit.
 *
 * <p>C'est le <b>serveur</b> qui tranche {@link #active} : l'écran ne compare jamais {@code now} à
 * une date pour décider d'un droit — une horloge de navigateur décalée ne doit pas ouvrir ni fermer
 * un accès.</p>
 *
 * @param active           vrai si un accès offert est en cours
 * @param grantedPlanCode  plan dont le droit est ouvert, ou {@code null}
 * @param grantedUntil     terme du droit, ou {@code null}
 * @param previousPlanCode plan que porte réellement l'abonnement et qui reprend seul la main au
 *                         terme (il n'a jamais été quitté), ou {@code null}
 * @param label            libellé donné par l'admin à l'émission, ou {@code null}
 */
public record AccessGrantResponse(
        boolean active,
        String grantedPlanCode,
        OffsetDateTime grantedUntil,
        String previousPlanCode,
        String label) {

    /** Réponse « aucun accès offert » — jamais un 404 : l'absence de droit est un état normal. */
    public static final AccessGrantResponse NONE =
            new AccessGrantResponse(false, null, null, null, null);

    /** Projette un droit (ou son absence) en réponse d'API. */
    public static AccessGrantResponse from(Optional<AccessGrant> grant) {
        return grant.map(AccessGrantResponse::from).orElse(NONE);
    }

    /** Projette un droit en réponse d'API. */
    public static AccessGrantResponse from(AccessGrant grant) {
        return new AccessGrantResponse(
                true,
                grant.grantedPlanCode() == null ? null : grant.grantedPlanCode().name(),
                grant.grantedUntil(),
                grant.previousPlanCode() == null ? null : grant.previousPlanCode().name(),
                grant.label());
    }
}
