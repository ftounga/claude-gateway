package fr.claudegateway.help;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.help.dto.HelpChatRequest;
import fr.claudegateway.help.dto.HelpChatResponse;

/**
 * Chatbot d'aide produit (F-54 / SF-54-01). L'identité provient exclusivement du {@link CurrentUser}
 * (JWT) : l'endpoint est authentifié par la règle {@code anyRequest().authenticated()} de la chaîne
 * de sécurité. Controller fin — aucune logique métier ici.
 */
@RestController
@RequestMapping("/help")
public class HelpChatController {

    private final HelpChatService helpChatService;
    private final CurrentUser currentUser;

    public HelpChatController(HelpChatService helpChatService, CurrentUser currentUser) {
        this.helpChatService = helpChatService;
        this.currentUser = currentUser;
    }

    /** Pose une question sur l'usage du produit et renvoie une réponse fondée sur la documentation. */
    @PostMapping("/chat")
    public HelpChatResponse chat(@Valid @RequestBody HelpChatRequest request) {
        UUID userId = currentUser.requireId();
        return helpChatService.answer(userId, request.message());
    }
}
