package fr.claudegateway.atelier.nextprompt;

import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>La suite prédite</b> (F-144 / SF-144-02) : {@code POST /workspaces/{id}/next-prompt}, appelé par
 * l'écran à la fin d'un tour.
 *
 * <p>Déclenché par l'écran et non par la fin du tour côté serveur (D3) : un tour peut finir sans
 * écran ouvert, et il n'y a alors personne à qui proposer quoi que ce soit — ni rien à payer.</p>
 */
@RestController
@RequestMapping("/workspaces/{id}/next-prompt")
public class NextPromptController {

    private final NextPromptService service;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public NextPromptController(NextPromptService service, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.service = service;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    @PostMapping
    public NextPromptResponse predict(@PathVariable UUID id) {
        atelierAccess.requireTerminalAccess(id);
        return service.predict(currentUser.requireId(), id);
    }
}
