package fr.claudegateway.atelier.fil;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Le fil des tâches</b> (F-191 / SF-191-01) : les tours en cours — et finis depuis moins de dix
 * minutes — de tous les terminaux du poste du terminal {@code id}.
 *
 * <p>Endpoint JSON court, interrogé périodiquement par la bande au-dessus de la saisie : il n'occupe ni
 * le pool des flux ni le registre des terminaux vivants (F-70). Identité issue de {@link CurrentUser} ;
 * terminal d'autrui ⇒ 404.</p>
 */
@RestController
@RequestMapping("/workspaces/{id}/fil")
public class PosteTurnsController {

    private final PosteTurnsService service;
    private final CurrentUser currentUser;
    private final AtelierAccessService atelierAccess;

    public PosteTurnsController(PosteTurnsService service, CurrentUser currentUser,
            AtelierAccessService atelierAccess) {
        this.service = service;
        this.currentUser = currentUser;
        this.atelierAccess = atelierAccess;
    }

    @GetMapping
    public PosteTurnsResponse turns(@PathVariable UUID id) {
        atelierAccess.requireTerminalAccess(id);
        return service.turnsOfPoste(currentUser.requireId(), id);
    }
}
