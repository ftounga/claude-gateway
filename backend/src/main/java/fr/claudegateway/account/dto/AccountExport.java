package fr.claudegateway.account.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.chat.MessageRole;
import fr.claudegateway.template.TemplateCategory;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.UserRole;

/**
 * Document d'export RGPD (droit à la portabilité, art. 20) des données d'un utilisateur.
 *
 * <p>Vue strictement publique : n'expose jamais de secret ni d'identifiant technique interne
 * (hash de mot de passe, identifiants Stripe, identifiant de fichier chez le fournisseur). Toutes
 * les données proviennent du seul {@code user_id} du contexte de sécurité.</p>
 */
public record AccountExport(
        OffsetDateTime exportedAt,
        Account account,
        SubscriptionExport subscription,
        List<UsageExport> usage,
        List<ConversationExport> conversations,
        List<UploadedFileExport> uploadedFiles,
        List<TemplateExport> templates) {

    /** Récapitulatif du compte. */
    public record Account(
            UUID id,
            String email,
            boolean emailVerified,
            AuthProvider provider,
            UserRole role,
            OffsetDateTime createdAt) {
    }

    /** Abonnement de l'utilisateur (identifiants Stripe volontairement omis). */
    public record SubscriptionExport(
            SubscriptionStatus status,
            PlanCode planCode,
            OffsetDateTime trialEndsAt,
            OffsetDateTime currentPeriodEnd) {
    }

    /** Compteur d'usage d'une période (mois calendaire). */
    public record UsageExport(
            LocalDate periodStart,
            long inputTokens,
            long outputTokens) {
    }

    /** Conversation et ses messages. */
    public record ConversationExport(
            UUID id,
            String title,
            String model,
            OffsetDateTime createdAt,
            List<MessageExport> messages) {
    }

    /** Message d'une conversation. */
    public record MessageExport(
            MessageRole role,
            String content,
            String model,
            OffsetDateTime createdAt) {
    }

    /** Métadonnées d'un fichier téléversé (identifiant fournisseur volontairement omis). */
    public record UploadedFileExport(
            String filename,
            String mediaType,
            long sizeBytes,
            OffsetDateTime createdAt) {
    }

    /** Modèle de prompt réutilisable (F-13). */
    public record TemplateExport(
            String name,
            TemplateCategory category,
            String content,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }
}
