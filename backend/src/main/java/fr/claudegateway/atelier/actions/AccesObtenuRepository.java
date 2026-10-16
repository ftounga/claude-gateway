package fr.claudegateway.atelier.actions;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Le registre des accès (F-180 / SF-180-04). Toute méthode porte {@code user_id}. */
public interface AccesObtenuRepository extends JpaRepository<AccesObtenu, UUID> {

    /** Le registre d'un poste, les plus récents d'abord. */
    List<AccesObtenu> findByUserIdAndHostIdOrderByObtainedAtDesc(UUID userId, UUID hostId);

    /** Le registre d'un terminal hébergé (sans poste). */
    List<AccesObtenu> findByUserIdAndHostIdIsNullAndWorkspaceIdOrderByObtainedAtDesc(UUID userId, UUID workspaceId);

    Optional<AccesObtenu> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndActionId(UUID userId, UUID actionId);

    /** Les accès qui expirent dans la fenêtre, tous comptes (F-180 / SF-180-06 : le rappel). */
    List<AccesObtenu> findByExpiresAtBetween(LocalDate from, LocalDate to);

    /** Reporte le registre d'un interlocuteur fusionné (F-180 / SF-180-03). */
    @Modifying
    @Query("update AccesObtenu a set a.interlocuteurId = :into where a.userId = :userId and a.interlocuteurId = :from")
    int repointInterlocuteur(@Param("userId") UUID userId, @Param("from") UUID from, @Param("into") UUID into);

    @Modifying
    @Query("delete from AccesObtenu a where a.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);

    /** Purge à la suppression d'un terminal hébergé : son registre n'a plus de portée. */
    @Modifying
    @Query("delete from AccesObtenu a where a.userId = :userId and a.hostId is null and a.workspaceId = :workspaceId")
    int purgeHostedWorkspace(@Param("userId") UUID userId, @Param("workspaceId") UUID workspaceId);
}
