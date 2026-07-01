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
    private AIProvider aiProvider;

    @Mock
    private QuotaService quotaService;

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
                aiProvider, modelCatalog, quotaService);
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
    void resolvesAttachmentsAndPassesThemToProvider() {
        prepareExistingConversation();
        UUID fileId = UUID.randomUUID();
        UploadedFile file = UploadedFile.builder()
                .id(fileId).userId(alice).providerFileId("file_abc")
                .filename("rapport.pdf").mediaType("application/pdf").sizeBytes(4).build();
        when(uploadedFileRepository.findByIdAndUserId(fileId, alice)).thenReturn(Optional.of(file));
        when(aiProvider.complete(any(ChatCompletionRequest.class)))
                .thenReturn(new ChatCompletionResult("Reçu", "claude-opus-4-8", 1, 1));

        chatService.reply(alice, UUID.randomUUID(), "Analyse ce doc", null, List.of(fileId));

        ArgumentCaptor<ChatCompletionRequest> reqCaptor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(aiProvider).complete(reqCaptor.capture());
        List<ProviderAttachment> attachments = reqCaptor.getValue().attachments();
        assertThat(attachments).hasSize(1);
        assertThat(attachments.get(0).providerFileId()).isEqualTo("file_abc");
        assertThat(attachments.get(0).mediaType()).isEqualTo("application/pdf");
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

    private void prepareExistingConversation() {
        Conversation conversation = Conversation.builder()
                .id(UUID.randomUUID()).userId(alice).title("t").model("claude-opus-4-8").build();
        when(conversationRepository.findByIdAndUserId(any(), org.mockito.ArgumentMatchers.eq(alice)))
                .thenReturn(Optional.of(conversation));
        stubMessageSaveEchoesWithId();
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
    }
}
