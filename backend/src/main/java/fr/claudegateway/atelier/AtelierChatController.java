package fr.claudegateway.atelier;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.atelier.dto.AtelierChatRequest;
import fr.claudegateway.atelier.dto.AtelierChatResponse;
import fr.claudegateway.atelier.dto.AtelierMessageResponse;
import fr.claudegateway.auth.CurrentUser;
import jakarta.validation.Valid;

/**
 * Endpoints de conversation de l'Atelier (F-28 / SF-28-02) : Claude lit/édite les fichiers du
 * workspace via une boucle tool-use. Identité issue du {@link CurrentUser} ; isolation {@code user_id}
 * appliquée dans le service (workspace d'un autre utilisateur => 404).
 */
@RestController
@RequestMapping("/workspaces/{id}/chat")
public class AtelierChatController {

    private final AtelierChatService atelierChatService;
    private final CurrentUser currentUser;

    public AtelierChatController(AtelierChatService atelierChatService, CurrentUser currentUser) {
        this.atelierChatService = atelierChatService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public AtelierChatResponse chat(@PathVariable UUID id, @Valid @RequestBody AtelierChatRequest request) {
        AtelierChatResult result = atelierChatService.chat(currentUser.requireId(), id, request.message());
        return new AtelierChatResponse(result.reply(), result.actions(), result.messageId());
    }

    @GetMapping
    public List<AtelierMessageResponse> history(@PathVariable UUID id) {
        return atelierChatService.history(currentUser.requireId(), id).stream()
                .map(AtelierMessageResponse::from)
                .toList();
    }
}
