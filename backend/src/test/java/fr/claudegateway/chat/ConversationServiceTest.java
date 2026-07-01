package fr.claudegateway.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests unitaires de l'isolation {@code user_id} pour la gestion des conversations : détail,
 * renommage et suppression sur une conversation d'autrui sont indistincts d'une conversation absente.
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private MessageRepository messageRepository;

    @InjectMocks
    private ConversationService conversationService;

    private final UUID alice = UUID.randomUUID();

    @Test
    void getOwnedReturnsConversationOfUser() {
        UUID id = UUID.randomUUID();
        Conversation conversation = Conversation.builder()
                .id(id).userId(alice).title("t").model("claude-opus-4-8").build();
        when(conversationRepository.findByIdAndUserId(id, alice)).thenReturn(Optional.of(conversation));

        assertThat(conversationService.getOwned(id, alice)).isSameAs(conversation);
    }

    @Test
    void getOwnedThrowsForForeignConversation() {
        UUID id = UUID.randomUUID();
        when(conversationRepository.findByIdAndUserId(id, alice)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> conversationService.getOwned(id, alice))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    void renameThrowsForForeignConversation() {
        UUID id = UUID.randomUUID();
        when(conversationRepository.findByIdAndUserId(id, alice)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> conversationService.rename(id, alice, "Nouveau titre"))
                .isInstanceOf(ConversationNotFoundException.class);
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void deleteThrowsForForeignConversation() {
        UUID id = UUID.randomUUID();
        when(conversationRepository.findByIdAndUserId(id, alice)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> conversationService.delete(id, alice))
                .isInstanceOf(ConversationNotFoundException.class);
        verify(conversationRepository, never()).delete(any());
    }

    @Test
    void renameTrimsTitle() {
        UUID id = UUID.randomUUID();
        Conversation conversation = Conversation.builder()
                .id(id).userId(alice).title("ancien").model("claude-opus-4-8").build();
        when(conversationRepository.findByIdAndUserId(id, alice)).thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(i -> i.getArgument(0));

        Conversation renamed = conversationService.rename(id, alice, "  Nouveau  ");

        assertThat(renamed.getTitle()).isEqualTo("Nouveau");
    }
}
