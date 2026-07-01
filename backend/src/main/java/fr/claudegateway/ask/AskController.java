package fr.claudegateway.ask;

import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.ask.AskService.AskResult;
import fr.claudegateway.ask.dto.AskRequest;
import fr.claudegateway.ask.dto.AskResponse;
import fr.claudegateway.auth.CurrentUser;
import jakarta.validation.Valid;

/**
 * Endpoint du Q&A documentaire (F-07). L'identité provient exclusivement du {@link CurrentUser}
 * (JWT) : l'isolation {@code user_id} est appliquée dans le service, jamais depuis un paramètre client.
 * Aucune logique métier ici (orchestration dans {@link AskService}).
 */
@RestController
@RequestMapping("/ask")
public class AskController {

    private final AskService askService;
    private final CurrentUser currentUser;

    public AskController(AskService askService, CurrentUser currentUser) {
        this.askService = askService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public AskResponse ask(@Valid @RequestBody AskRequest request) {
        UUID userId = currentUser.requireId();
        AskResult result = askService.ask(userId, request.question(), request.model(), request.topK());
        return new AskResponse(result.answer(), result.model(), result.grounded(), result.citations());
    }
}
