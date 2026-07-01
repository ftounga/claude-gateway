package fr.claudegateway.byok;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Accès à {@link UserApiKey}. Toutes les méthodes filtrent par {@code user_id} (isolation multi-tenant) :
 * une clé n'est jamais résolue par son id seul.
 */
@Repository
public interface UserApiKeyRepository extends JpaRepository<UserApiKey, UUID> {

    Optional<UserApiKey> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    void deleteByUserId(UUID userId);
}
