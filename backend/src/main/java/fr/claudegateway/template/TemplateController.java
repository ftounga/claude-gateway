package fr.claudegateway.template;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.template.dto.TemplateRequest;
import fr.claudegateway.template.dto.TemplateResponse;
import jakarta.validation.Valid;

/**
 * Gestion des modèles de prompts (F-13) de l'utilisateur courant : liste, détail, création, mise à
 * jour, suppression. Toute opération est bornée à l'utilisateur du JWT ({@link CurrentUser}) —
 * isolation {@code user_id}. Le controller ne porte aucune logique métier (déléguée au service).
 */
@RestController
@RequestMapping("/templates")
public class TemplateController {

    private final TemplateService templateService;
    private final CurrentUser currentUser;

    public TemplateController(TemplateService templateService, CurrentUser currentUser) {
        this.templateService = templateService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<TemplateResponse> list() {
        UUID userId = currentUser.requireId();
        return templateService.listForUser(userId).stream()
                .map(TemplateResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public TemplateResponse detail(@PathVariable UUID id) {
        UUID userId = currentUser.requireId();
        return TemplateResponse.from(templateService.getOwned(id, userId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse create(@Valid @RequestBody TemplateRequest request) {
        UUID userId = currentUser.requireId();
        return TemplateResponse.from(
                templateService.create(userId, request.name(), request.category(), request.content()));
    }

    @PutMapping("/{id}")
    public TemplateResponse update(@PathVariable UUID id, @Valid @RequestBody TemplateRequest request) {
        UUID userId = currentUser.requireId();
        return TemplateResponse.from(
                templateService.update(id, userId, request.name(), request.category(), request.content()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        UUID userId = currentUser.requireId();
        templateService.delete(id, userId);
        return ResponseEntity.noContent().build();
    }
}
