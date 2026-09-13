package fr.claudegateway.pages;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistance des liens de partage (F-109 / SF-109-05). */
@Repository
public interface PageShareRepository extends JpaRepository<PageShare, UUID> {

    /** Les liens d'une page, le plus récent d'abord (isolation {@code user_id}). */
    List<PageShare> findByPageIdAndUserIdOrderByCreatedAtDesc(UUID pageId, UUID userId);

    /** Un lien d'une page (isolation {@code user_id}). */
    Optional<PageShare> findByIdAndPageIdAndUserId(UUID id, UUID pageId, UUID userId);

    /**
     * Résolution publique par empreinte : la seule lecture sans utilisateur. L'appelant relit ensuite la page
     * filtrée sur le {@code user_id} de la ligne.
     */
    Optional<PageShare> findByTokenHash(String tokenHash);
}
