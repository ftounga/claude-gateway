package fr.claudegateway.admin;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.admin.dto.AdminUsageResponse;
import fr.claudegateway.admin.dto.AdminUserView;

/**
 * API d'administration (F-20). L'autorisation (rôle ADMIN / super-admin) est appliquée dans
 * {@link AdminService} ; la chaîne de sécurité exige déjà un JWT valide.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AdminService adminService;
    private final AdminUsageService adminUsageService;

    public AdminController(AdminService adminService, AdminUsageService adminUsageService) {
        this.adminService = adminService;
        this.adminUsageService = adminUsageService;
    }

    /** Liste des utilisateurs avec abonnement et consommation (ADMIN uniquement). */
    @GetMapping("/users")
    public List<AdminUserView> users() {
        return adminService.listUsers();
    }

    /**
     * Consommation <b>par utilisateur</b> sur une période (F-61 / SF-61-03, ADMIN uniquement) :
     * tokens d'entrée et de sortie distingués, coût estimé, part du total, plan, évolution
     * mensuelle.
     *
     * <p>Des volumes et des coûts, jamais des contenus : ni message, ni commande, ni chemin, ni nom
     * de projet ou de poste ne transite par cette route.</p>
     *
     * @param from premier mois observé (facultatif ; défaut : onze mois avant {@code to})
     * @param to   dernier mois observé, inclus (facultatif ; défaut : mois courant)
     */
    @GetMapping("/usage")
    public AdminUsageResponse usage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {
        return AdminUsageResponse.from(adminUsageService.byUser(from, to));
    }
}
