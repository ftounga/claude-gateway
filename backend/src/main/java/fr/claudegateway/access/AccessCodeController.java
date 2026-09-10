package fr.claudegateway.access;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.access.dto.AccessGrantResponse;
import fr.claudegateway.access.dto.RedeemAccessCodeRequest;
import fr.claudegateway.auth.AuthenticatedUser;
import fr.claudegateway.auth.CurrentUser;
import jakarta.validation.Valid;

/**
 * API des codes d'accès côté <b>utilisateur</b> (F-62 / SF-62-01). L'identité vient exclusivement du
 * {@link CurrentUser} (JWT) : l'isolation {@code user_id} est appliquée dans le service, jamais
 * depuis un paramètre client. Controller fin — aucune logique métier ici.
 *
 * <p>La saisie vit ici, et non sur l'authentification : la décision d'ergonomie de F-62 est que le
 * code se présente <b>là où il produit son effet</b> — l'écran du plan, en alternative au paiement.
 * Le demander à la connexion imposerait un champ à tous ceux qui n'ont pas de code.</p>
 */
@RestController
@RequestMapping("/access-code")
public class AccessCodeController {

    private final AccessCodeService accessCodeService;
    private final CurrentUser currentUser;

    public AccessCodeController(AccessCodeService accessCodeService, CurrentUser currentUser) {
        this.accessCodeService = accessCodeService;
        this.currentUser = currentUser;
    }

    /**
     * Accès offert en cours de l'utilisateur courant. Renvoie toujours 200 : l'absence de droit est
     * un état normal, pas une ressource introuvable.
     */
    @GetMapping("/grant")
    public AccessGrantResponse grant() {
        return AccessGrantResponse.from(accessCodeService.currentGrant(currentUser.requireId()));
    }

    /**
     * Consomme un code au profit de l'utilisateur courant.
     *
     * <p>404 code inconnu · 409 code déjà utilisé, périmé, ou droit déjà en cours (pas de cumul) ·
     * 403 code nominatif visant un autre compte.</p>
     */
    @PostMapping("/redeem")
    public AccessGrantResponse redeem(@Valid @RequestBody RedeemAccessCodeRequest request) {
        AuthenticatedUser user = currentUser.principal()
                .orElseThrow(() -> new IllegalStateException("Aucun utilisateur authentifié"));
        return AccessGrantResponse.from(
                accessCodeService.redeem(user.id(), user.email(), request.code()));
    }
}
