package fr.claudegateway.jarvis.companion;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.auth.CompanionSessionVerifier;

/**
 * F-232 / SF-232-05 — ce que le filtre d'authentification vérifie à <b>chaque requête</b> d'une session
 * {@code jarvis-compagnon} : la route est dans {@link JarvisCompanionScope} ; le compagnon appartient au compte du
 * jeton ({@code user_id}), est appairé et n'est pas révoqué (révoquer l'appareil coupe sa session à la requête
 * suivante).
 */
@Component
public class JarvisCompanionSessionGuard implements CompanionSessionVerifier {

    private final JarvisCompanionRepository repository;

    public JarvisCompanionSessionGuard(JarvisCompanionRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean allows(String method, String path) {
        return JarvisCompanionScope.allows(method, path);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(UUID userId, UUID companionId) {
        if (userId == null || companionId == null) {
            return false;
        }
        return repository.findByIdAndUserId(companionId, userId).filter(JarvisCompanion::isActive).isPresent();
    }
}
