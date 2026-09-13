package fr.claudegateway.access.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Demande d'émission d'un code d'accès (F-62 / SF-62-01), réservée à l'ADMIN.
 *
 * <p>Ni la durée ni la validité ne sont demandées : ce sont des réglages de produit
 * ({@code app.access-code.*}), figés dans la ligne au moment de l'émission. Les exposer ici ferait
 * de chaque code une négociation.</p>
 *
 * @param label         libellé permettant de reconnaître le code (« démo prospect Dupont »)
 * @param assignedEmail e-mail du destinataire pour un code <b>nominatif</b> ; absent ⇒ code au porteur
 */
public record IssueAccessCodeRequest(
        @NotBlank(message = "Le libellé est obligatoire.")
        @Size(max = 120, message = "Le libellé ne peut pas dépasser 120 caractères.")
        String label,

        @Email(message = "L'e-mail du destinataire est invalide.")
        @Size(max = 255, message = "L'e-mail ne peut pas dépasser 255 caractères.")
        String assignedEmail,

        @jakarta.validation.constraints.Pattern(regexp = "(?i)FORGE|VIGIE",
                message = "L'espace doit valoir FORGE ou VIGIE.")
        String space) {

    /** Requête d'avant F-107 : code Forge. */
    public IssueAccessCodeRequest(String label, String assignedEmail) {
        this(label, assignedEmail, null);
    }

    /** L'espace demandé ; absent ⇒ {@code FORGE} (F-107 / SF-107-04). */
    public fr.claudegateway.billing.EntitlementSpace grantedSpace() {
        return space == null || space.isBlank()
                ? fr.claudegateway.billing.EntitlementSpace.FORGE
                : fr.claudegateway.billing.EntitlementSpace.valueOf(space.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
