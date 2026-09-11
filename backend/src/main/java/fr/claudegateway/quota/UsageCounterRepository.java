package fr.claudegateway.quota;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistance des compteurs d'usage (F-10). Tout accès propre à un utilisateur passe par une
 * méthode filtrant sur {@code user_id} (isolation multi-tenant). Aucune logique métier ici.
 */
@Repository
public interface UsageCounterRepository extends JpaRepository<UsageCounter, UUID> {

    Optional<UsageCounter> findByUserIdAndPeriodStart(UUID userId, LocalDate periodStart);

    /** Export RGPD : tous les compteurs d'usage d'un utilisateur (isolation {@code user_id}). */
    List<UsageCounter> findByUserId(UUID userId);

    /**
     * Rapport d'usage (F-16) : compteurs d'un utilisateur triés de la période la plus récente à la
     * plus ancienne (isolation {@code user_id}). La fenêtre est ensuite bornée dans le service.
     */
    List<UsageCounter> findByUserIdOrderByPeriodStartDesc(UUID userId);

    /**
     * Compteurs de <b>tous</b> les comptes sur une fenêtre de mois — console d'administration
     * (F-61 / SF-61-03), et elle seule.
     *
     * <p>C'est la seule lecture du projet qui ne filtre pas sur {@code user_id}, parce que son objet
     * est précisément la vue transverse. Elle n'est atteignable que derrière
     * {@code AdminService.assertAdmin()}, et ne rend que des <b>volumes</b> : la table ne porte
     * aucun contenu, ni projet, ni poste.</p>
     *
     * @param from début inclus (premier du mois)
     * @param to   fin <b>exclue</b> (premier du mois suivant le dernier observé)
     */
    List<UsageCounter> findByPeriodStartGreaterThanEqualAndPeriodStartLessThan(LocalDate from,
            LocalDate to);

    /** Suppression RGPD : tous les compteurs d'usage d'un utilisateur. */
    void deleteByUserId(UUID userId);
}
