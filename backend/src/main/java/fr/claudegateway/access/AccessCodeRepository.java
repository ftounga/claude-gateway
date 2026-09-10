package fr.claudegateway.access;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.SubscriptionStatus;

/**
 * Accès aux codes d'accès (F-62 / SF-62-01).
 *
 * <p><b>Isolation.</b> Toute lecture d'un droit passe par
 * {@link #findFirstByRedeemedByUserIdOrderByGrantedUntilDesc(UUID)}, dont le {@code user_id} vient
 * du contexte de sécurité : il n'existe aucune méthode qui lise un droit sans le nommer. La liste
 * complète est réservée à l'administration, gardée en amont par {@code AdminService.assertAdmin()}.</p>
 */
@Repository
public interface AccessCodeRepository extends JpaRepository<AccessCode, UUID> {

    /** Retrouve un code par l'empreinte de son secret (le clair n'est jamais stocké). */
    Optional<AccessCode> findByCodeHash(String codeHash);

    /**
     * Dernier code consommé par cet utilisateur (le plus tardif au terme). Un seul droit peut être en
     * cours à la fois — le cumul est hors périmètre — donc ce « dernier » suffit à répondre « cet
     * utilisateur a-t-il un droit ouvert ? ».
     *
     * @param userId utilisateur du contexte de sécurité (isolation)
     */
    Optional<AccessCode> findFirstByRedeemedByUserIdOrderByGrantedUntilDesc(UUID userId);

    /** Tous les codes, du plus récent au plus ancien. Administration uniquement. */
    List<AccessCode> findAllByOrderByCreatedAtDesc();

    /**
     * Consomme le code <b>si et seulement si</b> il ne l'a pas déjà été.
     *
     * <p>C'est ici que l'usage unique devient vrai : la condition {@code redeemedAt is null} vit dans
     * la clause {@code WHERE}, donc dans la transaction de la base, et non dans un {@code if} Java
     * que deux requêtes simultanées franchiraient toutes les deux. Un retour de {@code 0} signifie
     * « quelqu'un vient de le consommer » — pas une erreur technique.</p>
     *
     * @return le nombre de lignes modifiées : {@code 1} si le code vient d'être consommé, {@code 0} sinon
     */
    @Modifying
    @Query("""
            update AccessCode c
               set c.redeemedByUserId = :userId,
                   c.redeemedAt       = :redeemedAt,
                   c.grantedUntil     = :grantedUntil,
                   c.previousPlanCode = :previousPlanCode,
                   c.previousStatus   = :previousStatus,
                   c.updatedAt        = :redeemedAt
             where c.id = :id
               and c.redeemedAt is null
            """)
    int consume(@Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("redeemedAt") OffsetDateTime redeemedAt,
            @Param("grantedUntil") OffsetDateTime grantedUntil,
            @Param("previousPlanCode") PlanCode previousPlanCode,
            @Param("previousStatus") SubscriptionStatus previousStatus);
}
