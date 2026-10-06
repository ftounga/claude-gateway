package fr.claudegateway.atelier.skills;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Les skills d'un terminal</b>, pour l'autocomplétion du {@code /} (F-177 / SF-177-03). Lecture seule,
 * aucun tour modèle. Identité par {@link CurrentUser} ; {@code requireOwned} dans le service (404 sur
 * un terminal d'autrui).
 */
@RestController
@RequestMapping("/workspaces/{workspaceId}/skills")
public class SkillCatalogController {

    private final SkillCatalogService service;
    private final CurrentUser currentUser;

    public SkillCatalogController(SkillCatalogService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<SkillEntry> list(@PathVariable UUID workspaceId) {
        return service.catalogOf(currentUser.requireId(), workspaceId);
    }
}
