package fr.claudegateway.atelier.veille;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès aux veilles — toujours sous {@code user_id} (+ {@code workspace_id}) (F-230). */
public interface JarvisWatchRepository extends JpaRepository<JarvisWatch, UUID> {

    List<JarvisWatch> findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(UUID userId, UUID workspaceId);

    List<JarvisWatch> findByUserIdOrderByCreatedAtAsc(UUID userId);

    Optional<JarvisWatch> findByIdAndUserIdAndWorkspaceId(UUID id, UUID userId, UUID workspaceId);

    Optional<JarvisWatch> findByIdAndUserId(UUID id, UUID userId);

    Optional<JarvisWatch> findFirstByUserIdAndWorkspaceIdAndNameIgnoreCase(UUID userId, UUID workspaceId, String name);

    long countByUserIdAndWorkspaceId(UUID userId, UUID workspaceId);

    /** Les veilles programmées (actives, validées), par page — ce que le planificateur examine (SF-230-02). */
    List<JarvisWatch> findByActiveTrueAndProposedFalseOrderByIdAsc(Pageable page);

    /**
     * <b>Le verrou du créneau</b> (SF-230-02) : avance {@code last_slot_at} au créneau dû si — et seulement
     * si — personne ne l'a fait. Deux pods au même créneau : une seule ligne modifiée.
     */
    @Modifying
    @Query("update JarvisWatch w set w.lastSlotAt = :slot, w.missedSlotAt = null where w.id = :id "
            + "and w.active = true and w.proposed = false and (w.lastSlotAt is null or w.lastSlotAt < :slot)")
    int claimSlot(@Param("id") UUID id, @Param("slot") OffsetDateTime slot);

    /** Note le créneau manqué (poste hors ligne) — une seule fois par créneau (SF-230-02). */
    @Modifying
    @Query("update JarvisWatch w set w.missedSlotAt = :slot where w.id = :id "
            + "and (w.missedSlotAt is null or w.missedSlotAt <> :slot) "
            + "and (w.lastSlotAt is null or w.lastSlotAt < :slot)")
    int markMissed(@Param("id") UUID id, @Param("slot") OffsetDateTime slot);

    /** F-247 / SF-247-07 — rend un créneau pris (relevé qui a cédé la place) : il sera repris au passage suivant. */
    @Modifying
    @Query("update JarvisWatch w set w.lastSlotAt = null where w.id = :id and w.lastSlotAt = :slot")
    int releaseSlot(@Param("id") UUID id, @Param("slot") OffsetDateTime slot);

    /**
     * L'issue d'un passage (SF-230-02), par un {@code UPDATE} ciblé : une pause posée par le PO pendant le passage
     * n'est jamais écrasée.
     */
    @Modifying
    @Query("update JarvisWatch w set w.lastRunAt = :at, w.lastRunStatus = :status, "
            + "w.consecutiveFailures = :failures, w.broken = :broken where w.id = :id and w.userId = :userId")
    int recordOutcome(@Param("id") UUID id, @Param("userId") UUID userId, @Param("at") OffsetDateTime at,
            @Param("status") String status, @Param("failures") int failures, @Param("broken") boolean broken);

    /** Purge à la suppression du compte. */
    @Modifying
    @Query("delete from JarvisWatch w where w.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
