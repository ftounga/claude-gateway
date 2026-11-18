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
            + "and d.createdAt >= :since order by d.createdAt desc")
    List<JarvisDelegation> openOffers(@Param("userId") UUID userId, @Param("subjectId") UUID subjectId,
            @Param("chantier") Integer chantier, @Param("since") OffsetDateTime since, Pageable page);

    /** Les offres pas encore prises du fil commun du sujet (sans chantier), les plus récentes d'abord. */
    @Query("select d from JarvisDelegation d where d.userId = :userId and d.subjectId = :subjectId "
            + "and d.chantier is null and d.demande is not null and d.takenAt is null and d.finishedAt is null "
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
            + "and d.createdAt < :before")
    List<JarvisDelegation> expiredOffers(@Param("before") OffsetDateTime before, Pageable page);

    /** Les délégations prises mais jamais terminées depuis trop longtemps (pod arrêté en plein tour). */
    @Query("select d from JarvisDelegation d where d.finishedAt is null and d.takenAt is not null "
            + "and d.createdAt < :before")
    List<JarvisDelegation> staleTaken(@Param("before") OffsetDateTime before, Pageable page);

    /** Termine une ligne précise (balayage) ; 1 si c'est ce passage qui l'a terminée. */
    @Modifying
    @Transactional
    @Query("update JarvisDelegation d set d.finishedAt = :now where d.id = :id and d.finishedAt is null")
    int close(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    @Modifying
    @Transactional
    @Query("delete from JarvisDelegation d where d.createdAt < :before")
    int purgeBefore(@Param("before") OffsetDateTime before);

    @Modifying
    @Transactional
    @Query("delete from JarvisDelegation d where d.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
