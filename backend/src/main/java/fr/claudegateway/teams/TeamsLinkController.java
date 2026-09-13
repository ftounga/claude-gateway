package fr.claudegateway.teams;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;

/**
 * L'état de la liaison Teams d'un projet (F-87 / SF-87-03).
 *
 * <p>Un seul verbe, {@code GET}, et aucune action : l'indicateur de la barre du terminal
 * <b>dit un état</b>. C'est la règle du cadrage — « une fenêtre sur un état, pas un panneau de
 * contrôle » — et elle se lit jusque dans la surface de l'API.</p>
 *
 * <p>L'identité vient exclusivement du {@link CurrentUser} (JWT) : l'isolation {@code user_id} est
 * appliquée dans le service, jamais depuis un paramètre client.</p>
 */
@RestController
@RequestMapping("/workspaces")
public class TeamsLinkController {

    private final TeamsLinkService teamsLinkService;
    private final CurrentUser currentUser;
    private final AtelierAccessService atelierAccess;

    public TeamsLinkController(TeamsLinkService teamsLinkService, CurrentUser currentUser,
            AtelierAccessService atelierAccess) {
        this.teamsLinkService = teamsLinkService;
        this.currentUser = currentUser;
        this.atelierAccess = atelierAccess;
    }

    /**
     * Où en est la liaison Teams de ce projet : relié, navigateur non détecté, ou Teams a changé.
     *
     * <p>Répond <b>200 dans tous les cas</b> où le projet existe et appartient à l'utilisateur :
     * une machine éteinte ou un navigateur non lancé sont des <b>états</b>, pas des pannes de
     * l'application.</p>
     */
    @GetMapping("/{id}/teams/link")
    public TeamsLinkResponse link(@PathVariable UUID id) {
        atelierAccess.requireTerminalAccess(id);
        return TeamsLinkResponse.from(teamsLinkService.status(currentUser.requireId(), id));
    }
}
