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
import fr.claudegateway.chat.ChatService.ChatResult;

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
    private AIProvider aiProvider;

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
        chatService = new ChatService(conversationRepository, messageRepository, aiProvider, modelCatalog);
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

        ChatResult result = chatService.reply(alice, null, "  Salut Claude  ", null);

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
        assertThatThrownBy(() -> chatService.reply(alice, null, "Salut", "gpt-4o"))
                .isInstanceOf(UnsupportedModelException.class);
        verify(conversationRepository, never()).save(any());
        verify(aiProvider, never()).complete(any());
    }

    @Test
    void rejectsConversationOwnedByAnotherUser() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findByIdAndUserId(conversationId, alice)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.reply(alice, conversationId, "Coucou", null))
                .isInstanceOf(ConversationNotFoundException.class);
        verify(aiProvider, never()).complete(any());
    }

    @Test
    void propagatesProviderUnavailable() {
        prepareExistingConversation();
        when(aiProvider.complete(any())).thenThrow(new AIProviderUnavailableException("dormant"));

        assertThatThrownBy(() -> chatService.reply(alice, UUID.randomUUID(), "Coucou", null))
                .isInstanceOf(AIProviderUnavailableException.class);
    }

    @Test
    void propagatesProviderError() {
        prepareExistingConversation();
        when(aiProvider.complete(any())).thenThrow(new AIProviderException("boom"));

        assertThatThrownBy(() -> chatService.reply(alice, UUID.randomUUID(), "Coucou", null))
                .isInstanceOf(AIProviderException.class);
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
