package fr.claudegateway.atelier.agent;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistance de la configuration Managed Agents (F-28 / Phase 2). Config globale (ligne unique) :
 * la première ligne, si présente, porte les identifiants provisionnés.
 */
@Repository
public interface AtelierAgentConfigRepository extends JpaRepository<AtelierAgentConfig, UUID> {

    /** Première configuration enregistrée (la plus ancienne), si le bootstrap a déjà eu lieu. */
    Optional<AtelierAgentConfig> findFirstByOrderByCreatedAtAsc();
}
