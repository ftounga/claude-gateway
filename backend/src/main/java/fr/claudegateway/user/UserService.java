package fr.claudegateway.user;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Logique métier autour des utilisateurs. Point d'accès unique pour lire et écrire un compte
 * (les couches supérieures ne dépendent jamais directement du {@link UserRepository}).
 */
@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** Vrai si un compte existe déjà pour cet email (email supposé déjà normalisé). */
    public boolean emailExists(String email) {
        return userRepository.existsByEmail(email);
    }

    /**
     * Crée et persiste un compte local (email / mot de passe). Le mot de passe est déjà haché
     * (BCrypt) par l'appelant : cette couche ne manipule jamais de mot de passe en clair.
     */
    @Transactional
    public User createLocalUser(String email, String passwordHash) {
        User user = User.builder()
                .email(email)
                .passwordHash(passwordHash)
                .emailVerified(false)
                .provider(AuthProvider.LOCAL)
                .role(UserRole.USER)
                .build();
        return userRepository.save(user);
    }

    /** Marque l'e-mail de l'utilisateur comme vérifié (idempotent). */
    @Transactional
    public User markEmailVerified(UUID userId) {
        User user = findByIdOrThrow(userId);
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    /** Remplace le hash de mot de passe d'un utilisateur (BCrypt déjà calculé par l'appelant). */
    @Transactional
    public User updatePassword(UUID userId, String passwordHash) {
        User user = findByIdOrThrow(userId);
        user.setPasswordHash(passwordHash);
        return userRepository.save(user);
    }

    /**
     * Fédère une identité Google vers un compte plateforme. Si un compte existe déjà pour cet
     * e-mail (identité pivot), il est réutilisé ; sinon un compte {@code GOOGLE} vérifié est créé.
     * L'e-mail est supposé déjà normalisé (minuscule).
     */
    @Transactional
    public User findOrCreateGoogleUser(String email) {
        return userRepository.findByEmail(email)
                .orElseGet(() -> userRepository.save(User.builder()
                        .email(email)
                        .emailVerified(true)
                        .provider(AuthProvider.GOOGLE)
                        .role(UserRole.USER)
                        .build()));
    }

    /**
     * Charge l'utilisateur courant / demandé par son identifiant.
     *
     * @throws UserNotFoundException si aucun utilisateur ne correspond
     */
    public User findByIdOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }

    /** Variante non levante, utilisée par le filtre JWT pour valider l'existence du compte. */
    public Optional<User> findById(UUID userId) {
        return userRepository.findById(userId);
    }

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }
}
