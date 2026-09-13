package fr.claudegateway.radar;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Alias des sujets (F-99). Toute méthode porte {@code user_id} et {@code host_id}. */
public interface RadarSubjectAliasRepository extends JpaRepository<RadarSubjectAlias, UUID> {

    List<RadarSubjectAlias> findByUserIdAndHostIdAndSubjectIdOrderByCreatedAtAsc(
            UUID userId, UUID hostId, UUID subjectId);

    List<RadarSubjectAlias> findByUserIdAndHostIdAndSubjectIdIn(
            UUID userId, UUID hostId, Collection<UUID> subjectIds);

    long deleteByUserIdAndHostId(UUID userId, UUID hostId);

    long deleteByUserId(UUID userId);
}
