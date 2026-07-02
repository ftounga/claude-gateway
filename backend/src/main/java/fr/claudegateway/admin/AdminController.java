package fr.claudegateway.admin;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.admin.dto.AdminUserView;

/**
 * API d'administration (F-20). L'autorisation (rôle ADMIN / super-admin) est appliquée dans
 * {@link AdminService} ; la chaîne de sécurité exige déjà un JWT valide.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    /** Liste des utilisateurs avec abonnement et consommation (ADMIN uniquement). */
    @GetMapping("/users")
    public List<AdminUserView> users() {
        return adminService.listUsers();
    }
}
