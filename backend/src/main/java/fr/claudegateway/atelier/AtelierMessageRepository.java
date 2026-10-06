package fr.claudegateway.atelier;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistance des messages Atelier (F-28 / SF-28-02). Lecture toujours filtrée sur {@code user_id}. */
@Repository
public interface AtelierMessageRepository extends JpaRepository<AtelierMessage, UUID> {

    List<AtelierMessage> findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(UUID workspaceId, UUID userId);

    /**
     * Les six derniers messages du fil, du plus récent au plus ancien (F-144 / SF-144-02) : la suite
     * prédite n'a besoin que du dernier échange, et charger tout le fil pour le trouver serait payer
     * des milliers de lignes pour deux. Filtrée {@code workspace_id} + {@code user_id}.
     */
    List<AtelierMessage> findTop6ByWorkspaceIdAndUserIdOrderByCreatedAtDesc(UUID workspaceId, UUID userId);

    /**
     * Rappel à la demande (F-162 / SF-162-01) : recherche <b>mot-clé</b> dans le contenu des messages
     * du fil, du plus récent au plus ancien, bornée par {@code pageable}.
     *
     * <p><b>Isolation stricte</b> : filtrée {@code workspace_id} <b>ET</b> {@code user_id} — un
     * utilisateur ne peut jamais rappeler les messages d'un autre, ni d'un autre workspace.</p>
     *
     * <p><b>Sur TOUT le fil</b> : aucune frontière de rejeu / compaction / « Nouveau départ » n'est
     * appliquée ici (contrairement à {@link #findByWorkspaceIdAndUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc}).
     * {@code recall} retrouve donc même les tours résumés, repliés ou d'avant un « Nouveau départ ».</p>
     *
     * <p><b>Portabilité</b> : {@code LOWER(content) LIKE :term} (avec {@code :term} déjà encadré de
     * {@code %…%} et minuscule) — insensible à la casse et valide en H2 comme en PostgreSQL (pas de
     * {@code to_tsvector}, qui casserait les tests H2).</p>
     */
    @Query("select m from AtelierMessage m where m.workspaceId = :workspaceId and m.userId = :userId "
            + "and lower(m.content) like :term order by m.createdAt desc")
    List<AtelierMessage> searchByContent(@Param("workspaceId") UUID workspaceId,
            @Param("userId") UUID userId, @Param("term") String term, Pageable pageable);

    /**
     * Relecture <b>isolée</b> de messages par identifiants (F-162 / SF-162-06, recherche sémantique) :
     * charge les entités dont l'{@code id} est dans {@code ids}, <b>toujours filtrées {@code workspace_id}
     * ET {@code user_id}</b>. C'est la défense en profondeur du chemin sémantique : même si la recherche
     * vecteur (SQL natif) filtre déjà le tenant, cette relecture le refiltre — un id d'un autre
     * utilisateur / workspace ne peut jamais être matérialisé ici. L'ordre de la liste n'est pas garanti :
     * l'appelant réordonne selon l'ordre de similarité renvoyé par la recherche vecteur.
     */
    List<AtelierMessage> findByWorkspaceIdAndUserIdAndIdIn(UUID workspaceId, UUID userId,
            java.util.Collection<UUID> ids);

    /**
     * Rappel <b>à portée poste</b> (F-178 / SF-178-01) : même recherche mot-clé que
     * {@link #searchByContent}, mais sur un <b>ensemble</b> de fils — le terminal du poste et les sujets
     * de CE poste, tels que résolus par l'appelant depuis le terminal possédé. Toujours filtrée
     * {@code user_id} : un id de workspace d'autrui glissé dans l'ensemble ne remonte rien.
     */
    @Query("select m from AtelierMessage m where m.workspaceId in :workspaceIds and m.userId = :userId "
            + "and lower(m.content) like :term order by m.createdAt desc")
    List<AtelierMessage> searchByContentInWorkspaces(
            @Param("workspaceIds") java.util.Collection<UUID> workspaceIds,
            @Param("userId") UUID userId, @Param("term") String term, Pageable pageable);

    /**
     * Dernière activité de chaque fil d'un ensemble (F-178 / SF-178-02, {@code sujets_etat}) : lignes
     * {@code [workspaceId, max(createdAt)]}, toujours filtrées {@code user_id}.
     */
    @Query("select m.workspaceId, max(m.createdAt) from AtelierMessage m where m.workspaceId in :workspaceIds "
            + "and m.userId = :userId group by m.workspaceId")
    List<Object[]> lastActivityByWorkspace(@Param("workspaceIds") java.util.Collection<UUID> workspaceIds,
            @Param("userId") UUID userId);

    /**
     * Relecture <b>isolée</b> du chemin sémantique à portée poste (F-178 / SF-178-01) : filtrée
     * {@code user_id} ET {@code workspace_id} dans l'ensemble des fils du poste (défense en profondeur).
     */
    List<AtelierMessage> findByUserIdAndWorkspaceIdInAndIdIn(UUID userId,
            java.util.Collection<UUID> workspaceIds, java.util.Collection<UUID> ids);

    /**
     * Numéro de tour d'un extrait (F-162 / SF-162-01) : nombre de messages d'un rôle donné (typiquement
     * {@code USER}) jusqu'à un instant inclus, dans le fil. Filtrée {@code workspace_id} + {@code user_id}
     * comme toutes les lectures de cette table. Sert à étiqueter chaque extrait « tour N ».
     */
    long countByWorkspaceIdAndUserIdAndRoleAndCreatedAtLessThanEqual(
            UUID workspaceId, UUID userId, String role, java.time.OffsetDateTime createdAt);

    /**
     * Offset de base du numéro de tour pour le résumé de compaction (F-162 / SF-162-02) : nombre de
     * messages d'un rôle donné (typiquement {@code USER}) <b>strictement antérieurs</b> à un instant,
     * dans le fil. Symétrique de {@link #countByWorkspaceIdAndUserIdAndRoleAndCreatedAtLessThanEqual}
     * (qui compte {@code <=}), utilisé ici pour numéroter les tours d'une fenêtre rejouée <b>à partir
     * du dernier tour résumé</b> — la compaction étant incrémentale, la frontière avance et le premier
     * tour de la fenêtre n'est pas le tour 1. « tour N » désigne ainsi le même tour que {@code recall}.
     * Filtrée {@code workspace_id} + {@code user_id} comme toutes les lectures de cette table.
     */
    long countByWorkspaceIdAndUserIdAndRoleAndCreatedAtLessThan(
            UUID workspaceId, UUID userId, String role, java.time.OffsetDateTime createdAt);

    /**
     * Messages postérieurs à la frontière de rejeu du fil (F-39 / SF-39-04) : ce que l'agent a
     * encore en mémoire après un « nouveau départ ». Filtrée sur {@code user_id} comme toutes les
     * lectures de cette table.
     */
    List<AtelierMessage> findByWorkspaceIdAndUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
            UUID workspaceId, UUID userId, java.time.OffsetDateTime since);

    /**
     * Combien de messages sont antérieurs au marqueur de repli du nouveau départ manuel
     * (F-117 / SF-117-05) : le nombre de messages que l'écran replie derrière « Voir l'historique ».
     * Tous rôles confondus — l'affichage replie le fil entier, comme {@code turns} le compte. Filtrée
     * sur {@code user_id} comme toutes les lectures de cette table.
     */
    long countByWorkspaceIdAndUserIdAndCreatedAtLessThan(UUID workspaceId, UUID userId,
            java.time.OffsetDateTime foldedAt);

    /** Purge à la suppression du compte (SF-11-03). */
    void deleteByUserId(UUID userId);

    /** Purge des messages d'un workspace supprimé (SF-11-03) : sans elle, ils restent orphelins. */
    void deleteByWorkspaceId(UUID workspaceId);

    /**
     * Combien de <b>tours</b> ont été demandés sur ces projets depuis une date (F-140 / SF-140-01).
     *
     * <p>Un tour = un message de l'utilisateur. C'est l'autre moitié de la mesure : le dénominateur
     * de « combien d'appels d'outils faut-il pour répondre ». Les projets sont ceux du poste, et le
     * filtre porte aussi sur {@code user_id}.</p>
     */
    long countByUserIdAndWorkspaceIdInAndRoleAndCreatedAtGreaterThanEqual(UUID userId,
            java.util.Collection<UUID> workspaceIds, String role, java.time.OffsetDateTime since);
}
