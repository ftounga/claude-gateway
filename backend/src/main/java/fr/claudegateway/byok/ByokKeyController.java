package fr.claudegateway.byok;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.byok.dto.ApiKeyStatusResponse;
import fr.claudegateway.byok.dto.SaveApiKeyRequest;
import fr.claudegateway.byok.dto.SetModeRequest;
import jakarta.validation.Valid;

/**
 * API de gestion de la clé BYOK de l'utilisateur courant (F-03) sous {@code /api/user/api-key}.
 * Le controller ne porte aucune logique métier : il résout le {@code user_id} du contexte de
 * sécurité (isolation multi-tenant) et délègue à {@link ByokKeyService}. La clé n'est jamais renvoyée
 * en clair.
 */
@RestController
@RequestMapping("/user/api-key")
public class ByokKeyController {

    private final ByokKeyService byokKeyService;
    private final CurrentUser currentUser;

    public ByokKeyController(ByokKeyService byokKeyService, CurrentUser currentUser) {
        this.byokKeyService = byokKeyService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiKeyStatusResponse status() {
        return byokKeyService.getStatus(currentUser.requireId());
    }

    @PostMapping
    public ApiKeyStatusResponse save(@Valid @RequestBody SaveApiKeyRequest request) {
        return byokKeyService.saveKey(currentUser.requireId(), request.apiKey());
    }

    @PutMapping("/mode")
    public ApiKeyStatusResponse setMode(@Valid @RequestBody SetModeRequest request) {
        return byokKeyService.setMode(currentUser.requireId(), request.mode());
    }

    @DeleteMapping
    public ResponseEntity<Void> delete() {
        byokKeyService.deleteKey(currentUser.requireId());
        return ResponseEntity.noContent().build();
    }
}
