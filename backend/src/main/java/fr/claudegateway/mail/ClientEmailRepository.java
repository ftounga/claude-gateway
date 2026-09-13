package fr.claudegateway.mail;

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
 * Courriels du client (F-110 / SF-110-02). Les lectures d'écran filtrent {@code user_id} ; les requêtes du
 * travailleur (hors contexte de sécurité) ne rendent que des identifiants et prennent une ligne par son
 * identifiant.
 */
public interface ClientEmailRepository extends JpaRepository<ClientEmail, UUID> {

    Optional<ClientEmail> findByIdAndUserId(UUID id, UUID userId);

    /** Courriels d'un genre mis en file par un compte depuis un instant (limite quotidienne). */
    long countByUserIdAndKindAndCreatedAtAfter(UUID userId, ClientEmail.Kind kind, OffsetDateTime since);

    /** Les courriels dus : en attente et à l'heure, ou pris sous un bail échu. */
    @Query("select e.id from ClientEmail e where (e.status = fr.claudegateway.mail.ClientEmailStatus.PENDING"
            + " and e.nextAttemptAt <= :now) or (e.status = fr.claudegateway.mail.ClientEmailStatus.SENDING"
            + " and e.leasedUntil < :now) order by e.nextAttemptAt asc")
    List<UUID> findDue(@Param("now") OffsetDateTime now, Pageable page);

    /** Prend un courriel dû sous bail ; rend 1 si la prise a réussi, 0 si un autre l'a pris. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ClientEmail e set e.status = fr.claudegateway.mail.ClientEmailStatus.SENDING,"
            + " e.leasedUntil = :until, e.attempts = e.attempts + 1, e.updatedAt = :now"
            + " where e.id = :id and ((e.status = fr.claudegateway.mail.ClientEmailStatus.PENDING"
            + " and e.nextAttemptAt <= :now) or (e.status = fr.claudegateway.mail.ClientEmailStatus.SENDING"
            + " and e.leasedUntil < :now))")
    int claim(@Param("id") UUID id, @Param("now") OffsetDateTime now, @Param("until") OffsetDateTime until);
}
