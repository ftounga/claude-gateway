package fr.claudegateway.jarvis.state;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Les faits d'état (F-247 / SF-247-02). <b>Toute lecture porte {@code user_id} et {@code host_id}</b> et passe par un
 * index, avec une limite (porte Z10) : jamais de parcours complet.
 */
public interface JarvisStateFactRepository extends JpaRepository<JarvisStateFact, UUID> {

    Optional<JarvisStateFact> findByUserIdAndHostIdAndObjectKeyAndAttribute(UUID userId, UUID hostId,
            String objectKey, String attribute);

    /** Les plus récents du client (index {@code idx_jarvis_state_facts_recent}). */
    List<JarvisStateFact> findByUserIdAndHostIdOrderByObservedAtDesc(UUID userId, UUID hostId, Pageable page);

    /** Les plus récents d'une famille (index {@code idx_jarvis_state_facts_kind}). */
    List<JarvisStateFact> findByUserIdAndHostIdAndObjectKindOrderByObservedAtDesc(UUID userId, UUID hostId,
            String objectKind, Pageable page);

    /** Par préfixe de clé (index {@code idx_jarvis_state_facts_prefix} sous PostgreSQL). */
    @Query("SELECT f FROM JarvisStateFact f WHERE f.userId = :userId AND f.hostId = :hostId "
            + "AND f.objectKey LIKE :prefix ESCAPE '\\' ORDER BY f.objectKey, f.attribute")
    List<JarvisStateFact> findByKeyPrefix(@Param("userId") UUID userId, @Param("hostId") UUID hostId,
            @Param("prefix") String prefixPattern, Pageable page);

    /** Les faits d'une clé exacte (tous attributs ; contrainte unique). */
    List<JarvisStateFact> findByUserIdAndHostIdAndObjectKeyOrderByAttribute(UUID userId, UUID hostId,
            String objectKey);

    Optional<JarvisStateFact> findByIdAndUserId(UUID id, UUID userId);

    long countByUserIdAndHostId(UUID userId, UUID hostId);

    /** Le seuil d'âge du N-ième plus récent : au-delà du plafond, on purge plus ancien que lui. */
    @Query("SELECT f.observedAt FROM JarvisStateFact f WHERE f.userId = :userId AND f.hostId = :hostId "
            + "ORDER BY f.observedAt DESC")
    List<OffsetDateTime> observedDesc(@Param("userId") UUID userId, @Param("hostId") UUID hostId, Pageable page);

    @Modifying
    @Query("DELETE FROM JarvisStateFact f WHERE f.userId = :userId AND f.hostId = :hostId "
            + "AND f.observedAt < :before")
    int deleteOlder(@Param("userId") UUID userId, @Param("hostId") UUID hostId,
            @Param("before") OffsetDateTime before);

    @Modifying
    @Query("DELETE FROM JarvisStateFact f WHERE f.userId = :userId AND f.hostId = :hostId")
    int deleteAllOf(@Param("userId") UUID userId, @Param("hostId") UUID hostId);

    /** Purge nocturne, rétention par défaut, hors clients à rétention propre (purgés à part). */
    @Modifying
    @Query("DELETE FROM JarvisStateFact f WHERE f.observedAt < :before AND NOT EXISTS (SELECT 1 FROM "
            + "JarvisStateSettings s WHERE s.userId = f.userId AND s.hostId = f.hostId "
            + "AND s.factRetentionDays <> :defaultDays)")
    int deleteDefaultOlder(@Param("before") OffsetDateTime before, @Param("defaultDays") int defaultDays);
}
