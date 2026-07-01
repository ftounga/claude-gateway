package fr.claudegateway.shared.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import fr.claudegateway.ai.AIProviderException;
import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.billing.UnknownPlanException;
import fr.claudegateway.billing.provider.BillingProviderException;
import fr.claudegateway.billing.provider.BillingProviderUnavailableException;
import fr.claudegateway.billing.provider.WebhookVerificationException;
import fr.claudegateway.byok.ByokDisabledException;
import fr.claudegateway.byok.ByokModeException;
import fr.claudegateway.byok.InvalidApiKeyException;
import fr.claudegateway.auth.EmailAlreadyUsedException;
import fr.claudegateway.auth.InvalidCredentialsException;
import fr.claudegateway.auth.InvalidPasswordResetTokenException;
import fr.claudegateway.auth.InvalidVerificationTokenException;
import fr.claudegateway.chat.AttachmentNotFoundException;
import fr.claudegateway.chat.ConversationNotFoundException;
import fr.claudegateway.chat.UnsupportedModelException;
import fr.claudegateway.ocr.DocumentNotFoundException;
import fr.claudegateway.quota.QuotaExceededException;
import fr.claudegateway.rag.provider.EmbeddingProviderException;
import fr.claudegateway.rag.provider.EmbeddingProviderUnavailableException;
import fr.claudegateway.template.TemplateNotFoundException;
import fr.claudegateway.upload.EmptyFileException;
import fr.claudegateway.upload.FileTooLargeException;
import fr.claudegateway.upload.UnsupportedFileTypeException;
import fr.claudegateway.user.UserNotFoundException;

/**
 * Traduit les exceptions applicatives en réponses JSON homogènes {@link ErrorResponse}.
 * Ne divulgue jamais de détail d'implémentation ni de stacktrace au client (cf. CODING_RULES §6).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException ex) {
        log.debug("Utilisateur introuvable : {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", "Ressource introuvable."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        // On journalise le champ en cause mais jamais la valeur soumise (peut contenir un secret).
        String field = ex.getBindingResult().getFieldError() != null
                ? ex.getBindingResult().getFieldError().getField()
                : "requête";
        log.debug("Requête invalide : champ '{}' non conforme", field);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("validation_error",
                        "Requête invalide : le champ '" + field + "' est incorrect."));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        log.debug("Requête invalide : paramètre '{}' manquant", ex.getParameterName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("validation_error",
                        "Requête invalide : le paramètre '" + ex.getParameterName() + "' est requis."));
    }

    @ExceptionHandler(InvalidVerificationTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidVerificationToken(InvalidVerificationTokenException ex) {
        log.debug("Vérification refusée : token invalide ou expiré");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("invalid_token", ex.getMessage()));
    }

    @ExceptionHandler(InvalidPasswordResetTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidResetToken(InvalidPasswordResetTokenException ex) {
        log.debug("Réinitialisation refusée : token invalide ou expiré");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("invalid_token", ex.getMessage()));
    }

    @ExceptionHandler(EmailAlreadyUsedException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyUsed(EmailAlreadyUsedException ex) {
        log.debug("Inscription refusée : email déjà utilisé");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("email_already_used", ex.getMessage()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        log.debug("Connexion refusée : identifiants invalides");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("invalid_credentials", ex.getMessage()));
    }

    @ExceptionHandler(ConversationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleConversationNotFound(ConversationNotFoundException ex) {
        log.debug("Conversation introuvable ou non possédée");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", ex.getMessage()));
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleDocumentNotFound(DocumentNotFoundException ex) {
        log.debug("Document introuvable ou non possédé");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", ex.getMessage()));
    }

    @ExceptionHandler(TemplateNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTemplateNotFound(TemplateNotFoundException ex) {
        log.debug("Modèle introuvable ou non possédé");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        // Corps JSON malformé ou valeur d'énumération invalide : message métier neutre, jamais le
        // contenu soumis (peut contenir des données utilisateur). Traduit en 400 plutôt que 500.
        log.debug("Requête invalide : corps JSON illisible ou valeur non conforme");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("validation_error", "Requête invalide : le corps est illisible ou contient une valeur non conforme."));
    }

    @ExceptionHandler(AttachmentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAttachmentNotFound(AttachmentNotFoundException ex) {
        log.debug("Pièce jointe introuvable ou non possédée");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("attachment_not_found", ex.getMessage()));
    }

    @ExceptionHandler(UnsupportedModelException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedModel(UnsupportedModelException ex) {
        log.debug("Modèle non supporté demandé");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("validation_error", ex.getMessage()));
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ErrorResponse> handleQuotaExceeded(QuotaExceededException ex) {
        log.debug("Appel refusé : quota de consommation atteint");
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(new ErrorResponse("quota_exceeded", ex.getMessage()));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException ex) {
        log.debug("Requête invalide : partie multipart '{}' manquante", ex.getRequestPartName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("validation_error",
                        "Requête invalide : le fichier '" + ex.getRequestPartName() + "' est requis."));
    }

    @ExceptionHandler(EmptyFileException.class)
    public ResponseEntity<ErrorResponse> handleEmptyFile(EmptyFileException ex) {
        log.debug("Upload refusé : fichier absent ou vide");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("validation_error", ex.getMessage()));
    }

    @ExceptionHandler(UnsupportedFileTypeException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedFileType(UnsupportedFileTypeException ex) {
        log.debug("Upload refusé : type de fichier non supporté");
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ErrorResponse("unsupported_file_type", ex.getMessage()));
    }

    @ExceptionHandler({ FileTooLargeException.class, MaxUploadSizeExceededException.class })
    public ResponseEntity<ErrorResponse> handleFileTooLarge(Exception ex) {
        log.debug("Upload refusé : fichier trop volumineux");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("file_too_large", "Fichier trop volumineux."));
    }

    @ExceptionHandler(AIProviderUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleProviderUnavailable(AIProviderUnavailableException ex) {
        // Aucune clé ni détail fournisseur n'est journalisé : message métier neutre uniquement.
        log.warn("Fournisseur IA non disponible");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("provider_unavailable",
                        "Le service de chat est momentanément indisponible."));
    }

    @ExceptionHandler(AIProviderException.class)
    public ResponseEntity<ErrorResponse> handleProviderError(AIProviderException ex) {
        // On journalise l'échec sans exposer la réponse brute du fournisseur au client.
        log.warn("Échec de l'appel au fournisseur IA");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("provider_error",
                        "Le fournisseur d'IA a rencontré une erreur. Veuillez réessayer."));
    }

    @ExceptionHandler(InvalidApiKeyException.class)
    public ResponseEntity<ErrorResponse> handleInvalidApiKey(InvalidApiKeyException ex) {
        // La clé n'est jamais journalisée : message métier neutre uniquement.
        log.debug("Clé API BYOK refusée : format invalide ou non validée par le fournisseur");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("invalid_api_key", ex.getMessage()));
    }

    @ExceptionHandler(ByokModeException.class)
    public ResponseEntity<ErrorResponse> handleByokMode(ByokModeException ex) {
        log.debug("Bascule mode BYOK refusée : aucune clé enregistrée");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("byok_mode_conflict", ex.getMessage()));
    }

    @ExceptionHandler(ByokDisabledException.class)
    public ResponseEntity<ErrorResponse> handleByokDisabled(ByokDisabledException ex) {
        // Aucune clé ni détail de configuration n'est journalisé : message métier neutre.
        log.warn("BYOK indisponible : chiffrement des clés utilisateur non configuré");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("byok_unavailable",
                        "La gestion de clé API personnelle est momentanément indisponible."));
    }

    @ExceptionHandler(EmbeddingProviderUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleEmbeddingUnavailable(EmbeddingProviderUnavailableException ex) {
        // Aucune clé ni détail fournisseur n'est journalisé : message métier neutre (F-07 /ask).
        log.warn("Fournisseur d'embeddings non disponible");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("provider_unavailable",
                        "Le service de recherche documentaire est momentanément indisponible."));
    }

    @ExceptionHandler(EmbeddingProviderException.class)
    public ResponseEntity<ErrorResponse> handleEmbeddingError(EmbeddingProviderException ex) {
        // On journalise l'échec sans exposer la réponse brute du fournisseur au client.
        log.warn("Échec de l'appel au fournisseur d'embeddings");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("provider_error",
                        "Le service de recherche documentaire a rencontré une erreur. Veuillez réessayer."));
    }

    @ExceptionHandler(UnknownPlanException.class)
    public ResponseEntity<ErrorResponse> handleUnknownPlan(UnknownPlanException ex) {
        log.debug("Checkout refusé : plan inconnu");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("validation_error", ex.getMessage()));
    }

    @ExceptionHandler(WebhookVerificationException.class)
    public ResponseEntity<ErrorResponse> handleWebhookVerification(WebhookVerificationException ex) {
        // Aucune signature ni payload n'est journalisé : message métier neutre.
        log.warn("Webhook de facturation rejeté : signature invalide");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("invalid_signature", "Signature de webhook invalide."));
    }

    @ExceptionHandler(BillingProviderUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleBillingUnavailable(BillingProviderUnavailableException ex) {
        // Aucune clé ni détail fournisseur n'est journalisé.
        log.warn("Fournisseur de paiement non disponible");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("billing_unavailable",
                        "Le service de facturation est momentanément indisponible."));
    }

    @ExceptionHandler(BillingProviderException.class)
    public ResponseEntity<ErrorResponse> handleBillingError(BillingProviderException ex) {
        log.warn("Échec de l'appel au fournisseur de paiement");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("billing_error",
                        "Le service de facturation a rencontré une erreur. Veuillez réessayer."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Erreur inattendue traitée par le handler global", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("internal_error", "Une erreur interne est survenue."));
    }
}
