package fr.claudegateway.jarvis.mail;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Accès à {@link JarvisMailAccount} (F-242). Toute résolution par id passe par {@link #findByIdAndUserId} : une boîte
 * d'autrui est introuvable (404), jamais « interdite ».
 */
@Repository
public interface JarvisMailAccountRepository extends JpaRepository<JarvisMailAccount, UUID> {

    List<JarvisMailAccount> findByUserIdOrderByCreatedAtAsc(UUID userId);

    Optional<JarvisMailAccount> findByIdAndUserId(UUID id, UUID userId);

    Optional<JarvisMailAccount> findByUserIdAndAddress(UUID userId, String address);

    long countByUserId(UUID userId);

    @Modifying
    @Query("delete from JarvisMailAccount a where a.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
