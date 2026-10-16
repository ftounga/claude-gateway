package fr.claudegateway.atelier.besoin;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès aux pièces des besoins — toujours sous {@code user_id} (F-205). */
public interface AppRequestAttachmentRepository extends JpaRepository<AppRequestAttachment, UUID> {

    List<AppRequestAttachment> findByRequestIdAndUserIdOrderBySeqAsc(UUID requestId, UUID userId);

    List<AppRequestAttachment> findByRequestIdInAndUserIdOrderBySeqAsc(Collection<UUID> requestIds, UUID userId);

    Optional<AppRequestAttachment> findByRequestIdAndUserIdAndSeq(UUID requestId, UUID userId, int seq);

    /** Purge à la suppression du compte (les pièces suivent aussi le besoin, en cascade). */
    @Modifying
    @Query("delete from AppRequestAttachment a where a.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
