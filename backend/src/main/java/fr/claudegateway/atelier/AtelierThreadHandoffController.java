package fr.claudegateway.atelier;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.dto.ThreadHandoffResponse;
import fr.claudegateway.atelier.dto.ThreadHandoffUpdateRequest;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>La passation du fil</b> (F-117 / SF-117-09) : {@code GET} pour la lire, {@code PUT} pour la
 * corriger. Aucun tour modèle ; l'identité vient du JWT ({@link CurrentUser}) et l'isolation
 * ({@code requireOwned}) est appliquée par le service.
 */
@RestController
@RequestMapping("/workspaces/{id}/chat/handoff")
public class AtelierThreadHandoffController {

    private final AtelierThreadHandoffService handoffService;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public AtelierThreadHandoffController(AtelierThreadHandoffService handoffService,
            AtelierAccessService atelierAccess, CurrentUser currentUser) {
        this.handoffService = handoffService;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ThreadHandoffResponse read(@PathVariable UUID id) {
        atelierAccess.requireTerminalAccess(id);
        return handoffService.read(currentUser.requireId(), id);
    }

    @PutMapping
    public ThreadHandoffResponse correct(@PathVariable UUID id,
            @RequestBody ThreadHandoffUpdateRequest request) {
        atelierAccess.requireTerminalAccess(id);
        return handoffService.correct(currentUser.requireId(), id,
                request == null ? null : request.summary());
    }
}
