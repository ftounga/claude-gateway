package fr.claudegateway.terminals;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistance des places de terminal vivant (F-70 / SF-70-01).
 *
 * <p><b>Toute</b> lecture filtre sur {@code user_id} : il n'existe volontairement aucune méthode qui
 * lise une place par son seul identifiant. Un registre dont on peut extraire une ligne sans dire
 * pour qui finit toujours par être appelé sans le dire.</p>
 */
@Repository
public interface LiveTerminalRepository extends JpaRepository<LiveTerminal, UUID> {

    /**
     * Places <b>vivantes</b> d'un utilisateur, les plus anciennes d'abord. L'ordre n'est pas
     * cosmétique : c'est lui qui décide, en cas de course, qui garde sa place — le premier arrivé.
     */
    List<LiveTerminal> findByUserIdAndLastSeenAtAfterOrderByOpenedAtAsc(UUID userId,
            OffsetDateTime cutoff);

    /**
     * La place d'un onglet donné, vivante ou non.
     *
     * <p><b>Jamais dans un chemin de décision</b> depuis F-78 : c'est cette lecture, suivie d'une
     * écriture, qui laissait deux battements du même onglet insérer tous les deux. Renouveler se
     * fait maintenant par {@link #renew} — une écriture qui dit elle-même si elle a trouvé
     * quelqu'un. Cette méthode ne sert plus qu'à <b>constater</b> (tests, diagnostic).</p>
     */
    Optional<LiveTerminal> findByUserIdAndSessionId(UUID userId, String sessionId);

    /**
     * <b>Renouvelle</b> la place de cet onglet, sans rien lire d'abord (F-78 / SF-78-01).
     *
     * <p>Le nombre de lignes touchées <b>est</b> la réponse : 1 = l'onglet tenait déjà sa place,
     * 0 = il n'en a pas encore. Aucune fenêtre entre la question et l'écriture, puisqu'il n'y a
     * qu'une écriture.</p>
     *
     * <p>{@code opened_at} n'est <b>jamais</b> réécrit : c'est lui qui décide, en cas de course,
     * qui garde sa place. Un renouvellement qui le remettrait à l'instant présent ferait passer un
     * vieil onglet pour un nouveau et changerait <b>qui</b> est refusé au plafond.</p>
     */
    @Modifying
    @Query("update LiveTerminal t set t.workspaceId = :workspaceId, t.lastSeenAt = :now"
            + " where t.userId = :userId and t.sessionId = :sessionId")
    int renew(@Param("userId") UUID userId, @Param("sessionId") String sessionId,
            @Param("workspaceId") UUID workspaceId, @Param("now") OffsetDateTime now);

    /**
     * Renouvelle la place <b>et</b> y range l'aperçu de F-76, en une seule écriture.
     *
     * <p>Deux méthodes plutôt qu'une avec des valeurs conditionnelles : un battement <b>sans</b>
     * aperçu ne doit rien effacer, et la façon la plus sûre de ne rien effacer est de ne pas
     * nommer les colonnes.</p>
     */
    @Modifying
    @Query("update LiveTerminal t set t.workspaceId = :workspaceId, t.lastSeenAt = :now,"
            + " t.activity = :activity, t.activityDetail = :activityDetail,"
            + " t.previewLines = :previewLines, t.activityAt = :now"
            + " where t.userId = :userId and t.sessionId = :sessionId")
    int renewWithPreview(@Param("userId") UUID userId, @Param("sessionId") String sessionId,
            @Param("workspaceId") UUID workspaceId, @Param("now") OffsetDateTime now,
            @Param("activity") TerminalActivity activity,
            @Param("activityDetail") String activityDetail,
            @Param("previewLines") String previewLines);

    /** Libération explicite : un onglet ne peut libérer que sa propre place. */
    void deleteByUserIdAndSessionId(UUID userId, String sessionId);

    /** Purge des places expirées d'un utilisateur — bornée à lui, jamais un balayage global. */
    @Modifying
    @Query("delete from LiveTerminal t where t.userId = :userId and t.lastSeenAt <= :cutoff")
    int deleteStale(@Param("userId") UUID userId, @Param("cutoff") OffsetDateTime cutoff);

    /** Purge à la suppression du compte : aucune place ne survit à son propriétaire. */
    void deleteByUserId(UUID userId);
}
