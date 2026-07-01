package fr.claudegateway.user;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Logique métier autour des utilisateurs. Point d'accès unique pour lire un compte
 * (les couches supérieures ne dépendent jamais directement du {@link UserRepository}).
 */
@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
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
