package fr.claudegateway.access;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.access.dto.AccessCodeAdminView;
import fr.claudegateway.access.dto.IssueAccessCodeRequest;
import fr.claudegateway.access.dto.IssuedAccessCodeResponse;
import jakarta.validation.Valid;

/**
 * API d'émission et de suivi des codes d'accès (F-62 / SF-62-01), <b>réservée à l'ADMIN</b>.
 *
 * <p>L'autorisation est appliquée dans {@link AccessCodeService} via
 * {@code AdminService.assertAdmin()} — la garde unique du produit (F-20). La dupliquer ici créerait
 * une seconde définition de « qui est admin », c'est-à-dire, un jour, deux définitions
 * divergentes.</p>
 */
@RestController
@RequestMapping("/admin/access-codes")
public class AccessCodeAdminController {

    private final AccessCodeService accessCodeService;

    public AccessCodeAdminController(AccessCodeService accessCodeService) {
        this.accessCodeService = accessCodeService;
    }

    /** Tous les codes émis, avec leur trace de consommation. Jamais de code en clair. */
    @GetMapping
    public List<AccessCodeAdminView> list() {
        return accessCodeService.list().stream().map(AccessCodeAdminView::from).toList();
    }

    /**
     * Émet un code. La réponse porte le code en clair — <b>la seule et unique fois</b> où il est
     * lisible.
     */
    @PostMapping
    public IssuedAccessCodeResponse issue(@Valid @RequestBody IssueAccessCodeRequest request) {
        return IssuedAccessCodeResponse.from(
                accessCodeService.issue(request.label(), request.assignedEmail()));
    }
}
