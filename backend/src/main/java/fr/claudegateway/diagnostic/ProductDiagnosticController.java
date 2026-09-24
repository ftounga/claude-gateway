package fr.claudegateway.diagnostic;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Le diagnostic du produit</b> (F-156 / SF-156-05) — <b>administrateur uniquement</b>, et
 * <b>à la demande</b>.
 *
 * <p>À la demande parce que le diagnostic regarde une accumulation : le lancer à chaque fermeture
 * de session reviendrait à ré-analyser les mêmes données pour la même conclusion — exactement le
 * gaspillage qu'il traque.</p>
 *
 * <p>La garde est la <b>définition unique</b> ({@link AdminService#assertAdmin()}), qui accepte
 * aussi le super-admin par e-mail.</p>
 */
@RestController
@RequestMapping("/admin/diagnostic")
public class ProductDiagnosticController {

    private final ProductDiagnosticService service;
    private final AdminService adminService;
    private final CurrentUser currentUser;

    public ProductDiagnosticController(ProductDiagnosticService service, AdminService adminService,
                                       CurrentUser currentUser) {
        this.service = service;
        this.adminService = adminService;
        this.currentUser = currentUser;
    }

    /**
     * Lance le diagnostic sur les {@code days} derniers jours.
     *
     * <p>Aucun jeton n'est consommé : le diagnostic lit des mesures, il n'appelle pas de
     * fournisseur.</p>
     */
    @PostMapping
    public DiagnosticReport run(@RequestParam(required = false) Integer days) {
        adminService.assertAdmin();
        return service.run(currentUser.requireId(), days);
    }
}
