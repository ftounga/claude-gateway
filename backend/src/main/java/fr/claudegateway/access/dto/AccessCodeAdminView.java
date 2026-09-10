package fr.claudegateway.access.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import fr.claudegateway.access.AccessCodeService.AccessCodeView;

/**
 * Un code d'accès vu depuis la console d'administration (F-62 / SF-62-01).
 *
 * <p><b>Ne porte jamais le code en clair.</b> Celui-ci n'existe qu'une fois, dans la réponse
 * d'émission ; il n'est ni stocké, ni relisible.</p>
 *
 * @param id               identifiant de la ligne
 * @param label            libellé donné à l'émission
 * @param assignedEmail    destinataire d'un code nominatif, ou {@code null} (code au porteur)
 * @param grantedPlanCode  plan dont le droit est offert
 * @param durationHours    durée du droit, figée à l'émission
 * @param validUntil       au-delà, un code non consommé ne vaut plus rien
 * @param state            état <b>dérivé</b> des dates : ISSUED / ACTIVE / ENDED / EXPIRED
 * @param redeemedByEmail  compte qui l'a consommé, ou {@code null} — la trace « pour qui »
 * @param redeemedAt       instant de la consommation, ou {@code null} — la trace « quand »
 * @param grantedUntil     terme du droit, ou {@code null}
 * @param previousPlanCode plan auquel ce compte revient au terme, ou {@code null}
 * @param createdAt        instant de l'émission
 */
public record AccessCodeAdminView(
        UUID id,
        String label,
        String assignedEmail,
        String grantedPlanCode,
        int durationHours,
        OffsetDateTime validUntil,
        String state,
        String redeemedByEmail,
        OffsetDateTime redeemedAt,
        OffsetDateTime grantedUntil,
        String previousPlanCode,
        OffsetDateTime createdAt) {

    /** Projette la vue métier en réponse d'API. */
    public static AccessCodeAdminView from(AccessCodeView view) {
        return new AccessCodeAdminView(
                view.id(),
                view.label(),
                view.assignedEmail(),
                view.grantedPlanCode() == null ? null : view.grantedPlanCode().name(),
                view.durationHours(),
                view.validUntil(),
                view.state().name(),
                view.redeemedByEmail(),
                view.redeemedAt(),
                view.grantedUntil(),
                view.previousPlanCode() == null ? null : view.previousPlanCode().name(),
                view.createdAt());
    }
}
