package fr.claudegateway.auth;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.user.User;
import fr.claudegateway.user.UserService;

/**
 * Endpoint de validation du socle d'authentification : {@code GET /api/me}.
 *
 * <p>Illustre le patron d'isolation {@code user_id} que toutes les features V1 réutilisent :
 * l'identité provient exclusivement du {@link CurrentUser} (donc du JWT), puis la donnée est
 * relue en filtrant sur cet identifiant. Un utilisateur ne peut jamais lire que son propre compte.</p>
 */
@RestController
public class MeController {

    private final CurrentUser currentUser;
    private final UserService userService;

    public MeController(CurrentUser currentUser, UserService userService) {
        this.currentUser = currentUser;
        this.userService = userService;
    }

    @GetMapping("/me")
    public MeResponse me() {
        UUID userId = currentUser.requireId();
        User user = userService.findByIdOrThrow(userId);
        return MeResponse.from(user);
    }
}
