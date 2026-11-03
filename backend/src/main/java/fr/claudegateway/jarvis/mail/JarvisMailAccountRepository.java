package fr.claudegateway.jarvis.mail;

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

    /** F-242 / SF-242-02 — les boîtes dues : jamais en pause, échéance passée, bail libre. */
    @Query("select a.id from JarvisMailAccount a where a.status <> fr.claudegateway.jarvis.mail.JarvisMailAccount.Status.PAUSED "
            + "and (a.nextPollAt is null or a.nextPollAt <= :now) and (a.leaseUntil is null or a.leaseUntil < :now) "
            + "order by a.nextPollAt asc")
    List<UUID> dueIds(@Param("now") OffsetDateTime now, Pageable page);

    /** Réservation par {@code UPDATE} conditionnel (patron {@code claimSlot}) : un seul pod gagne. */
    @Modifying
    @Transactional
    @Query("update JarvisMailAccount a set a.leaseUntil = :until where a.id = :id "
            + "and (a.leaseUntil is null or a.leaseUntil < :now)")
    int claim(@Param("id") UUID id, @Param("now") OffsetDateTime now, @Param("until") OffsetDateTime until);

    /** Relève réussie : position de la boîte, état OK, bail rendu — seulement si le secret utilisé est toujours le sien. */
    @Modifying
    @Transactional
    @Query("update JarvisMailAccount a set a.inboxUidvalidity = :validity, a.inboxLastUid = :lastUid, a.lastOkAt = :now, "
            + "a.status = fr.claudegateway.jarvis.mail.JarvisMailAccount.Status.OK, a.lastError = null, a.authFailures = 0, "
            + "a.nextPollAt = :next, a.leaseUntil = null where a.id = :id and a.ciphertext = :ciphertext")
    int markPolled(@Param("id") UUID id, @Param("ciphertext") String ciphertext, @Param("validity") long validity,
            @Param("lastUid") long lastUid, @Param("now") OffsetDateTime now, @Param("next") OffsetDateTime next);

    /** Google refuse le secret utilisé : refus, retard, bail rendu — jamais sur un secret remplacé entre-temps. */
    @Modifying
    @Transactional
    @Query("update JarvisMailAccount a set a.status = fr.claudegateway.jarvis.mail.JarvisMailAccount.Status.AUTH_FAILED, "
            + "a.authFailures = :failures, a.lastError = :error, a.nextPollAt = :next, a.leaseUntil = null "
            + "where a.id = :id and a.ciphertext = :ciphertext")
    int markAuthFailed(@Param("id") UUID id, @Param("ciphertext") String ciphertext, @Param("failures") int failures,
            @Param("error") String error, @Param("next") OffsetDateTime next);

    /** La boîte ne répond pas : erreur notée, prochaine relève, bail rendu ; l'état ne change pas. */
    @Modifying
    @Transactional
    @Query("update JarvisMailAccount a set a.lastError = :error, a.nextPollAt = :next, a.leaseUntil = null where a.id = :id")
    int markUnavailable(@Param("id") UUID id, @Param("error") String error, @Param("next") OffsetDateTime next);
}
