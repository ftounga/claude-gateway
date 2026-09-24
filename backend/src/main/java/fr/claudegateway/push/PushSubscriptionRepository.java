package fr.claudegateway.push;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistance des abonnements Web Push (F-153 / SF-153-02).
 *
 * <p><b>Toute</b> lecture filtre sur {@code user_id} : il n'existe volontairement aucune méthode qui
 * lise un abonnement par son seul identifiant. On ne notifie que les appareils du propriétaire.</p>
 *
 * <p>Seule exception au filtre {@code user_id} : {@link #deleteByEndpoint(String)}, la purge d'un
 * endpoint <b>mort</b> (le service push a répondu 404/410). L'endpoint est l'adresse elle-même,
 * devenue invalide côté fournisseur ; sa suppression n'expose aucune donnée d'autrui.</p>
 */
@Repository
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, UUID> {

    /** Abonnements d'un utilisateur — les seuls appareils qu'on notifiera. */
    List<PushSubscription> findByUserId(UUID userId);

    /** L'abonnement de cet appareil (endpoint) pour cet utilisateur, s'il existe. */
    Optional<PushSubscription> findByUserIdAndEndpoint(UUID userId, String endpoint);

    /** Désabonnement explicite : un utilisateur ne retire que son propre appareil. */
    @Modifying
    @Transactional
    void deleteByUserIdAndEndpoint(UUID userId, String endpoint);

    /**
     * Purge d'un endpoint mort (404/410 du service push). Non filtré {@code user_id} à dessein :
     * l'adresse est invalide côté fournisseur, quelle que soit la ligne qui la porte.
     */
    @Modifying
    @Transactional
    void deleteByEndpoint(String endpoint);

    /** Purge à la suppression du compte : aucun abonnement ne survit à son propriétaire. */
    @Modifying
    @Transactional
    void deleteByUserId(UUID userId);
}
