package fr.claudegateway.jarvis.budget;

import java.math.BigDecimal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Le plafond de Jarvis</b> (F-244 / SF-244-03), pour le seul compte courant ({@link CurrentUser}) :
 * {@code GET /api/jarvis/budget} (l'état), {@code PUT /api/jarvis/budget} (le plafond et l'option BYOK),
 * {@code POST /api/jarvis/budget/confirm} (le « oui » à une délégation au-delà du plafond).
 */
@RestController
public class JarvisBudgetController {

    private final JarvisBudgetService service;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public JarvisBudgetController(JarvisBudgetService service, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.service = service;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    /** Le corps du réglage : un champ absent garde sa valeur. */
    public record BudgetRequest(BigDecimal monthlyCapEur, Boolean applyOnByok) {
    }

    @GetMapping("/jarvis/budget")
    public JarvisBudgetService.Status status() {
        atelierAccess.requireAccess();
        return service.status(currentUser.requireId());
    }

    @PutMapping("/jarvis/budget")
    public ResponseEntity<?> update(@RequestBody BudgetRequest request) {
        atelierAccess.requireAccess();
        try {
            return ResponseEntity.ok(service.update(currentUser.requireId(),
                    request == null ? null : request.monthlyCapEur(), request == null ? null : request.applyOnByok()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new fr.claudegateway.shared.error.ErrorResponse("invalid_budget", e.getMessage()));
        }
    }

    @PostMapping("/jarvis/budget/confirm")
    public ResponseEntity<Void> confirm() {
        atelierAccess.requireAccess();
        service.confirm(currentUser.requireId());
        return ResponseEntity.noContent().build();
    }
}
