package fr.claudegateway.git;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Accès à {@link UserGitCredential}. Toutes les méthodes filtrent par {@code user_id} (isolation
 * multi-tenant) : un jeton n'est jamais résolu par son id seul, qui n'est jamais exposé au client.
 */
@Repository
public interface UserGitCredentialRepository extends JpaRepository<UserGitCredential, UUID> {

    Optional<UserGitCredential> findByUserId(UUID userId);

    void deleteByUserId(UUID userId);
}
