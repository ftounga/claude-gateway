package fr.claudegateway.atelier.agents;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Les agents nommés d'un terminal</b> (F-182 / SF-182-01) : pour l'écran et l'autocomplétion de
 * {@code /agent}. Lecture seule, aucun tour modèle, jamais la consigne. Identité par {@link CurrentUser} ;
 * {@code requireOwned} dans le service (404 sur un terminal d'autrui).
 */
@RestController
@RequestMapping("/workspaces/{workspaceId}/agents")
public class NamedAgentController {

    private final NamedAgentCatalogService service;
    private final CurrentUser currentUser;

    public NamedAgentController(NamedAgentCatalogService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<NamedAgentEntry> list(@PathVariable UUID workspaceId) {
        return service.catalogOf(currentUser.requireId(), workspaceId);
    }
}
