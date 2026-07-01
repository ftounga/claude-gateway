package fr.claudegateway.auth;

import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.claudegateway.user.User;
import fr.claudegateway.user.UserService;

/**
 * Orchestration des opérations de profil : mise à jour de l'e-mail (avec re-vérification) et
 * déconnexion « toutes sessions ». Les règles métier vivent ici, pas dans le controller.
 */
@Service
public class ProfileService {

    private final UserService userService;
    private final EmailVerificationService emailVerificationService;

    public ProfileService(UserService userService, EmailVerificationService emailVerificationService) {
        this.userService = userService;
        this.emailVerificationService = emailVerificationService;
    }

    /**
     * Met à jour l'e-mail du compte courant. Si l'e-mail change réellement, vérifie l'unicité,
     * repasse le compte en « non vérifié » et déclenche un nouvel e-mail de vérification.
     *
     * @throws EmailAlreadyUsedException si l'e-mail cible appartient déjà à un autre compte
     */
    public MeResponse updateEmail(UUID userId, String rawEmail) {
        String email = normalizeEmail(rawEmail);
        User current = userService.findByIdOrThrow(userId);
        if (email.equals(current.getEmail())) {
            return MeResponse.from(current);
        }
        if (userService.emailExists(email)) {
            throw new EmailAlreadyUsedException();
        }
        User updated = userService.updateEmail(userId, email);
        emailVerificationService.createAndSend(updated);
        return MeResponse.from(updated);
    }

    /** Déconnecte toutes les sessions : incrémente le {@code token_version} du compte courant. */
    public void logoutAllSessions(UUID userId) {
        userService.incrementTokenVersion(userId);
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
