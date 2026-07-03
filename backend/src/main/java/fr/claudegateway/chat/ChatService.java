package fr.claudegateway.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ChatMessage;
import fr.claudegateway.ai.ChatRole;
import fr.claudegateway.ai.ModelCatalog;
import fr.claudegateway.ai.ProviderAttachment;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.ocr.Document;
import fr.claudegateway.ocr.DocumentNotFoundException;
import fr.claudegateway.ocr.DocumentRepository;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.upload.UploadedFile;
import fr.claudegateway.upload.UploadedFileRepository;

/**
 * Cœur du proxy de chat Hosted : valide la demande, résout/crée la conversation de l'utilisateur,
 * persiste le message utilisateur, relaie l'historique au fournisseur via l'interface
 * {@link AIProvider} (jamais Anthropic en direct), puis persiste la réponse assistant.
 *
 * <p>Isolation multi-tenant : la conversation ciblée est toujours vérifiée comme appartenant à
 * l'utilisateur courant avant toute écriture ; les messages portent le {@code user_id}.</p>
 */
@Service
public class ChatService {

    private static final int TITLE_MAX_LENGTH = 60;

    /** Garde-fou : longueur max du texte injecté par document de bibliothèque (F-24). */
    private static final int LIBRARY_DOCUMENT_TEXT_MAX = 100_000;

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UploadedFileRepository uploadedFileRepository;
    private final DocumentRepository documentRepository;
    private final AIProvider aiProvider;
    private final ModelCatalog modelCatalog;
    private final QuotaService quotaService;
    private final ByokKeyService byokKeyService;

    public ChatService(
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            UploadedFileRepository uploadedFileRepository,
            DocumentRepository documentRepository,
            AIProvider aiProvider,
            ModelCatalog modelCatalog,
            QuotaService quotaService,
            ByokKeyService byokKeyService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.uploadedFileRepository = uploadedFileRepository;
        this.documentRepository = documentRepository;
        this.aiProvider = aiProvider;
        this.modelCatalog = modelCatalog;
        this.quotaService = quotaService;
        this.byokKeyService = byokKeyService;
    }

    /**
     * Traite un tour de chat pour l'utilisateur courant.
     *
     * @param userId         utilisateur authentifié (contexte de sécurité)
     * @param conversationId conversation existante ou null pour en créer une
     * @param rawMessage     message utilisateur (déjà validé non vide côté controller)
     * @param requestedModel modèle souhaité ou null
     * @param attachmentIds  identifiants de fichiers téléversés à rattacher (null/vide = aucun)
     * @return le message assistant persisté et la conversation ciblée
     * @throws UnsupportedModelException     si le modèle demandé n'est pas dans la liste blanche
     * @throws ConversationNotFoundException si la conversation ne appartient pas à l'utilisateur
     * @throws AttachmentNotFoundException   si un fichier attaché n'appartient pas à l'utilisateur
     */
    @Transactional
    public ChatResult reply(UUID userId, UUID conversationId, String rawMessage, String requestedModel,
            List<UUID> attachmentIds) {
        return reply(userId, conversationId, rawMessage, requestedModel, attachmentIds, null);
    }

    /**
     * Variante avec import de documents de la bibliothèque personnelle (F-24) : le texte OCR des
     * documents désignés par {@code libraryDocumentIds} est injecté comme message de contexte en tête
     * de la requête fournisseur, sans être persisté dans l'historique de la conversation.
     *
     * @param libraryDocumentIds documents de la bibliothèque à injecter (null/vide = aucun ; max 10)
     * @throws DocumentNotFoundException si un document est inconnu ou appartient à un autre utilisateur
     * @throws DocumentNotReadyException si un document n'a pas encore de texte exploitable
     */
    @Transactional
    public ChatResult reply(UUID userId, UUID conversationId, String rawMessage, String requestedModel,
            List<UUID> attachmentIds, List<UUID> libraryDocumentIds) {
        String content = rawMessage.trim();
        // Contrôle de quota AVANT toute écriture ou appel fournisseur (F-10) : un utilisateur ayant
        // atteint son quota reçoit 402 sans qu'aucun message ne soit persisté ni relayé.
        quotaService.assertWithinQuota(userId);
        // Rejette tout modèle explicite hors liste blanche, même sur une conversation existante.
        validateRequestedModel(requestedModel);
        // Résolution des pièces jointes AVANT toute écriture : un id d'un autre utilisateur => 404,
        // sans persister de message ni appeler le fournisseur (isolation user_id).
        List<ProviderAttachment> attachments = resolveAttachments(userId, attachmentIds);
        // Contexte documentaire de la bibliothèque (F-24), résolu AVANT toute écriture : un id d'un
        // autre utilisateur => 404, un document non prêt => 409, sans persistance ni appel fournisseur.
        List<ChatMessage> libraryContext = resolveLibraryContext(userId, libraryDocumentIds);
        Conversation conversation = resolveConversation(userId, conversationId, requestedModel, content);
        // Rattache les fichiers joints à la conversation (F-23) — après résolution, avant toute écriture
        // de message, pour que l'ordre « attachement invalide => 404 sans persistance » reste préservé.
        associateAttachmentsWithConversation(userId, conversation.getId(), attachmentIds);

        // Persistance du message utilisateur.
        Message userMessage = messageRepository.save(Message.builder()
                .conversationId(conversation.getId())
                .userId(userId)
                .role(MessageRole.USER)
                .content(content)
                .build());

        // Historique complet (incluant le message tout juste persisté) transmis au fournisseur.
        List<Message> history = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId());
        // Mode fournisseur par utilisateur : clé BYOK active si présente (déchiffrée à la volée, jamais
        // persistée ni journalisée), sinon clé plateforme (Hosted). Provider-neutre : la clé n'est
        // qu'un paramètre de la requête.
        String byokApiKey = byokKeyService.resolveActiveApiKey(userId).orElse(null);
        ChatCompletionResult completion = aiProvider.complete(new ChatCompletionRequest(
                conversation.getModel(), buildProviderMessages(history, libraryContext), attachments, byokApiKey));

        // Persistance de la réponse assistant.
        Message assistantMessage = messageRepository.save(Message.builder()
                .conversationId(conversation.getId())
                .userId(userId)
                .role(MessageRole.ASSISTANT)
                .content(completion.content())
                .model(completion.model())
                .build());

        // Enregistre la consommation de tokens de cet appel sur la période courante (F-10).
        quotaService.recordUsage(userId, completion.inputTokens(), completion.outputTokens());

        // Rafraîchit updated_at pour le tri de la liste latérale.
        conversation.setTitle(conversation.getTitle());
        conversationRepository.save(conversation);

        return new ChatResult(conversation, userMessage, assistantMessage);
    }

    /**
     * Pré-vol synchrone du streaming (SF-02-04) : applique les mêmes garanties que {@link #reply}
     * (quota, modèle, isolation, résolution/création de conversation, persistance du message USER,
     * historique, clé BYOK) et renvoie le contexte prêt pour le relais. Exécuté sur le thread requête
     * pour que toute erreur (402/400/404) devienne une réponse HTTP normale <b>avant</b> l'ouverture du flux.
     */
    @Transactional
    public StreamContext prepareStream(UUID userId, UUID conversationId, String rawMessage,
            String requestedModel, List<UUID> attachmentIds) {
        return prepareStream(userId, conversationId, rawMessage, requestedModel, attachmentIds, null);
    }

    /**
     * Variante streaming avec import de documents de la bibliothèque personnelle (F-24) : cf.
     * {@link #reply(UUID, UUID, String, String, List, List)}. Le contexte documentaire est résolu
     * pendant le pré-vol synchrone : ses erreurs (404/409) remontent avant l'ouverture du flux.
     */
    @Transactional
    public StreamContext prepareStream(UUID userId, UUID conversationId, String rawMessage,
            String requestedModel, List<UUID> attachmentIds, List<UUID> libraryDocumentIds) {
        String content = rawMessage.trim();
        quotaService.assertWithinQuota(userId);
        validateRequestedModel(requestedModel);
        List<ProviderAttachment> attachments = resolveAttachments(userId, attachmentIds);
        List<ChatMessage> libraryContext = resolveLibraryContext(userId, libraryDocumentIds);
        Conversation conversation = resolveConversation(userId, conversationId, requestedModel, content);
        // Rattache les fichiers joints à la conversation (F-23), même garantie d'ordre que reply().
        associateAttachmentsWithConversation(userId, conversation.getId(), attachmentIds);

        Message userMessage = messageRepository.save(Message.builder()
                .conversationId(conversation.getId())
                .userId(userId)
                .role(MessageRole.USER)
                .content(content)
                .build());

        List<Message> history = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId());
        String byokApiKey = byokKeyService.resolveActiveApiKey(userId).orElse(null);
        ChatCompletionRequest providerRequest = new ChatCompletionRequest(
                conversation.getModel(), buildProviderMessages(history, libraryContext), attachments, byokApiKey);

        return new StreamContext(userId, conversation, userMessage, providerRequest);
    }

    /**
     * Relaie le flux du fournisseur (via {@code onDelta}) puis, <b>à la fin du flux réussi</b>, persiste
     * le message ASSISTANT (texte = concaténation des deltas) et comptabilise l'usage. Volontairement
     * <b>non transactionnel</b> : garder une transaction ouverte pendant tout le streaming réseau
     * bloquerait une connexion ; les écritures finales sont atomiques par appel (repos Spring Data +
     * {@code QuotaService.recordUsage}). Un flux en échec ({@link fr.claudegateway.ai.AIProviderException})
     * ne persiste ni message ASSISTANT ni usage.
     *
     * @return le message assistant persisté
     */
    public Message streamAndPersist(StreamContext context, java.util.function.Consumer<String> onDelta) {
        ChatCompletionResult completion = aiProvider.streamComplete(context.providerRequest(), onDelta);

        Message assistantMessage = messageRepository.save(Message.builder()
                .conversationId(context.conversation().getId())
                .userId(context.userId())
                .role(MessageRole.ASSISTANT)
                .content(completion.content())
                .model(completion.model())
                .build());

        quotaService.recordUsage(context.userId(), completion.inputTokens(), completion.outputTokens());

        // Rafraîchit updated_at pour le tri de la liste latérale.
        context.conversation().setTitle(context.conversation().getTitle());
        conversationRepository.save(context.conversation());

        return assistantMessage;
    }

    /** Contexte de streaming issu du pré-vol, consommé par {@link #streamAndPersist}. */
    public record StreamContext(UUID userId, Conversation conversation, Message userMessage,
            ChatCompletionRequest providerRequest) {
    }

    private Conversation resolveConversation(UUID userId, UUID conversationId, String requestedModel, String firstMessage) {
        if (conversationId != null) {
            return conversationRepository.findByIdAndUserId(conversationId, userId)
                    .orElseThrow(ConversationNotFoundException::new);
        }
        String model = resolveModel(requestedModel);
        return conversationRepository.save(Conversation.builder()
                .userId(userId)
                .title(deriveTitle(firstMessage))
                .model(model)
                .build());
    }

    /**
     * Résout le modèle : celui demandé s'il est dans la liste blanche, sinon le défaut si aucun
     * n'est demandé.
     *
     * @throws UnsupportedModelException si un modèle explicite hors liste blanche est fourni
     */
    private String resolveModel(String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) {
            return modelCatalog.defaultModel();
        }
        return requestedModel;
    }

    private void validateRequestedModel(String requestedModel) {
        if (requestedModel != null && !requestedModel.isBlank()
                && !modelCatalog.supports(requestedModel)) {
            throw new UnsupportedModelException("Modèle non supporté : " + requestedModel);
        }
    }

    private String deriveTitle(String message) {
        String singleLine = message.replaceAll("\\s+", " ").trim();
        if (singleLine.length() <= TITLE_MAX_LENGTH) {
            return singleLine;
        }
        return singleLine.substring(0, TITLE_MAX_LENGTH).trim() + "…";
    }

    /**
     * Résout les pièces jointes en références fournisseur, en vérifiant que chaque fichier
     * appartient à l'utilisateur courant (isolation multi-tenant).
     *
     * @throws AttachmentNotFoundException si un id est inconnu ou appartient à un autre utilisateur
     */
    private List<ProviderAttachment> resolveAttachments(UUID userId, List<UUID> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return List.of();
        }
        List<ProviderAttachment> attachments = new ArrayList<>(attachmentIds.size());
        for (UUID id : attachmentIds) {
            UploadedFile file = uploadedFileRepository.findByIdAndUserId(id, userId)
                    .orElseThrow(AttachmentNotFoundException::new);
            attachments.add(new ProviderAttachment(file.getProviderFileId(), file.getMediaType()));
        }
        return attachments;
    }

    /**
     * Rattache les fichiers joints à la conversation (F-23 : dossier de fichiers par conversation).
     * Chaque fichier est relu sous double filtre {@code id} + {@code user_id} (isolation multi-tenant) ;
     * l'association n'est posée que si elle est encore vide (« premier rattachement gagne », immuable).
     */
    private void associateAttachmentsWithConversation(UUID userId, UUID conversationId, List<UUID> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        for (UUID id : attachmentIds) {
            uploadedFileRepository.findByIdAndUserId(id, userId).ifPresent(file -> {
                if (file.getConversationId() == null) {
                    file.setConversationId(conversationId);
                    uploadedFileRepository.save(file);
                }
            });
        }
    }

    private List<ChatMessage> toProviderMessages(List<Message> history) {
        List<ChatMessage> messages = new ArrayList<>(history.size());
        for (Message message : history) {
            ChatRole role = message.getRole() == MessageRole.ASSISTANT ? ChatRole.ASSISTANT : ChatRole.USER;
            messages.add(new ChatMessage(role, message.getContent()));
        }
        return messages;
    }

    /**
     * Construit la liste de messages fournisseur : le contexte documentaire de la bibliothèque (F-24),
     * s'il existe, est inséré en tête, suivi de l'historique de la conversation.
     */
    private List<ChatMessage> buildProviderMessages(List<Message> history, List<ChatMessage> libraryContext) {
        List<ChatMessage> messages = toProviderMessages(history);
        messages.addAll(0, libraryContext);
        return messages;
    }

    /**
     * Résout les documents de la bibliothèque personnelle (F-08) désignés en messages de contexte
     * fournisseur (F-24). Chaque document est relu sous double filtre {@code id} + {@code user_id}
     * (isolation multi-tenant) ; son texte extrait est injecté tel quel (relais Provider-First, aucun
     * ré-traitement), tronqué au garde-fou {@link #LIBRARY_DOCUMENT_TEXT_MAX}.
     *
     * @throws DocumentNotFoundException si un id est inconnu ou appartient à un autre utilisateur
     * @throws DocumentNotReadyException si le texte du document n'est pas encore disponible
     */
    private List<ChatMessage> resolveLibraryContext(UUID userId, List<UUID> libraryDocumentIds) {
        if (libraryDocumentIds == null || libraryDocumentIds.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> context = new ArrayList<>(libraryDocumentIds.size());
        for (UUID id : libraryDocumentIds) {
            Document document = documentRepository.findByIdAndUserId(id, userId)
                    .orElseThrow(() -> new DocumentNotFoundException("Document introuvable : " + id));
            String text = document.getExtractedText();
            if (text == null || text.isBlank()) {
                throw new DocumentNotReadyException(
                        "Le document « " + document.getFilename() + " » n'a pas encore de texte exploitable.");
            }
            if (text.length() > LIBRARY_DOCUMENT_TEXT_MAX) {
                text = text.substring(0, LIBRARY_DOCUMENT_TEXT_MAX);
            }
            context.add(new ChatMessage(ChatRole.USER,
                    "Document de la bibliothèque « " + document.getFilename() + " » :\n\n" + text));
        }
        return context;
    }

    /** Résultat interne d'un tour de chat. */
    public record ChatResult(Conversation conversation, Message userMessage, Message assistantMessage) {
    }
}
