package fr.claudegateway.pages;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** <b>Une ligne du journal d'une page</b> (F-109 / SF-109-05). */
@Entity
@Table(name = "page_events")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PageEvent {

    /** Ce qui est arrivé à la page. */
    public enum Kind {
        /** Publication de la version 1. */
        CREATED,
        /** Publication d'une nouvelle version. */
        VERSION,
        /** Création d'un lien de partage. */
        SHARED,
        /** Ouverture d'un lien de partage — sans rien du visiteur. */
        OPENED,
        /** Révocation d'un lien de partage. */
        REVOKED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "page_id", nullable = false, updatable = false)
    private UUID pageId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "share_id", updatable = false)
    private UUID shareId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16, updatable = false)
    private Kind kind;

    @Column(name = "version", updatable = false)
    private Integer version;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;
}
