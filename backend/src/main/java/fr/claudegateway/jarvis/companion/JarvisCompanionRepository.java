package fr.claudegateway.jarvis.companion;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Les compagnons Android appairés (F-232 / SF-232-03). Toute lecture côté compte est filtrée par {@code user_id}. */
public interface JarvisCompanionRepository extends JpaRepository<JarvisCompanion, UUID> {

    Optional<JarvisCompanion> findByPairingCodeHash(String pairingCodeHash);

    Optional<JarvisCompanion> findByTokenHash(String tokenHash);

    Optional<JarvisCompanion> findByIdAndUserId(UUID id, UUID userId);

    List<JarvisCompanion> findByUserIdAndPairedAtIsNotNullAndRevokedAtIsNullOrderByPairedAtDesc(UUID userId);

    /** Les codes d'appairage jamais échangés du compte : un nouveau code remplace les précédents. */
    @Modifying
    @Query("delete from JarvisCompanion c where c.userId = :userId and c.pairedAt is null")
    int deletePendingOf(@Param("userId") UUID userId);

    /** Les codes jamais échangés et déjà expirés, de tous les comptes (ménage best-effort). */
    @Modifying
    @Query("delete from JarvisCompanion c where c.pairedAt is null and c.pairingExpiresAt < :now")
    int deleteExpiredPending(@Param("now") OffsetDateTime now);
}
