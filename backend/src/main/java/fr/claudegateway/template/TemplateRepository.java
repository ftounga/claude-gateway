package fr.claudegateway.template;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistance des modèles de prompts. Toute lecture propre à un utilisateur passe par une méthode
 * filtrant sur {@code user_id} (isolation multi-tenant). Aucune logique métier ici.
 */
@Repository
public interface TemplateRepository extends JpaRepository<PromptTemplate, UUID> {

    List<PromptTemplate> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    Optional<PromptTemplate> findByIdAndUserId(UUID id, UUID userId);

    /** Suppression RGPD : tous les modèles d'un utilisateur (isolation {@code user_id}). */
    void deleteByUserId(UUID userId);
}
