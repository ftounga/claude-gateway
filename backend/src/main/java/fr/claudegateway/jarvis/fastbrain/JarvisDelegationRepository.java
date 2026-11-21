package fr.claudegateway.jarvis.fastbrain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Accès aux délégations de Jarvis (F-272 / SF-272-01). Toute lecture filtre par {@code user_id}. */
@Repository
public interface JarvisDelegationRepository extends JpaRepository<JarvisDelegation, UUID> {

    Optional<JarvisDelegation> findByUserIdAndDelegationId(UUID userId, UUID delegationId);

    /** Les offres pas encore prises d'un fil, les plus récentes d'abord (chantier précis). */
    @Query("select d from JarvisDelegation d where d.userId = :userId and d.subjectId = :subjectId "
            + "and d.chantier = :chantier and d.demande is not null and d.takenAt is null and d.finishedAt is null "
            + "and (d.phase is null or d.phase <> 'QUEUED') "
            + "and d.createdAt >= :since order by d.createdAt desc")
    List<JarvisDelegation> openOffers(@Param("userId") UUID userId, @Param("subjectId") UUID subjectId,
            @Param("chantier") Integer chantier, @Param("since") OffsetDateTime since, Pageable page);

    /** Les offres pas encore prises du fil commun du sujet (sans chantier), les plus récentes d'abord. */
    @Query("select d from JarvisDelegation d where d.userId = :userId and d.subjectId = :subjectId "
            + "and d.chantier is null and d.demande is not null and d.takenAt is null and d.finishedAt is null "
            + "and (d.phase is null or d.phase <> 'QUEUED') "
            + "and d.createdAt >= :since order by d.createdAt desc")
    List<JarvisDelegation> openOffersCommon(@Param("userId") UUID userId, @Param("subjectId") UUID subjectId,
            @Param("since") OffsetDateTime since, Pageable page);

    /** La prise atomique : vrai (1) pour le seul tour qui l'obtient, quel que soit le pod. */
    @Modifying
    @Transactional
    @Query("update JarvisDelegation d set d.takenAt = :now where d.id = :id and d.userId = :userId "
            + "and d.takenAt is null and d.finishedAt is null")
    int claim(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") OffsetDateTime now);

    /** Les délégations non terminées du compte depuis {@code since}, de la plus ancienne à la plus récente. */
    @Query("select d from JarvisDelegation d where d.userId = :userId and d.finishedAt is null "
            + "and d.createdAt >= :since order by d.createdAt asc")
    List<JarvisDelegation> unfinished(@Param("userId") UUID userId, @Param("since") OffsetDateTime since,
            Pageable page);

    /** Termine une délégation du compte (une fois). */
    @Modifying
    @Transactional
    @Query("update JarvisDelegation d set d.finishedAt = :now where d.userId = :userId "
            + "and d.delegationId = :delegationId and d.finishedAt is null")
    int finish(@Param("userId") UUID userId, @Param("delegationId") UUID delegationId,
            @Param("now") OffsetDateTime now);

    /** Les offres jamais prises, échues : leur tour n'est jamais venu (tous comptes : balayage). */
    @Query("select d from JarvisDelegation d where d.finishedAt is null and d.takenAt is null "
            + "and (d.phase is null or d.phase <> 'QUEUED') and d.createdAt < :before")
    List<JarvisDelegation> expiredOffers(@Param("before") OffsetDateTime before, Pageable page);

    /** Les délégations prises mais jamais terminées depuis trop longtemps (pod arrêté en plein tour). */
    @Query("select d from JarvisDelegation d where d.finishedAt is null and d.takenAt is not null "
            + "and d.createdAt < :before")
    List<JarvisDelegation> staleTaken(@Param("before") OffsetDateTime before, Pageable page);

    /** Termine une ligne précise (balayage) ; 1 si c'est ce passage qui l'a terminée (issue : échec). */
    @Modifying
    @Transactional
    @Query("update JarvisDelegation d set d.finishedAt = :now, d.outcome = 'FAILED' where d.id = :id "
            + "and d.finishedAt is null")
    int close(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    // ------------------------------------------------------------------ F-270 / SF-270-01 : les travaux vivants

    /** Les tours directs annoncés et pas encore pris d'un fil (chantier précis), les plus récents d'abord. */
    @Query("select d from JarvisDelegation d where d.userId = :userId and d.subjectId = :subjectId "
            + "and d.chantier = :chantier and d.origin = 'DIRECT' and d.takenAt is null and d.finishedAt is null "
            + "and d.createdAt >= :since order by d.createdAt desc")
    List<JarvisDelegation> openDirect(@Param("userId") UUID userId, @Param("subjectId") UUID subjectId,
            @Param("chantier") Integer chantier, @Param("since") OffsetDateTime since, Pageable page);

    /** Les tours directs annoncés et pas encore pris du fil commun du sujet, les plus récents d'abord. */
    @Query("select d from JarvisDelegation d where d.userId = :userId and d.subjectId = :subjectId "
            + "and d.chantier is null and d.origin = 'DIRECT' and d.takenAt is null and d.finishedAt is null "
            + "and d.createdAt >= :since order by d.createdAt desc")
    List<JarvisDelegation> openDirectCommon(@Param("userId") UUID userId, @Param("subjectId") UUID subjectId,
            @Param("since") OffsetDateTime since, Pageable page);

    /** La phase d'un travail vivant du compte ; 1 si elle a changé. */
    @Modifying
    @Transactional
    @Query("update JarvisDelegation d set d.phase = :phase where d.userId = :userId "
            + "and d.delegationId = :delegationId and d.finishedAt is null "
            + "and (d.phase is null or d.phase <> :phase)")
    int phase(@Param("userId") UUID userId, @Param("delegationId") UUID delegationId, @Param("phase") String phase);

    /** Termine un travail du compte avec son issue (une fois). */
    @Modifying
    @Transactional
    @Query("update JarvisDelegation d set d.finishedAt = :now, d.outcome = :outcome where d.userId = :userId "
            + "and d.delegationId = :delegationId and d.finishedAt is null")
    int finishWith(@Param("userId") UUID userId, @Param("delegationId") UUID delegationId,
            @Param("outcome") String outcome, @Param("now") OffsetDateTime now);

    /** Le « oui » attendu sur un fil (chantier précis) n'est plus attendu : un nouveau travail est parti. */
    @Modifying
    @Transactional
    @Query("update JarvisDelegation d set d.outcome = 'DONE' where d.userId = :userId and d.subjectId = :subjectId "
            + "and d.chantier = :chantier and d.outcome = 'WAITING_YES'")
    int answered(@Param("userId") UUID userId, @Param("subjectId") UUID subjectId,
            @Param("chantier") Integer chantier);

    /** Même chose pour le fil commun du sujet. */
    @Modifying
    @Transactional
    @Query("update JarvisDelegation d set d.outcome = 'DONE' where d.userId = :userId and d.subjectId = :subjectId "
            + "and d.chantier is null and d.outcome = 'WAITING_YES'")
    int answeredCommon(@Param("userId") UUID userId, @Param("subjectId") UUID subjectId);

    // ------------------------------------------------------------------ F-270 / SF-270-04 : l'enchaînement

    /** Les commandes enchaînées (en attente) d'un fil, chantier précis. */
    @Query("select d from JarvisDelegation d where d.userId = :userId and d.subjectId = :subjectId "
            + "and d.chantier = :chantier and d.phase = 'QUEUED' and d.finishedAt is null")
    List<JarvisDelegation> queued(@Param("userId") UUID userId, @Param("subjectId") UUID subjectId,
            @Param("chantier") Integer chantier);

    /** Les commandes enchaînées du fil commun du sujet. */
    @Query("select d from JarvisDelegation d where d.userId = :userId and d.subjectId = :subjectId "
            + "and d.chantier is null and d.phase = 'QUEUED' and d.finishedAt is null")
    List<JarvisDelegation> queuedCommon(@Param("userId") UUID userId, @Param("subjectId") UUID subjectId);

    /** Libère une commande enchaînée : elle redevient une offre fraîche (le tour du fil la prendra) ; 1 si libérée. */
    @Modifying
    @Transactional
    @Query("update JarvisDelegation d set d.phase = null, d.createdAt = :now where d.userId = :userId "
            + "and d.delegationId = :delegationId and d.phase = 'QUEUED' and d.finishedAt is null")
    int release(@Param("userId") UUID userId, @Param("delegationId") UUID delegationId,
            @Param("now") OffsetDateTime now);

    /** Les commandes enchaînées jamais libérées depuis trop longtemps (balayage, tous comptes). */
    @Query("select d from JarvisDelegation d where d.finishedAt is null and d.phase = 'QUEUED' "
            + "and d.createdAt < :before")
    List<JarvisDelegation> staleQueued(@Param("before") OffsetDateTime before, Pageable page);

    /** Les travaux du compte depuis {@code since} (vivants ou finis), du plus ancien au plus récent. */
    @Query("select d from JarvisDelegation d where d.userId = :userId and d.createdAt >= :since "
            + "order by d.createdAt asc")
    List<JarvisDelegation> recent(@Param("userId") UUID userId, @Param("since") OffsetDateTime since, Pageable page);

    @Modifying
    @Transactional
    @Query("delete from JarvisDelegation d where d.createdAt < :before")
    int purgeBefore(@Param("before") OffsetDateTime before);

    @Modifying
    @Transactional
    @Query("delete from JarvisDelegation d where d.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
