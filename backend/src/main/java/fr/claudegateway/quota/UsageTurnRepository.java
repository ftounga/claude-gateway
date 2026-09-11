package fr.claudegateway.quota;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistance du journal de consommation par tour (F-61 / SF-61-01). <b>Toute</b> lecture filtre sur
 * {@code user_id} : il n'existe ici aucune méthode capable de lire les tours d'un autre compte, ni
 * de tous les comptes. Aucune logique métier.
 *
 * <p>La console d'administration n'utilise <b>pas</b> ce journal : elle agrège {@link UsageCounter}
 * (F-10), qui ignore les projets. C'est délibéré — le journal sait de quel client parle chaque tour,
 * et une vue admin bâtie dessus serait un pas vers la surveillance.</p>
 */
@Repository
public interface UsageTurnRepository extends JpaRepository<UsageTurn, UUID> {

    /**
     * Consommation d'un utilisateur sur une fenêtre, agrégée par <b>poste</b> puis par
     * <b>projet</b> — l'unique lecture prévue, couverte par l'index
     * {@code idx_usage_turns_user_occurred} (migration 068).
     *
     * <p>Une seule requête pour tout l'écran, plutôt qu'une par projet : le nombre de projets d'un
     * consultant n'est pas borné, et une lecture par projet ferait grandir le coût de l'écran avec
     * le succès de l'utilisateur.</p>
     *
     * <p>Les couples dont le poste ou le projet est {@code null} sont <b>conservés</b> : ce sont les
     * tours « hors client », sans lesquels la somme des lignes ne ferait pas le total.</p>
     *
     * @param userId utilisateur du contexte de sécurité (jamais un paramètre client)
     * @param from   borne basse incluse
     * @param to     borne haute <b>exclue</b>
     */
    @Query("""
            select t.hostId as hostId, t.workspaceId as workspaceId,
                   sum(t.inputTokens) as inputTokens, sum(t.outputTokens) as outputTokens
            from UsageTurn t
            where t.userId = :userId and t.occurredAt >= :from and t.occurredAt < :to
            group by t.hostId, t.workspaceId
            """)
    List<UsageTurnAggregate> aggregateByHostAndWorkspace(@Param("userId") UUID userId,
            @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /** Tours d'un utilisateur, les plus récents d'abord (diagnostic et tests ; isolation `user_id`). */
    List<UsageTurn> findByUserIdOrderByOccurredAtDesc(UUID userId);

    /**
     * Purge à la suppression du compte (F-11 / SF-11-03) : le journal décrit l'activité d'un compte
     * et ne lui survit pas.
     */
    void deleteByUserId(UUID userId);
}
