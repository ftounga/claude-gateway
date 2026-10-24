package fr.claudegateway.atelier.sends;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.actions.TerminalActionService;

/** La note « messages marqués envoyés » jointe au tour (F-211 / SF-211-01). */
class MessageBlockSendNoteTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final OffsetDateTime lastTurn = OffsetDateTime.of(2026, 10, 24, 15, 0, 0, 0, ZoneOffset.UTC);
    private MessageBlockSendRepository repository;
    private AtelierMessageRepository messages;
    private MessageBlockSendService service;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        repository = mock(MessageBlockSendRepository.class);
        messages = mock(AtelierMessageRepository.class);
        service = new MessageBlockSendService(repository, messages, mock(WorkspaceService.class),
                mock(TerminalActionService.class), Clock.systemUTC());
        workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
    }

    private void lastUserMessageAt(OffsetDateTime at) {
        AtelierMessage message = AtelierMessage.builder().workspaceId(workspaceId).userId(userId).role("USER")
                .content("x").build();
        message.setCreatedAt(at);
        when(messages.findFirstByWorkspaceIdAndUserIdAndRoleOrderByCreatedAtDesc(workspaceId, userId, "USER"))
                .thenReturn(Optional.of(message));
    }

    private static MessageBlockSend send(String subject, String excerpt, String recipient, UUID actionId) {
        return MessageBlockSend.builder().kind(MessageBlockSend.Kind.MAIL).subject(subject).excerpt(excerpt)
                .recipient(recipient).attenteKey(actionId == null ? null : "acces-forge")
                .attenteActionId(actionId)
                .sentAt(OffsetDateTime.of(2026, 10, 24, 15, 42, 0, 0, ZoneOffset.UTC)).build();
    }

    @Test
    @DisplayName("aucun envoi depuis le dernier tour : note vide, consigne inchangée")
    void emptyWithoutSends() {
        lastUserMessageAt(lastTurn);
        when(repository.findByUserIdAndWorkspaceIdAndCancelledAtIsNullAndSentAtAfterOrderBySentAtAsc(
                eq(userId), eq(workspaceId), any())).thenReturn(List.of());

        assertThat(service.noteFor(userId, workspace)).isEmpty();
    }

    @Test
    @DisplayName("pas de tour précédent : note vide")
    void emptyWithoutPreviousTurn() {
        when(messages.findFirstByWorkspaceIdAndUserIdAndRoleOrderByCreatedAtDesc(workspaceId, userId, "USER"))
                .thenReturn(Optional.empty());

        assertThat(service.noteFor(userId, workspace)).isEmpty();
    }

    @Test
    @DisplayName("les envois postérieurs au dernier message utilisateur sont listés, heure de Paris, attente nommée")
    void listsTheSends() {
        lastUserMessageAt(lastTurn);
        when(repository.findByUserIdAndWorkspaceIdAndCancelledAtIsNullAndSentAtAfterOrderBySentAtAsc(
                userId, workspaceId, lastTurn)).thenReturn(List.of(
                        send("Compte forge", "Bonjour", "Zahi", UUID.randomUUID()),
                        send(null, "Salut l'équipe", null, null)));

        String note = service.noteFor(userId, workspace);

        assertThat(note).startsWith(MessageBlockSendService.NOTE_HEADER).endsWith(MessageBlockSendService.NOTE_FOOTER)
                .contains("- « Compte forge » (mail, à Zahi, 24/10 17:42) — attente key=acces-forge passée à « demandé »")
                .contains("- « Salut l'équipe … » (mail, 24/10 17:42)\n")
                .contains("Ne lui redemande pas");
    }

    @Test
    @DisplayName("bornée : au-delà de dix envois, le reste est compté")
    void bounded() {
        lastUserMessageAt(lastTurn);
        List<MessageBlockSend> many = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            many.add(send("Objet " + i, null, null, null));
        }
        when(repository.findByUserIdAndWorkspaceIdAndCancelledAtIsNullAndSentAtAfterOrderBySentAtAsc(
                userId, workspaceId, lastTurn)).thenReturn(many);

        String note = service.noteFor(userId, workspace);

        assertThat(note).contains("« Objet 9 »").doesNotContain("« Objet 10 »").contains("(… et 4 autre(s).)");
    }
}
