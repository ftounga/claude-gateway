package fr.claudegateway.pages;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
import lombok.Setter;

/**
 * <b>Une page</b> (F-109 / SF-109-01) : un document HTML rendu par l'agent, rangé et servi par la
 * gateway. Son contenu vit dans le stockage objet ; la base ne porte que ce qu'il faut pour la lister,
 * la versionner et la retrouver. {@link #userId} est la racine de l'isolation.
 */
@Entity
@Table(name = "pages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Page {

    /** Tiré par le service avant l'écriture : la clé de stockage en dépend. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "space", nullable = false, length = 8, updatable = false)
    private PageSpace space;

    @Column(name = "host_id", updatable = false)
    private UUID hostId;

    @Column(name = "workspace_id", updatable = false)
    private UUID workspaceId;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "description", length = 300)
    private String description;

    @Column(name = "current_version", nullable = false)
    private int currentVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
