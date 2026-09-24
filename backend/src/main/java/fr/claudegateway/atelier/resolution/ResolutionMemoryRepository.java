package fr.claudegateway.atelier.resolution;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistance de la mémoire de résolutions (F-148 / SF-148-08).
 *
 * <p><b>Aucune lecture sans le couple utilisateur + poste.</b> C'est la garantie d'isolation : la
 * mémoire d'un poste ne peut pas être servie au tour d'un autre.</p>
 */
@Repository
public interface ResolutionMemoryRepository extends JpaRepository<ResolutionMemoryEntry, UUID> {

    /**
     * La fenêtre récente des résolutions d'un poste — bornée, du plus récent au plus ancien. Le rappel
     * matche lexicalement dans cette fenêtre : on ne charge jamais tout l'historique.
     */
    List<ResolutionMemoryEntry> findTop100ByUserIdAndHostIdOrderByCreatedAtDesc(UUID userId, UUID hostId);

    /** Purge à la suppression du compte : la mémoire ne survit pas à son propriétaire. */
    void deleteByUserId(UUID userId);

    /**
     * Combien de lignes ce compte a-t-il ici (F-156 / SF-156-03) : <b>zéro</b> prouve que la mémoire de résolutions
     * n'a jamais été alimentée — une capacité <b>dormante</b>, qui ne demande aucun développement
     * mais qu'on s'en aperçoive.
     */
    long countByUserId(UUID userId);
}
