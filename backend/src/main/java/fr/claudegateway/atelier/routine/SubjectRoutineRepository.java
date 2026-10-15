package fr.claudegateway.atelier.routine;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès aux routines — toujours sous {@code user_id} + {@code workspace_id} (F-183 / SF-183-01). */
public interface SubjectRoutineRepository extends JpaRepository<SubjectRoutine, UUID> {

    List<SubjectRoutine> findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(UUID userId, UUID workspaceId);

    Optional<SubjectRoutine> findByIdAndUserIdAndWorkspaceId(UUID id, UUID userId, UUID workspaceId);

    long countByUserIdAndWorkspaceId(UUID userId, UUID workspaceId);

    /** Les routines programmées (actives, validées), par page — ce que le planificateur examine. */
    List<SubjectRoutine> findByActiveTrueAndProposedFalseOrderByIdAsc(Pageable page);

    /**
     * <b>Le verrou du créneau</b> (SF-183-02) : avance {@code last_slot_at} au créneau dû si — et
     * seulement si — personne ne l'a fait. Deux pods au même créneau : une seule ligne modifiée.
     *
     * @return 1 si ce planificateur a gagné le créneau, 0 sinon
     */
    @Modifying
    @Query("update SubjectRoutine r set r.lastSlotAt = :slot, r.missedSlotAt = null where r.id = :id "
            + "and r.active = true and r.proposed = false and (r.lastSlotAt is null or r.lastSlotAt < :slot)")
    int claimSlot(@Param("id") java.util.UUID id, @Param("slot") OffsetDateTime slot);

    /**
     * Note le créneau manqué (poste hors ligne) — une seule fois par créneau.
     *
     * @return 1 si c'est la première fois que ce créneau est noté manqué
     */
    @Modifying
    @Query("update SubjectRoutine r set r.missedSlotAt = :slot where r.id = :id "
            + "and (r.missedSlotAt is null or r.missedSlotAt <> :slot) "
            + "and (r.lastSlotAt is null or r.lastSlotAt < :slot)")
    int markMissed(@Param("id") java.util.UUID id, @Param("slot") OffsetDateTime slot);

    /** Purge à la suppression du compte. */
    @Modifying
    @Query("delete from SubjectRoutine r where r.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
