package fr.claudegateway.chat;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.ai.ModelCatalog;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.chat.ChatService.ChatResult;
import fr.claudegateway.chat.dto.ChatRequest;
import fr.claudegateway.chat.dto.ChatResponse;
import fr.claudegateway.chat.dto.MessageResponse;
import fr.claudegateway.chat.dto.ModelsResponse;
import jakarta.validation.Valid;

/**
 * Endpoints du proxy de chat Hosted. L'identité provient exclusivement du {@link CurrentUser}
 * (JWT) : l'isolation {@code user_id} est appliquée dans le service, jamais depuis un paramètre client.
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;
    private final CurrentUser currentUser;
    private final ModelCatalog modelCatalog;

    public ChatController(ChatService chatService, CurrentUser currentUser, ModelCatalog modelCatalog) {
        this.chatService = chatService;
        this.currentUser = currentUser;
        this.modelCatalog = modelCatalog;
    }

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        UUID userId = currentUser.requireId();
        ChatResult result = chatService.reply(userId, request.conversationId(), request.message(), request.model());
        return new ChatResponse(
                result.conversation().getId(),
                MessageResponse.from(result.assistantMessage()),
                result.conversation().getModel());
    }

    /** Modèles sélectionnables et modèle par défaut (aucune donnée sensible). */
    @GetMapping("/models")
    public ModelsResponse models() {
        return new ModelsResponse(modelCatalog.defaultModel(), modelCatalog.availableModels());
    }
}
