package fr.claudegateway.diagrams;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** F-237 / SF-237-03 — les schémas conservés, toujours lus avec {@code user_id}. */
public interface DiagramRenderRepository extends JpaRepository<DiagramRender, UUID> {

    Optional<DiagramRender> findByIdAndUserId(UUID id, UUID userId);

    List<DiagramRender> findTop50ByUserIdAndWorkspaceIdOrderByCreatedAtDesc(UUID userId, UUID workspaceId);

    /**
     * F-238 / SF-238-07 — range le graphe calculé à la demande, <b>une fois</b> : seulement sur la ligne du compte,
     * et seulement si aucun graphe n'y est déjà. Rend le nombre de lignes modifiées (0 ou 1).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update DiagramRender d set d.graphJson = :json, d.graphVersion = :version, d.nodeCount = :nodes, "
            + "d.zoneCount = :zones, d.edgeCount = :edges "
            + "where d.id = :id and d.userId = :userId and d.graphJson is null")
    int storeGraph(@Param("id") UUID id, @Param("userId") UUID userId, @Param("json") String json,
            @Param("version") Short version, @Param("nodes") Integer nodes, @Param("zones") Integer zones,
            @Param("edges") Integer edges);
}
