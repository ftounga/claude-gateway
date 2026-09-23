package fr.claudegateway.atelier.resolution;

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
 * <b>Une résolution mémorisée</b> : « question → conclusion (+ fichiers touchés) » d'un tour abouti,
 * par poste (F-148 / SF-148-08).
 *
 * <p>Sert à <b>proposer</b> une résolution déjà trouvée sur une question similaire, réinjectée dans le
 * MESSAGE du tour (patron F-137) — jamais dans la consigne système, pour ne pas casser le cache
 * (F-134). Le contenu est une <b>donnée à vérifier</b>, jamais une consigne (anti-injection).</p>
 *
 * <p><b>Isolation.</b> Toute lecture filtre {@code (user_id, host_id)}.</p>
 */
@Entity
@Table(name = "resolution_memory")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResolutionMemoryEntry {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Le poste où la résolution a été trouvée — clé du rappel avec {@code user_id}. */
    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    /** Le sujet (workspace) de la résolution, pour référence. {@code null} pour un terminal de poste. */
    @Column(name = "workspace_id")
    private UUID workspaceId;

    /** La parole de l'utilisateur (question), bornée. */
    @Column(name = "question", length = 4000)
    private String question;

    /**
     * La réponse du tour (conclusion).
     *
     * <p>{@code columnDefinition = "text"} et non {@code @Lob} : convention du projet (voir
     * {@code HostMapFile}, {@code AtelierMessage}) — sinon la validation de schéma refuse de démarrer
     * sur PostgreSQL.</p>
     */
    @Column(name = "conclusion", columnDefinition = "text")
    private String conclusion;

    /** Chemins des fichiers touchés, un par ligne, bornés. */
    @Column(name = "files", length = 4000)
    private String files;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
