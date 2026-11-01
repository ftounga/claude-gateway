package fr.claudegateway.jarvis.companion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Les téléphones appairés (F-232 / SF-232-03). Toute lecture porte le {@code user_id}, sauf la résolution du jeton. */
public interface JarvisCompanionDeviceRepository extends JpaRepository<JarvisCompanionDevice, UUID> {

    /** La résolution du jeton : seule lecture sans {@code user_id} — c'est elle qui le fournit. */
    Optional<JarvisCompanionDevice> findByTokenHash(String tokenHash);

    List<JarvisCompanionDevice> findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(UUID userId);

    Optional<JarvisCompanionDevice> findByIdAndUserId(UUID id, UUID userId);

    long countByUserIdAndRevokedAtIsNull(UUID userId);

    boolean existsByUserIdAndRevokedAtIsNull(UUID userId);
}
