package fr.claudegateway.atelier.proposal;

import java.time.OffsetDateTime;
import java.util.UUID;

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
 * <b>Une règle de tous mes postes</b> (F-266 / SF-266-01) : une règle de gouvernance rangée côté
 * serveur pour un compte, injectée à chaque tour de cet utilisateur, quel que soit le poste. Une règle
 * de poste ou de sujet de même titre l'emporte.
 */
@Entity
@Table(name = "account_governance_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountGovernanceRule {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Le titre tel qu'écrit : la section {@code ## titre}. */
    @Column(name = "title", nullable = false, length = 120)
    private String title;

    /** Le titre normalisé (minuscules, espaces réduits) : unicité et ordre stable. */
    @Column(name = "title_key", nullable = false, length = 120, updatable = false)
    private String titleKey;

    @Column(name = "content", nullable = false)
    private String content;

    /** Le poste d'où la règle a été recopiée, le cas échéant (trace). */
    @Column(name = "source_host_id")
    private UUID sourceHostId;

    /** La proposition d'où elle vient, le cas échéant (trace). */
    @Column(name = "source_proposal_id")
    private UUID sourceProposalId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
