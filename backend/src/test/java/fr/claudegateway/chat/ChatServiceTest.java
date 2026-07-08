package fr.claudegateway.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.AIProviderException;
import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ModelCatalog;
import fr.claudegateway.ai.ProviderAttachment;
import fr.claudegateway.chat.ChatService.ChatResult;
import fr.claudegateway.quota.QuotaExceededException;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.upload.UploadedFile;
import fr.claudegateway.upload.UploadedFileRepository;

/**
 * Tests unitaires du cœur du proxy de chat : création de conversation, persistance USER/ASSISTANT,
 * relais via {@link AIProvider}, validation du modèle et isolation {@code user_id}.
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private UploadedFileRepository uploadedFileRepository;

    @Mock
    private fr.claudegateway.ocr.DocumentRepository documentRepository;

    @Mock
    private MessageLibraryDocumentRepository messageLibraryDocumentRepository;

    @Mock
    private AIProvider aiProvider;

    @Mock
    private QuotaService quotaService;

    @Mock
    private fr.claudegateway.byok.ByokKeyService byokKeyService;

    private ChatService chatService;

    private final UUID alice = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ModelCatalog modelCatalog = new ModelCatalog() {
            @Override
            public String defaultModel() {
                return "claude-opus-4-8";
            }

            @Override
            public List<String> availableModels() {
                return List.of("claude-opus-4-8", "claude-sonnet-5");
            }
        };
        chatService = new ChatService(conversationRepository, messageRepository, uploadedFileRepository,
                documentRepository, messageLibraryDocumentRepository, aiProvider, modelCatalog, quotaService,
                byokKeyService);
    }

    /** Persiste le message avec un id simulé et renvoie l'entité (comportement JpaRepository.save). */
    private void stubMessageSaveEchoesWithId() {
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
            Message m = invocation.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });
    }

    @Test
    void createsConversationAndPersistsBothMessages() {
        stubMessageSaveEchoesWithId();
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> {
            Conversation c = invocation.getArgument(0);
            if (c.getId() == null) {
                c.setId(UUID.randomUUID());
            }
            return c;
        });
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(any()))
                .thenReturn(List.of()); // contenu non déterminant pour ce test
        when(aiProvider.complete(any(ChatCompletionRequest.class)))
                .thenReturn(new ChatCompletionResult("Bonjour !", "claude-opus-4-8", 10, 5));

        ChatResult result = chatService.reply(alice, null, "  Salut Claude  ", null, null);

        // Conversation créée pour Alice, modèle par défaut, titre dérivé du message (trim).
        ArgumentCaptor<Conversation> convCaptor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository, org.mockito.Mockito.atLeastOnce()).save(convCaptor.capture());
        Conversation created = convCaptor.getAllValues().get(0);
        assertThat(created.getUserId()).isEqualTo(alice);
        assertThat(created.getModel()).isEqualTo("claude-opus-4-8");
        assertThat(created.getTitle()).isEqualTo("Salut Claude");

        // Deux messages persistés : USER puis ASSISTANT (avec modèle).
        ArgumentCaptor<Message> msgCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, org.mockito.Mockito.times(2)).save(msgCaptor.capture());
        List<Message> saved = msgCaptor.getAllValues();
        assertThat(saved.get(0).getRole()).isEqualTo(MessageRole.USER);
        assertThat(saved.get(0).getContent()).isEqualTo("Salut Claude");
        assertThat(saved.get(0).getUserId()).isEqualTo(alice);
        assertThat(saved.get(1).getRole()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(saved.get(1).getContent()).isEqualTo("Bonjour !");
        assertThat(saved.get(1).getModel()).isEqualTo("claude-opus-4-8");

        assertThat(result.assistantMessage().getContent()).isEqualTo("Bonjour !");
    }

    @Test
    void usesActiveByokKeyForProviderCallWhenPresent() {
        stubMessageSaveEchoesWithId();
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> {
            Conversation c = invocation.getArgument(0);
            if (c.getId() == null) {
                c.setId(UUID.randomUUID());
            }
            return c;
        });
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        // Clé BYOK active de l'utilisateur (déchiffrée par ByokKeyService).
        when(byokKeyService.resolveActiveApiKey(alice)).thenReturn(Optional.of("sk-ant-user-key"));
        when(aiProvider.complete(any(ChatCompletionRequest.class)))
                .thenReturn(new ChatCompletionResult("ok", "claude-opus-4-8", 1, 1));

        chatService.reply(alice, null, "Salut", null, null);

        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(aiProvider).complete(captor.capture());
        assertThat(captor.getValue().apiKey()).isEqualTo("sk-ant-user-key");
    }

    @Test
    void usesPlatformKeyWhenNoActiveByokKey() {
        stubMessageSaveEchoesWithId();
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> {
            Conversation c = invocation.getArgument(0);
            if (c.getId() == null) {
                c.setId(UUID.randomUUID());
            }
            return c;
        });
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        // Aucune clé BYOK active (Optional.empty par défaut) => clé plateforme (apiKey null).
        when(aiProvider.complete(any(ChatCompletionRequest.class)))
                .thenReturn(new ChatCompletionResult("ok", "claude-opus-4-8", 1, 1));

        chatService.reply(alice, null, "Salut", null, null);

        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(aiProvider).complete(captor.capture());
        assertThat(captor.getValue().apiKey()).isNull();
    }

    @Test
    void rejectsUnknownModel() {
        assertThatThrownBy(() -> chatService.reply(alice, null, "Salut", "gpt-4o", null))
                .isInstanceOf(UnsupportedModelException.class);
        verify(conversationRepository, never()).save(any());
        verify(aiProvider, never()).complete(any());
    }

    @Test
    void rejectsConversationOwnedByAnotherUser() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findByIdAndUserId(conversationId, alice)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.reply(alice, conversationId, "Coucou", null, null))
                .isInstanceOf(ConversationNotFoundException.class);
        verify(aiProvider, never()).complete(any());
    }

    @Test
    void propagatesProviderUnavailable() {
        prepareExistingConversation();
        when(aiProvider.complete(any())).thenThrow(new AIProviderUnavailableException("dormant"));

        assertThatThrownBy(() -> chatService.reply(alice, UUID.randomUUID(), "Coucou", null, null))
                .isInstanceOf(AIProviderUnavailableException.class);
    }

    @Test
    void propagatesProviderError() {
        prepareExistingConversation();
        when(aiProvider.complete(any())).thenThrow(new AIProviderException("boom"));

        assertThatThrownBy(() -> chatService.reply(alice, UUID.randomUUID(), "Coucou", null, null))
                .isInstanceOf(AIProviderException.class);
    }

    @Test
    void linksAttachmentToMessageAndConversation() {
        prepareExistingConversation();
        UUID fileId = UUID.randomUUID();
        UploadedFile file = UploadedFile.builder()
                .id(fileId).userId(alice).providerFileId("file_abc")
                .filename("rapport.pdf").mediaType("application/pdf").sizeBytes(4).build();
        when(uploadedFileRepository.findByIdAndUserId(fileId, alice)).thenReturn(Optional.of(file));
        when(aiProvider.complete(any(ChatCompletionRequest.class)))
                .thenReturn(new ChatCompletionResult("Reçu", "claude-opus-4-8", 1, 1));

        chatService.reply(alice, UUID.randomUUID(), "Analyse ce doc", null, List.of(fileId));

        // F-25 : le fichier est rattaché au message (pour être rejoué à chaque tour) ET à la
        // conversation (F-23). Le forwarding par message à travers l'historique est couvert en intégration.
        ArgumentCaptor<UploadedFile> fileCaptor = ArgumentCaptor.forClass(UploadedFile.class);
        verify(uploadedFileRepository).save(fileCaptor.capture());
        assertThat(fileCaptor.getValue().getMessageId()).isNotNull();
        assertThat(fileCaptor.getValue().getConversationId()).isNotNull();
    }

    @Test
    void stampsConversationOnAttachedFile() {
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = Conversation.builder()
                .id(conversationId).userId(alice).title("t").model("claude-opus-4-8").build();
        when(conversationRepository.findByIdAndUserId(conversationId, alice))
                .thenReturn(Optional.of(conversation));
        stubMessageSaveEchoesWithId();
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        UUID fileId = UUID.randomUUID();
        UploadedFile file = UploadedFile.builder()
                .id(fileId).userId(alice).providerFileId("file_abc")
                .filename("rapport.pdf").mediaType("application/pdf").sizeBytes(4).build();
        when(uploadedFileRepository.findByIdAndUserId(fileId, alice)).thenReturn(Optional.of(file));
        when(aiProvider.complete(any(ChatCompletionRequest.class)))
                .thenReturn(new ChatCompletionResult("Reçu", "claude-opus-4-8", 1, 1));

        chatService.reply(alice, conversationId, "Analyse", null, List.of(fileId));

        ArgumentCaptor<UploadedFile> fileCaptor = ArgumentCaptor.forClass(UploadedFile.class);
        verify(uploadedFileRepository).save(fileCaptor.capture());
        assertThat(fileCaptor.getValue().getConversationId()).isEqualTo(conversationId);
    }

    @Test
    void doesNotReassignAttachmentAlreadyLinkedToConversation() {
        UUID conversationId = UUID.randomUUID();
        UUID otherConversationId = UUID.randomUUID();
        Conversation conversation = Conversation.builder()
                .id(conversationId).userId(alice).title("t").model("claude-opus-4-8").build();
        when(conversationRepository.findByIdAndUserId(conversationId, alice))
                .thenReturn(Optional.of(conversation));
        stubMessageSaveEchoesWithId();
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        UUID fileId = UUID.randomUUID();
        UUID otherMessageId = UUID.randomUUID();
        // Fichier déjà rattaché à une autre conversation ET un autre message (« premier rattachement gagne »).
        UploadedFile file = UploadedFile.builder()
                .id(fileId).userId(alice).conversationId(otherConversationId).messageId(otherMessageId)
                .providerFileId("file_abc")
                .filename("rapport.pdf").mediaType("application/pdf").sizeBytes(4).build();
        when(uploadedFileRepository.findByIdAndUserId(fileId, alice)).thenReturn(Optional.of(file));
        when(aiProvider.complete(any(ChatCompletionRequest.class)))
                .thenReturn(new ChatCompletionResult("Reçu", "claude-opus-4-8", 1, 1));

        chatService.reply(alice, conversationId, "Analyse", null, List.of(fileId));

        // Association immuable : ni la conversation ni le message ne sont réassignés (aucune écriture).
        verify(uploadedFileRepository, never()).save(any());
        assertThat(file.getConversationId()).isEqualTo(otherConversationId);
        assertThat(file.getMessageId()).isEqualTo(otherMessageId);
    }

    @Test
    void rejectsAttachmentOwnedByAnotherUser() {
        UUID conversationId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        when(uploadedFileRepository.findByIdAndUserId(fileId, alice)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.reply(alice, conversationId, "Coucou", null, List.of(fileId)))
                .isInstanceOf(AttachmentNotFoundException.class);
        // Aucune écriture ni appel fournisseur : la résolution échoue avant tout effet de bord.
        verify(aiProvider, never()).complete(any());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void recordsTokenUsageAfterSuccessfulReply() {
        prepareExistingConversation();
        when(aiProvider.complete(any(ChatCompletionRequest.class)))
                .thenReturn(new ChatCompletionResult("Reçu", "claude-opus-4-8", 42, 17));

        chatService.reply(alice, UUID.randomUUID(), "Bonjour", null, null);

        // Le quota est vérifié avant l'appel et la consommation exacte est enregistrée après.
        verify(quotaService).assertWithinQuota(alice);
        verify(quotaService).recordUsage(alice, 42, 17);
    }

    @Test
    void blocksWhenQuotaExceededWithoutCallingProviderOrPersisting() {
        org.mockito.Mockito.doThrow(new QuotaExceededException("quota atteint"))
                .when(quotaService).assertWithinQuota(alice);

        assertThatThrownBy(() -> chatService.reply(alice, UUID.randomUUID(), "Bonjour", null, null))
                .isInstanceOf(QuotaExceededException.class);

        // Aucun effet de bord : ni message persisté, ni appel fournisseur, ni enregistrement d'usage.
        verify(messageRepository, never()).save(any());
        verify(aiProvider, never()).complete(any());
        org.mockito.Mockito.verify(quotaService, never()).recordUsage(any(UUID.class),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    // ---- F-24 : import d'un document de la bibliothèque personnelle comme contexte ----

    @Test
    void linksImportedLibraryDocumentToMessage() {
        prepareExistingConversation();
        UUID docId = UUID.randomUUID();
        fr.claudegateway.ocr.Document document = fr.claudegateway.ocr.Document.builder()
                .id(docId).userId(alice).filename("cv.pdf")
                .status(fr.claudegateway.ocr.DocumentStatus.EXTRACTED)
                .extractedText("Jean Dupont — développeur Java").build();
        when(documentRepository.findByIdAndUserId(docId, alice)).thenReturn(Optional.of(document));
        when(aiProvider.complete(any(ChatCompletionRequest.class)))
                .thenReturn(new ChatCompletionResult("Vu", "claude-opus-4-8", 1, 1));

        chatService.reply(alice, UUID.randomUUID(), "Résume ce CV", null, null, List.of(docId));

        // Le lien message ↔ document est persisté (SF-24-03) : le texte est ré-injecté à chaque tour
        // depuis ce lien à la reconstruction de l'historique. L'injection bout-en-bout est couverte en
        // intégration (le stub d'historique ici est vide, donc pas de reconstruction à vérifier en unit).
        ArgumentCaptor<MessageLibraryDocument> linkCaptor =
                ArgumentCaptor.forClass(MessageLibraryDocument.class);
        verify(messageLibraryDocumentRepository).save(linkCaptor.capture());
        assertThat(linkCaptor.getValue().getDocumentId()).isEqualTo(docId);
        assertThat(linkCaptor.getValue().getMessageId()).isNotNull();
    }

    @Test
    void rejectsLibraryDocumentOwnedByAnotherUser() {
        UUID docId = UUID.randomUUID();
        when(documentRepository.findByIdAndUserId(docId, alice)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.reply(alice, UUID.randomUUID(), "Coucou", null, null, List.of(docId)))
                .isInstanceOf(fr.claudegateway.ocr.DocumentNotFoundException.class);
        // Isolation : aucune écriture ni appel fournisseur avant la résolution du contexte.
        verify(aiProvider, never()).complete(any());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void rejectsLibraryDocumentWithoutExtractedText() {
        UUID docId = UUID.randomUUID();
        fr.claudegateway.ocr.Document document = fr.claudegateway.ocr.Document.builder()
                .id(docId).userId(alice).filename("scan.pdf")
                .status(fr.claudegateway.ocr.DocumentStatus.PROCESSING)
                .extractedText(null).build();
        when(documentRepository.findByIdAndUserId(docId, alice)).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> chatService.reply(alice, UUID.randomUUID(), "Résume", null, null, List.of(docId)))
                .isInstanceOf(DocumentNotReadyException.class);
        verify(aiProvider, never()).complete(any());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void doesNotTouchDocumentRepositoryWhenNoLibraryDocuments() {
        prepareExistingConversation();
        when(aiProvider.complete(any(ChatCompletionRequest.class)))
                .thenReturn(new ChatCompletionResult("ok", "claude-opus-4-8", 1, 1));

        chatService.reply(alice, UUID.randomUUID(), "Salut", null, null, null);

        verify(documentRepository, never()).findByIdAndUserId(any(), any());
    }

    private void prepareExistingConversation() {
        Conversation conversation = Conversation.builder()
                .id(UUID.randomUUID()).userId(alice).title("t").model("claude-opus-4-8").build();
        when(conversationRepository.findByIdAndUserId(any(), org.mockito.ArgumentMatchers.eq(alice)))
                .thenReturn(Optional.of(conversation));
        stubMessageSaveEchoesWithId();
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
    }
}
