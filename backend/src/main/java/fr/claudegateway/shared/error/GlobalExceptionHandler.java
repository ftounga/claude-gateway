package fr.claudegateway.shared.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.ai.AIProviderException;
import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.auth.EmailAlreadyUsedException;
import fr.claudegateway.auth.InvalidCredentialsException;
import fr.claudegateway.auth.InvalidPasswordResetTokenException;
import fr.claudegateway.auth.InvalidVerificationTokenException;
import fr.claudegateway.chat.ConversationNotFoundException;
import fr.claudegateway.chat.UnsupportedModelException;
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

    @ExceptionHandler(UnsupportedModelException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedModel(UnsupportedModelException ex) {
        log.debug("Modèle non supporté demandé");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("validation_error", ex.getMessage()));
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Erreur inattendue traitée par le handler global", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("internal_error", "Une erreur interne est survenue."));
    }
}
