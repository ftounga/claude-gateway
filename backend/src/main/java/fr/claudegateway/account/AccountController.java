package fr.claudegateway.account;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.account.dto.AccountExport;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.auth.dto.MessageResponse;

/**
 * Endpoints RGPD du compte courant : export des données personnelles et suppression définitive.
 *
 * <p>L'identité provient exclusivement du {@link CurrentUser} (donc du JWT). Un utilisateur ne peut
 * exporter ni supprimer que ses propres données — jamais celles d'un autre compte.</p>
 */
@RestController
public class AccountController {

    private final CurrentUser currentUser;
    private final AccountService accountService;

    public AccountController(CurrentUser currentUser, AccountService accountService) {
        this.currentUser = currentUser;
        this.accountService = accountService;
    }

    /** Export RGPD (portabilité) de l'intégralité des données de l'utilisateur courant. */
    @GetMapping("/account/export")
    public AccountExport export() {
        return accountService.export(currentUser.requireId());
    }

    /** Suppression définitive du compte courant et de toutes ses données (droit à l'effacement). */
    @DeleteMapping("/account")
    public MessageResponse delete() {
        accountService.deleteAccount(currentUser.requireId());
        return new MessageResponse("Votre compte et toutes vos données ont été supprimés.");
    }
}
