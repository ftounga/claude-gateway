package fr.claudegateway.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.chat.dto.ConversationDetailResponse;
import fr.claudegateway.chat.dto.ConversationFileResponse;
import fr.claudegateway.chat.dto.ConversationSummaryResponse;
import fr.claudegateway.chat.dto.RenameConversationRequest;
import jakarta.validation.Valid;

/**
 * Gestion des conversations de l'utilisateur courant : liste, détail, renommage, suppression.
 * Toute opération est bornée à l'utilisateur du JWT ({@link CurrentUser}) — isolation {@code user_id}.
 */
@RestController
@RequestMapping("/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final CurrentUser currentUser;

    public ConversationController(ConversationService conversationService, CurrentUser currentUser) {
        this.conversationService = conversationService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<ConversationSummaryResponse> list() {
        UUID userId = currentUser.requireId();
        return conversationService.listForUser(userId).stream()
                .map(ConversationSummaryResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ConversationDetailResponse detail(@PathVariable UUID id) {
        UUID userId = currentUser.requireId();
        Conversation conversation = conversationService.getOwned(id, userId);
        List<Message> messages = conversationService.messagesOf(id, userId);
        return ConversationDetailResponse.from(conversation, messages);
    }

    /**
     * Dossier de fichiers de la conversation (F-23) : les fichiers téléversés rattachés à cette
     * conversation, du plus récent au plus ancien. Isolation {@code user_id} (conversation d'autrui → 404).
     */
    @GetMapping("/{id}/files")
    public List<ConversationFileResponse> files(@PathVariable UUID id) {
        UUID userId = currentUser.requireId();
        return conversationService.filesOf(id, userId).stream()
                .map(ConversationFileResponse::from)
                .toList();
    }

    @PatchMapping("/{id}")
    public ConversationSummaryResponse rename(
            @PathVariable UUID id,
            @Valid @RequestBody RenameConversationRequest request) {
        UUID userId = currentUser.requireId();
        return ConversationSummaryResponse.from(conversationService.rename(id, userId, request.title()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        UUID userId = currentUser.requireId();
        conversationService.delete(id, userId);
        return ResponseEntity.noContent().build();
    }
}
