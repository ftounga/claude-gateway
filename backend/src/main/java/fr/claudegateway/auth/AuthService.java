package fr.claudegateway.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import fr.claudegateway.auth.dto.AuthResponse;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserService;

/**
 * Orchestration de l'inscription et de la connexion par email / mot de passe.
 *
 * <p>Règles métier portées ici (jamais dans le controller) : normalisation de l'email,
 * unicité, hachage BCrypt, vérification du mot de passe, et émission du JWT plateforme via
 * le {@link JwtService} du socle. Aucun mot de passe en clair n'est journalisé.</p>
 */
@Service
public class AuthService {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmailVerificationService emailVerificationService;

    public AuthService(
            UserService userService,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            EmailVerificationService emailVerificationService) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.emailVerificationService = emailVerificationService;
    }

    /**
     * Crée un compte local. L'email est normalisé (trim + minuscule) ; le mot de passe est haché
     * en BCrypt avant persistance.
     *
     * @throws EmailAlreadyUsedException si l'email est déjà rattaché à un compte
     */
    public MeResponse register(String rawEmail, String rawPassword) {
        String email = normalizeEmail(rawEmail);
        if (userService.emailExists(email)) {
            throw new EmailAlreadyUsedException();
        }
        String passwordHash = passwordEncoder.encode(rawPassword);
        User user = userService.createLocalUser(email, passwordHash);
        emailVerificationService.createAndSend(user);
        return MeResponse.from(user);
    }

    /**
     * Vérifie les identifiants et émet un JWT. Le message d'erreur est identique quel que soit
     * le motif d'échec (email inconnu, mauvais mot de passe, compte OAuth-only) pour éviter
     * l'énumération de comptes.
     *
     * @throws InvalidCredentialsException si les identifiants ne sont pas valides
     */
    public AuthResponse login(String rawEmail, String rawPassword) {
        User user = userService.findByEmail(normalizeEmail(rawEmail))
                .filter(candidate -> StringUtils.hasText(candidate.getPasswordHash()))
                .filter(candidate -> passwordEncoder.matches(rawPassword, candidate.getPasswordHash()))
                .orElseThrow(InvalidCredentialsException::new);

        String token = jwtService.generateToken(user);
        return AuthResponse.bearer(token, MeResponse.from(user));
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
