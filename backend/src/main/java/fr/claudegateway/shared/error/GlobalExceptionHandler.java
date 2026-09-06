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

import fr.claudegateway.admin.AdminForbiddenException;
import fr.claudegateway.ai.AIProviderException;
import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.billing.NoActiveSubscriptionException;
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
import fr.claudegateway.atelier.AtelierAccessDeniedException;
import fr.claudegateway.atelier.InvalidArchiveException;
import fr.claudegateway.atelier.InvalidFilePathException;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.ExecutionTargetModeException;
import fr.claudegateway.atelier.git.GitWorkspaceModeException;
import fr.claudegateway.atelier.git.GitWorkspaceReadOnlyException;
import fr.claudegateway.atelier.git.GitWorkspaceRequiredException;
import fr.claudegateway.atelier.agent.NoActiveSessionException;
import fr.claudegateway.runner.exec.NoPendingConfirmationException;
import fr.claudegateway.chat.AttachmentNotFoundException;
import fr.claudegateway.chat.ConversationNotFoundException;
import fr.claudegateway.chat.DocumentNotReadyException;
import fr.claudegateway.chat.UnsupportedModelException;
import fr.claudegateway.export.UnsupportedExportFormatException;
import fr.claudegateway.git.GitFileNotReadableException;
import fr.claudegateway.git.GitHubUnavailableException;
import fr.claudegateway.git.GitTokenMissingException;
import fr.claudegateway.git.InvalidGitBranchException;
import fr.claudegateway.git.InvalidGitRepositoryException;
import fr.claudegateway.git.InvalidGitTokenException;
import fr.claudegateway.ocr.DocumentNotFoundException;
import fr.claudegateway.quota.QuotaExceededException;
import fr.claudegateway.quota.SandboxLimitExceededException;
import fr.claudegateway.rag.provider.EmbeddingProviderException;
import fr.claudegateway.runner.PairingInvalidException;
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

    @ExceptionHandler(AdminForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleAdminForbidden(AdminForbiddenException ex) {
        log.debug("Accès admin refusé");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("forbidden", ex.getMessage()));
    }

    @ExceptionHandler(AtelierAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAtelierAccessDenied(AtelierAccessDeniedException ex) {
        log.debug("Accès Atelier refusé : ni admin ni abonné Gold actif");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("atelier_forbidden", ex.getMessage()));
    }

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

    @ExceptionHandler(fr.claudegateway.atelier.git.GitNothingToPublishException.class)
    public ResponseEntity<ErrorResponse> handleGitNothingToPublish(
            fr.claudegateway.atelier.git.GitNothingToPublishException ex) {
        log.debug("Publication refusée : aucun travail non publié");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("git_nothing_to_publish", ex.getMessage()));
    }

    @ExceptionHandler(fr.claudegateway.atelier.git.GitBranchUnknownException.class)
    public ResponseEntity<ErrorResponse> handleGitBranchUnknown(
            fr.claudegateway.atelier.git.GitBranchUnknownException ex) {
        log.debug("Changement de branche refusé : branche inconnue");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("git_branch_unknown", ex.getMessage()));
    }

    @ExceptionHandler(fr.claudegateway.atelier.git.GitBranchExistsException.class)
    public ResponseEntity<ErrorResponse> handleGitBranchExists(
            fr.claudegateway.atelier.git.GitBranchExistsException ex) {
        log.debug("Création de branche refusée : la branche existe déjà");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("git_branch_exists", ex.getMessage()));
    }

    @ExceptionHandler(fr.claudegateway.atelier.git.GitDefaultBranchRefusedException.class)
    public ResponseEntity<ErrorResponse> handleGitDefaultBranchRefused(
            fr.claudegateway.atelier.git.GitDefaultBranchRefusedException ex) {
        log.debug("Publication refusée : commit demandé sur la branche par défaut");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("git_default_branch_refused", ex.getMessage()));
    }

    @ExceptionHandler(PairingInvalidException.class)
    public ResponseEntity<ErrorResponse> handlePairingInvalid(PairingInvalidException ex) {
        log.debug("Appairage runner refusé : code invalide, expiré ou déjà consommé");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("pairing_invalid", ex.getMessage()));
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

    @ExceptionHandler(WorkspaceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWorkspaceNotFound(WorkspaceNotFoundException ex) {
        log.debug("Workspace/fichier Atelier introuvable ou non possédé");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", ex.getMessage()));
    }

    @ExceptionHandler(InvalidArchiveException.class)
    public ResponseEntity<ErrorResponse> handleInvalidArchive(InvalidArchiveException ex) {
        log.debug("Archive Atelier rejetée : {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("invalid_archive", ex.getMessage()));
    }

    @ExceptionHandler(InvalidFilePathException.class)
    public ResponseEntity<ErrorResponse> handleInvalidFilePath(InvalidFilePathException ex) {
        log.debug("Chemin de fichier Atelier invalide");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("invalid_file_path", ex.getMessage()));
    }

    @ExceptionHandler(DocumentNotReadyException.class)
    public ResponseEntity<ErrorResponse> handleDocumentNotReady(DocumentNotReadyException ex) {
        log.debug("Document de bibliothèque non prêt : texte non encore extrait");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("document_not_ready", ex.getMessage()));
    }

    @ExceptionHandler(UnsupportedModelException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedModel(UnsupportedModelException ex) {
        log.debug("Modèle non supporté demandé");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("validation_error", ex.getMessage()));
    }

    @ExceptionHandler(UnsupportedExportFormatException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedExportFormat(UnsupportedExportFormatException ex) {
        log.debug("Format d'export non supporté demandé");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("validation_error", ex.getMessage()));
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ErrorResponse> handleQuotaExceeded(QuotaExceededException ex) {
        log.debug("Appel refusé : quota de consommation atteint");
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(new ErrorResponse("quota_exceeded", ex.getMessage()));
    }

    @ExceptionHandler(SandboxLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleSandboxLimit(SandboxLimitExceededException ex) {
        log.debug("Exécution refusée : plafond de temps de bac à sable atteint");
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(new ErrorResponse("sandbox_limit", ex.getMessage()));
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

    @ExceptionHandler(InvalidGitTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidGitToken(InvalidGitTokenException ex) {
        // Le jeton n'est jamais journalisé : message métier neutre uniquement (F-31 / SF-31-01).
        log.debug("Jeton GitHub refusé : format invalide ou rejeté par GitHub");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("invalid_git_token", ex.getMessage()));
    }

    @ExceptionHandler(GitTokenMissingException.class)
    public ResponseEntity<ErrorResponse> handleGitTokenMissing(GitTokenMissingException ex) {
        // Distinct d'un jeton refusé : l'action corrective est d'en enregistrer un, pas d'en changer.
        log.debug("Opération Git refusée : aucun jeton GitHub enregistré pour l'utilisateur");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("git_token_missing", ex.getMessage()));
    }

    @ExceptionHandler(InvalidGitRepositoryException.class)
    public ResponseEntity<ErrorResponse> handleInvalidGitRepository(InvalidGitRepositoryException ex) {
        // L'URL est journalisable (publique), mais inutile au diagnostic : message métier seul.
        log.debug("Dépôt Git refusé : URL invalide, ou dépôt hors de portée du jeton");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("invalid_git_repository", ex.getMessage()));
    }

    @ExceptionHandler(InvalidGitBranchException.class)
    public ResponseEntity<ErrorResponse> handleInvalidGitBranch(InvalidGitBranchException ex) {
        log.debug("Branche Git refusée : forme invalide ou branche de base");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("invalid_git_branch", ex.getMessage()));
    }

    @ExceptionHandler(GitWorkspaceReadOnlyException.class)
    public ResponseEntity<ErrorResponse> handleGitWorkspaceReadOnly(GitWorkspaceReadOnlyException ex) {
        // 409 et non 403 : la demande est légitime, c'est l'état du projet qui l'interdit.
        log.debug("Écriture refusée : le projet est adossé à un dépôt Git (lecture seule)");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("git_workspace_read_only", ex.getMessage()));
    }

    @ExceptionHandler(GitWorkspaceRequiredException.class)
    public ResponseEntity<ErrorResponse> handleGitWorkspaceRequired(GitWorkspaceRequiredException ex) {
        log.debug("Opération Git refusée : le projet n'est pas adossé à un dépôt");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("git_workspace_required", ex.getMessage()));
    }

    @ExceptionHandler(NoActiveSessionException.class)
    public ResponseEntity<ErrorResponse> handleNoActiveSession(NoActiveSessionException ex) {
        // Rien n'a été fait dans la sandbox : il n'y a rien à publier, et aucune session n'a été
        // ouverte pour l'occasion (elle repartirait d'un clone vierge).
        log.debug("Publication refusée : aucune session sandbox en cours");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("no_active_session", ex.getMessage()));
    }

    @ExceptionHandler(GitWorkspaceModeException.class)
    public ResponseEntity<ErrorResponse> handleGitWorkspaceMode(GitWorkspaceModeException ex) {
        log.debug("Mode Assistant refusé : le projet est adossé à un dépôt Git");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("git_workspace_terminal_only", ex.getMessage()));
    }

    @ExceptionHandler(NoPendingConfirmationException.class)
    public ResponseEntity<ErrorResponse> handleNoPendingConfirmation(NoPendingConfirmationException ex) {
        // Demande inconnue, déjà tranchée ou expirée : le dire, plutôt que de laisser croire qu'une
        // autorisation est passée alors qu'elle s'est perdue (F-38 / SF-38-08).
        log.debug("Réponse d'autorisation refusée : aucune demande en attente");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("no_pending_confirmation", ex.getMessage()));
    }

    @ExceptionHandler(ExecutionTargetModeException.class)
    public ResponseEntity<ErrorResponse> handleExecutionTargetMode(ExecutionTargetModeException ex) {
        // Le projet s'execute sur la machine de l'utilisateur : ouvrir un bac a sable chez le
        // fournisseur travaillerait au mauvais endroit (F-38, decision D2).
        log.debug("Session sandbox refusee : le projet est en cible d'execution RUNNER");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("execution_target_runner", ex.getMessage()));
    }

    @ExceptionHandler(fr.claudegateway.runner.browse.RunnerBrowseException.class)
    public ResponseEntity<ErrorResponse> handleRunnerBrowse(
            fr.claudegateway.runner.browse.RunnerBrowseException ex) {
        // Le projet vit sur la machine de l'utilisateur et elle n'est pas joignable (F-38 /
        // SF-38-17) : c'est un ETAT, pas une panne — et il se repare en lançant le runner. Jamais
        // une liste vide, qui laisserait croire a un projet vide.
        log.debug("Lecture sur la machine impossible : runner injoignable ou lecture refusee");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("runner_browse_unavailable", ex.getMessage()));
    }

    @ExceptionHandler(fr.claudegateway.atelier.LocalWorkspaceException.class)
    public ResponseEntity<ErrorResponse> handleLocalWorkspace(
            fr.claudegateway.atelier.LocalWorkspaceException ex) {
        // Le projet vit sur la machine de l'utilisateur (F-38 / SF-38-15) : ecrire dans le stockage,
        // basculer sur le bac a sable ou passer par l'API GitHub n'a pas de sens ici. Le message dit
        // ou le geste doit se faire, pas ce qui a echoue.
        log.debug("Geste refuse : le projet est local (source LOCAL)");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("local_workspace_refused", ex.getMessage()));
    }

    @ExceptionHandler(GitFileNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleGitFileNotReadable(GitFileNotReadableException ex) {
        // Le fichier existe : le dire évite de faire chercher l'utilisateur, contrairement à un 404.
        log.debug("Fichier du dépôt non affichable : binaire ou trop volumineux");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("git_file_not_readable", ex.getMessage()));
    }

    @ExceptionHandler(GitHubUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleGitHubUnavailable(GitHubUnavailableException ex) {
        // Panne temporaire : distincte d'un jeton refusé, et rien n'a été persisté.
        log.warn("GitHub indisponible lors de la vérification d'un jeton");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("github_unavailable", ex.getMessage()));
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

    @ExceptionHandler(NoActiveSubscriptionException.class)
    public ResponseEntity<ErrorResponse> handleNoActiveSubscription(NoActiveSubscriptionException ex) {
        log.debug("Changement de plan refusé : aucun abonnement actif");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("no_active_subscription", ex.getMessage()));
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
