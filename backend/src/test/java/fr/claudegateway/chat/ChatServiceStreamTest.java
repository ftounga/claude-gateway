package fr.claudegateway.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.AIProviderException;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ModelCatalog;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.chat.ChatService.StreamContext;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.upload.UploadedFileRepository;

/**
 * Tests unitaires du streaming de chat (SF-02-04) : pré-vol (`prepareStream`) et relais/persistance
 * (`streamAndPersist`). Un flux réussi persiste l'ASSISTANT et facture une fois ; un flux en échec
 * ne persiste rien.
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceStreamTest {

    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private UploadedFileRepository uploadedFileRepository;
    @Mock
    private fr.claudegateway.ocr.DocumentRepository documentRepository;
    @Mock
    private AIProvider aiProvider;
    @Mock
    private QuotaService quotaService;
    @Mock
    private ByokKeyService byokKeyService;

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
                documentRepository, aiProvider, modelCatalog, quotaService, byokKeyService);
    }

    private void stubSaveEchoesId() {
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> {
            Conversation c = inv.getArgument(0);
            if (c.getId() == null) {
                c.setId(UUID.randomUUID());
            }
            return c;
        });
    }

    @Test
    void prepareStreamAppliesQuotaAndPersistsUserMessage() {
        stubSaveEchoesId();
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(byokKeyService.resolveActiveApiKey(alice)).thenReturn(Optional.empty());

        StreamContext context = chatService.prepareStream(alice, null, "  Salut Claude  ", null, null);

        verify(quotaService).assertWithinQuota(alice);
        assertThat(context.userId()).isEqualTo(alice);
        assertThat(context.userMessage().getRole()).isEqualTo(MessageRole.USER);
        assertThat(context.userMessage().getContent()).isEqualTo("Salut Claude");
        assertThat(context.providerRequest().model()).isEqualTo("claude-opus-4-8");
    }

    @Test
    void streamAndPersistRelaysDeltasPersistsAssistantAndRecordsUsageOnce() {
        stubSaveEchoesId();
        when(aiProvider.streamComplete(any(ChatCompletionRequest.class), any())).thenAnswer(inv -> {
            Consumer<String> onDelta = inv.getArgument(1);
            onDelta.accept("Bon");
            onDelta.accept("jour");
            return new ChatCompletionResult("Bonjour", "claude-opus-4-8", 11, 5);
        });

        StreamContext context = contextFor();
        List<String> deltas = new ArrayList<>();

        Message assistant = chatService.streamAndPersist(context, deltas::add);

        assertThat(deltas).containsExactly("Bon", "jour");
        assertThat(assistant.getRole()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(assistant.getContent()).isEqualTo("Bonjour");
        assertThat(assistant.getModel()).isEqualTo("claude-opus-4-8");

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(MessageRole.ASSISTANT);
        verify(quotaService, times(1)).recordUsage(eq(alice), eq(11), eq(5));
    }

    @Test
    void streamAndPersistDoesNotPersistNorRecordWhenProviderFails() {
        when(aiProvider.streamComplete(any(ChatCompletionRequest.class), any()))
                .thenThrow(new AIProviderException("boom", null));

        StreamContext context = contextFor();

        assertThatThrownBy(() -> chatService.streamAndPersist(context, delta -> { }))
                .isInstanceOf(AIProviderException.class);

        verify(messageRepository, never()).save(any(Message.class));
        verify(quotaService, never()).recordUsage(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    /** Contexte de streaming minimal (conversation persistée) pour tester {@code streamAndPersist}. */
    private StreamContext contextFor() {
        Conversation conversation = Conversation.builder()
                .userId(alice).title("t").model("claude-opus-4-8").build();
        conversation.setId(UUID.randomUUID());
        Message userMessage = Message.builder()
                .conversationId(conversation.getId()).userId(alice)
                .role(MessageRole.USER).content("Salut").build();
        ChatCompletionRequest providerRequest = new ChatCompletionRequest("claude-opus-4-8", List.of());
        return new StreamContext(alice, conversation, userMessage, providerRequest);
    }
}
