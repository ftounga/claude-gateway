package fr.claudegateway.atelier.agent;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Configuration Managed Agents de la plateforme (F-28 / Phase 2, ADR-013) : identifiants de
 * l'environnement et de l'agent provisionnés une seule fois puis réutilisés (« créé une fois »).
 * Config <b>globale à la plateforme</b> (un environment/agent partagés) : pas de {@code user_id} ici
 * — l'isolation par utilisateur se fera sur la session (SF-28-09).
 */
@Entity
@Table(name = "atelier_agent_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AtelierAgentConfig {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "environment_id", nullable = false, updatable = false)
    private String environmentId;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private String agentId;

    @Column(name = "agent_version", nullable = false, updatable = false)
    private String agentVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
