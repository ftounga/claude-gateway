package fr.claudegateway.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * Consigne système (F-26) : demande à Claude d'emballer chaque <b>livrable autonome</b> dans un
     * bloc de code Markdown, avec un token de langage reconnu par la copie de bloc côté frontend
     * (```email → e-mail, ```markdown/```text → document, langage → code). Ainsi chaque mail, courrier,
     * document, extrait de code ou config s'affiche avec un bouton « Copier », comme sur ChatGPT.
     */
    private static final String COPYABLE_DELIVERABLES_SYSTEM_PROMPT = """
            Quand tu produis un livrable autonome que l'utilisateur voudra probablement copier tel quel \
            — un e-mail, un courrier, une lettre, un document rédigé, du code, un fichier de configuration — \
            présente-le TOUJOURS dans un bloc de code Markdown délimité par ``` avec un token de langage adapté : \
            ```email pour un e-mail, ```markdown (ou ```text) pour un document ou un courrier, et le langage \
            correspondant pour du code ou une config (```java, ```yaml, ```json, ```bash, …). \
            Le texte d'explication ou de conversation reste HORS des blocs. \
            N'emballe pas une réponse conversationnelle normale qui n'a pas vocation à être copiée.""";

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UploadedFileRepository uploadedFileRepository;
    private final DocumentRepository documentRepository;
    private final MessageLibraryDocumentRepository messageLibraryDocumentRepository;
    private final AIProvider aiProvider;
    private final ModelCatalog modelCatalog;
    private final QuotaService quotaService;
    private final ByokKeyService byokKeyService;

    public ChatService(
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            UploadedFileRepository uploadedFileRepository,
            DocumentRepository documentRepository,
            MessageLibraryDocumentRepository messageLibraryDocumentRepository,
            AIProvider aiProvider,
            ModelCatalog modelCatalog,
            QuotaService quotaService,
            ByokKeyService byokKeyService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.uploadedFileRepository = uploadedFileRepository;
        this.documentRepository = documentRepository;
        this.messageLibraryDocumentRepository = messageLibraryDocumentRepository;
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
     * Variante avec import de documents de la bibliothèque personnelle (F-24) : le lien message ↔
     * documents désignés par {@code libraryDocumentIds} est persisté, et le texte OCR de ces documents
     * est ré-injecté dans le contexte fournisseur à <b>chaque</b> tour (SF-24-03), comme claude.ai.
     *
     * @param libraryDocumentIds documents de la bibliothèque à importer (null/vide = aucun ; max 10)
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
        // Validation AVANT toute écriture (isolation user_id) : un id de pièce jointe d'un autre
        // utilisateur => 404 ; un document de bibliothèque inconnu => 404, non prêt => 409. Aucune
        // persistance ni appel fournisseur si la validation échoue.
        resolveAttachments(userId, attachmentIds);
        validateLibraryDocuments(userId, libraryDocumentIds);
        Conversation conversation = resolveConversation(userId, conversationId, requestedModel, content);

        // Persistance du message utilisateur.
        Message userMessage = messageRepository.save(Message.builder()
                .conversationId(conversation.getId())
                .userId(userId)
                .role(MessageRole.USER)
                .content(content)
                .build());
        // Rattache les fichiers joints à la conversation (F-23) ET à ce message (F-25), et lie les
        // documents de bibliothèque importés à ce message (F-24) — après la persistance du message
        // (pour disposer de son id). Le tout est rejoué à chaque tour à la reconstruction de l'historique.
        linkAttachmentsToMessage(userId, conversation.getId(), userMessage.getId(), attachmentIds);
        linkLibraryDocumentsToMessage(userMessage.getId(), libraryDocumentIds);

        // Historique complet (incluant le message tout juste persisté) transmis au fournisseur : chaque
        // message ré-embarque ses pièces jointes (F-25) et le texte de ses documents importés (F-24).
        List<Message> history = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId());
        // Mode fournisseur par utilisateur : clé BYOK active si présente (déchiffrée à la volée, jamais
        // persistée ni journalisée), sinon clé plateforme (Hosted). Provider-neutre : la clé n'est
        // qu'un paramètre de la requête.
        String byokApiKey = byokKeyService.resolveActiveApiKey(userId).orElse(null);
        ChatCompletionResult completion = aiProvider.complete(new ChatCompletionRequest(
                conversation.getModel(), toProviderMessages(userId, history), List.of(), byokApiKey, COPYABLE_DELIVERABLES_SYSTEM_PROMPT));

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
        resolveAttachments(userId, attachmentIds);
        validateLibraryDocuments(userId, libraryDocumentIds);
        Conversation conversation = resolveConversation(userId, conversationId, requestedModel, content);

        Message userMessage = messageRepository.save(Message.builder()
                .conversationId(conversation.getId())
                .userId(userId)
                .role(MessageRole.USER)
                .content(content)
                .build());
        // Rattache fichiers (F-23/F-25) et documents de bibliothèque importés (F-24) à ce message — cf. reply().
        linkAttachmentsToMessage(userId, conversation.getId(), userMessage.getId(), attachmentIds);
        linkLibraryDocumentsToMessage(userMessage.getId(), libraryDocumentIds);

        List<Message> history = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId());
        String byokApiKey = byokKeyService.resolveActiveApiKey(userId).orElse(null);
        ChatCompletionRequest providerRequest = new ChatCompletionRequest(
                conversation.getModel(), toProviderMessages(userId, history), List.of(), byokApiKey, COPYABLE_DELIVERABLES_SYSTEM_PROMPT);

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
    private void linkAttachmentsToMessage(UUID userId, UUID conversationId, UUID messageId,
            List<UUID> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        for (UUID id : attachmentIds) {
            uploadedFileRepository.findByIdAndUserId(id, userId).ifPresent(file -> {
                boolean dirty = false;
                // « Premier rattachement gagne » (immuable) : un fichier appartient à la conversation
                // (F-23) et au message (F-25) où il a été joint, et n'y est pas déplacé ensuite.
                if (file.getConversationId() == null) {
                    file.setConversationId(conversationId);
                    dirty = true;
                }
                if (file.getMessageId() == null) {
                    file.setMessageId(messageId);
                    dirty = true;
                }
                if (dirty) {
                    uploadedFileRepository.save(file);
                }
            });
        }
    }

    /**
     * Valide les documents de bibliothèque importés (F-24), <b>avant</b> toute écriture : chaque
     * document est relu sous double filtre {@code id} + {@code user_id} (isolation) et doit disposer
     * d'un texte extrait exploitable. Ne persiste rien (le lien est posé après la sauvegarde du message).
     *
     * @throws DocumentNotFoundException si un id est inconnu ou appartient à un autre utilisateur
     * @throws DocumentNotReadyException si le texte du document n'est pas encore disponible
     */
    private void validateLibraryDocuments(UUID userId, List<UUID> libraryDocumentIds) {
        if (libraryDocumentIds == null || libraryDocumentIds.isEmpty()) {
            return;
        }
        for (UUID id : libraryDocumentIds) {
            Document document = documentRepository.findByIdAndUserId(id, userId)
                    .orElseThrow(() -> new DocumentNotFoundException("Document introuvable : " + id));
            String text = document.getExtractedText();
            if (text == null || text.isBlank()) {
                throw new DocumentNotReadyException(
                        "Le document « " + document.getFilename() + " » n'a pas encore de texte exploitable.");
            }
        }
    }

    /**
     * Persiste le lien message ↔ documents de bibliothèque importés (F-24). Ces liens permettent de
     * ré-injecter le texte des documents à chaque tour (reconstruction de l'historique), comme claude.ai.
     */
    private void linkLibraryDocumentsToMessage(UUID messageId, List<UUID> libraryDocumentIds) {
        if (libraryDocumentIds == null || libraryDocumentIds.isEmpty()) {
            return;
        }
        for (UUID documentId : libraryDocumentIds) {
            messageLibraryDocumentRepository.save(MessageLibraryDocument.builder()
                    .messageId(messageId)
                    .documentId(documentId)
                    .build());
        }
    }

    /**
     * Convertit l'historique en messages fournisseur. Chaque message ré-embarque <b>ses propres</b>
     * pièces jointes (F-25) et le texte de <b>ses</b> documents de bibliothèque importés (F-24) : les
     * deux restent donc dans le contexte à tous les tours suivants (comme claude.ai), pas seulement au
     * tour où ils ont été ajoutés. Chargements groupés (évitent le N+1). Isolation défensive : seuls
     * les documents appartenant à l'utilisateur sont ré-injectés.
     */
    private List<ChatMessage> toProviderMessages(UUID userId, List<Message> history) {
        if (history.isEmpty()) {
            return new ArrayList<>();
        }
        List<UUID> messageIds = history.stream().map(Message::getId).toList();

        // Pièces jointes par message (F-25).
        Map<UUID, List<ProviderAttachment>> attachmentsByMessage = new HashMap<>();
        for (UploadedFile file : uploadedFileRepository.findByMessageIdInOrderByCreatedAtAsc(messageIds)) {
            attachmentsByMessage
                    .computeIfAbsent(file.getMessageId(), key -> new ArrayList<>())
                    .add(new ProviderAttachment(file.getProviderFileId(), file.getMediaType()));
        }

        // Documents de bibliothèque importés par message (F-24), puis chargement groupé des documents.
        Map<UUID, List<UUID>> documentIdsByMessage = new HashMap<>();
        Set<UUID> allDocumentIds = new HashSet<>();
        for (MessageLibraryDocument link
                : messageLibraryDocumentRepository.findByMessageIdInOrderByCreatedAtAsc(messageIds)) {
            documentIdsByMessage
                    .computeIfAbsent(link.getMessageId(), key -> new ArrayList<>())
                    .add(link.getDocumentId());
            allDocumentIds.add(link.getDocumentId());
        }
        Map<UUID, Document> documentsById = new HashMap<>();
        if (!allDocumentIds.isEmpty()) {
            for (Document document : documentRepository.findAllById(allDocumentIds)) {
                if (document.getUserId().equals(userId)) {
                    documentsById.put(document.getId(), document);
                }
            }
        }

        List<ChatMessage> messages = new ArrayList<>(history.size());
        for (Message message : history) {
            ChatRole role = message.getRole() == MessageRole.ASSISTANT ? ChatRole.ASSISTANT : ChatRole.USER;
            List<ProviderAttachment> attachments =
                    attachmentsByMessage.getOrDefault(message.getId(), List.of());
            String content = withLibraryContext(
                    message.getContent(), documentIdsByMessage.get(message.getId()), documentsById);
            messages.add(new ChatMessage(role, content, attachments));
        }
        return messages;
    }

    /**
     * Préfixe le contenu d'un message avec le texte OCR des documents de bibliothèque qui y sont liés
     * (F-24). Relais Provider-First (texte déjà extrait, aucun ré-traitement), tronqué au garde-fou
     * {@link #LIBRARY_DOCUMENT_TEXT_MAX}. Le message persisté reste inchangé : le préfixe n'existe
     * qu'au moment de l'appel fournisseur.
     */
    private String withLibraryContext(String content, List<UUID> documentIds,
            Map<UUID, Document> documentsById) {
        if (documentIds == null || documentIds.isEmpty()) {
            return content;
        }
        StringBuilder prefix = new StringBuilder();
        for (UUID documentId : documentIds) {
            Document document = documentsById.get(documentId);
            if (document == null) {
                continue;
            }
            String text = document.getExtractedText();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (text.length() > LIBRARY_DOCUMENT_TEXT_MAX) {
                text = text.substring(0, LIBRARY_DOCUMENT_TEXT_MAX);
            }
            prefix.append("Document de la bibliothèque « ").append(document.getFilename())
                    .append(" » :\n\n").append(text).append("\n\n---\n\n");
        }
        return prefix.length() == 0 ? content : prefix.append(content).toString();
    }

    /** Résultat interne d'un tour de chat. */
    public record ChatResult(Conversation conversation, Message userMessage, Message assistantMessage) {
    }
}
