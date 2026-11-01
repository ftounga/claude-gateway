package fr.claudegateway.atelier.sends;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Les envois marqués (F-211 / SF-211-01). Toute lecture est filtrée {@code user_id}. */
@Repository
public interface MessageBlockSendRepository extends JpaRepository<MessageBlockSend, UUID> {

    Optional<MessageBlockSend> findByUserIdAndWorkspaceIdAndMessageIdAndBlockIndex(
            UUID userId, UUID workspaceId, UUID messageId, int blockIndex);

    /** Les envois actifs d'un terminal, les plus anciens d'abord. */
    List<MessageBlockSend> findByUserIdAndWorkspaceIdAndCancelledAtIsNullOrderBySentAtAsc(
            UUID userId, UUID workspaceId);

    /** Les envois actifs postérieurs à un instant : la matière de la note de tour. */
    List<MessageBlockSend> findByUserIdAndWorkspaceIdAndCancelledAtIsNullAndSentAtAfterOrderBySentAtAsc(
            UUID userId, UUID workspaceId, OffsetDateTime after);

    /** Les envois actifs de plusieurs sujets (F-231 / SF-231-06, compteur « À envoyer » du tableau du client). */
    List<MessageBlockSend> findByUserIdAndWorkspaceIdInAndCancelledAtIsNull(UUID userId,
            java.util.Collection<UUID> workspaceIds);
}
