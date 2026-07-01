package fr.claudegateway.auth;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.dto.MessageResponse;
import fr.claudegateway.auth.dto.UpdateProfileRequest;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserService;
import jakarta.validation.Valid;

/**
 * Endpoints du compte courant : consultation/édition du profil et déconnexion.
 *
 * <p>Illustre le patron d'isolation {@code user_id} : l'identité provient exclusivement du
 * {@link CurrentUser} (donc du JWT). Un utilisateur ne lit ni ne modifie jamais que son propre
 * compte.</p>
 */
@RestController
public class MeController {

    private final CurrentUser currentUser;
    private final UserService userService;
    private final ProfileService profileService;

    public MeController(CurrentUser currentUser, UserService userService, ProfileService profileService) {
        this.currentUser = currentUser;
        this.userService = userService;
        this.profileService = profileService;
    }

    @GetMapping("/me")
    public MeResponse me() {
        UUID userId = currentUser.requireId();
        User user = userService.findByIdOrThrow(userId);
        return MeResponse.from(user);
    }

    @PutMapping("/me")
    public MeResponse updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return profileService.updateEmail(currentUser.requireId(), request.email());
    }

    @PostMapping("/me/logout")
    public MessageResponse logout() {
        // JWT stateless : aucune révocation serveur d'un token individuel. Le client oublie le token.
        return new MessageResponse("Déconnecté.");
    }

    @PostMapping("/me/logout-all")
    public MessageResponse logoutAll() {
        profileService.logoutAllSessions(currentUser.requireId());
        return new MessageResponse("Toutes les sessions ont été déconnectées.");
    }
}
