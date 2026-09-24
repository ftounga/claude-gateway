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
    public DiagnosticReport run(@RequestParam(required = false) Integer days,
                                @RequestParam(required = false) java.util.UUID workspaceId) {
        adminService.assertAdmin();
        return service.run(currentUser.requireId(), days, workspaceId);
    }

    /**
     * Une <b>hypothèse</b> sur une capacité, tirée de la lecture de son code (F-157 / SF-157-05).
     *
     * <p><b>C'est la seule route du diagnostic qui consomme des jetons.</b> Une capacité par appel,
     * à la demande. Le résultat est une hypothèse, <b>jamais un verdict</b> — l'écran le dit.</p>
     *
     * @return {@code 200} avec l'hypothèse, ou {@code 204} quand il n'y avait rien à en tirer
     */
    @PostMapping("/hypothesis")
    public org.springframework.http.ResponseEntity<SourceHypothesis> explain(
            @RequestParam java.util.UUID workspaceId,
            @RequestParam String capabilityId) {
        adminService.assertAdmin();
        return service.explain(currentUser.requireId(), workspaceId, capabilityId)
                .map(org.springframework.http.ResponseEntity::ok)
                .orElseGet(() -> org.springframework.http.ResponseEntity
                        .status(org.springframework.http.HttpStatus.NO_CONTENT).build());
    }
}
