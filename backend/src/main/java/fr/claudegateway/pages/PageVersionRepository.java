package fr.claudegateway.pages;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistance des versions de pages (F-109). Toute lecture filtre {@code user_id}. */
@Repository
public interface PageVersionRepository extends JpaRepository<PageVersion, UUID> {

    /** Versions conservées d'une page, la plus ancienne d'abord (isolation {@code user_id}). */
    List<PageVersion> findByPageIdAndUserIdOrderByVersionAsc(UUID pageId, UUID userId);

    /** Une version précise d'une page (isolation {@code user_id}). */
    Optional<PageVersion> findByPageIdAndUserIdAndVersion(UUID pageId, UUID userId, int version);

    /** Octets conservés par un compte, toutes pages et versions confondues : la mesure du quota. */
    @Query("select coalesce(sum(v.sizeBytes), 0) from PageVersion v where v.userId = :userId")
    long sumSizeBytesByUserId(@Param("userId") UUID userId);
}
