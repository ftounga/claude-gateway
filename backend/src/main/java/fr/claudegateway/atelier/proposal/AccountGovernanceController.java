package fr.claudegateway.atelier.proposal;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Les règles de tous mes postes</b> (F-266 / SF-266-01) : lister, créer ou remplacer, retirer, et
 * recopier une règle du {@code GOUVERNANCE.md} d'un poste. Même garde que l'écran Gouvernance (accès
 * Forge) ; tout est sous l'utilisateur courant (une règle d'autrui est introuvable).
 */
@RestController
public class AccountGovernanceController {

    /** Corps de création : le titre (section {@code ## titre}) et le texte de la règle. */
    public record RuleRequest(String title, String content) {
    }

    /** Corps de recopie depuis un poste : le titre de la section à recopier. */
    public record PromoteRequest(String title) {
    }

    private final AccountGovernanceService service;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public AccountGovernanceController(AccountGovernanceService service, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.service = service;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    @GetMapping("/governance/account-rules")
    public List<AccountGovernanceRuleView> list() {
        atelierAccess.requireAccess();
        return service.list(currentUser.requireId());
    }

    @PostMapping("/governance/account-rules")
    public AccountGovernanceRuleView upsert(@RequestBody RuleRequest request) {
        atelierAccess.requireAccess();
        return service.upsert(currentUser.requireId(), request == null ? null : request.title(),
                request == null ? null : request.content(), null, null);
    }

    @DeleteMapping("/governance/account-rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID ruleId) {
        atelierAccess.requireAccess();
        service.delete(currentUser.requireId(), ruleId);
    }

    @PostMapping("/governance/hosts/{hostRef}/account-rules")
    public AccountGovernanceRuleView promoteFromHost(@PathVariable String hostRef,
            @RequestBody PromoteRequest request) {
        atelierAccess.requireAccess();
        return service.promoteFromHost(currentUser.requireId(), hostRef, request == null ? null : request.title());
    }
}
