package fr.claudegateway.jarvis.trace;

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
 * <b>Un événement du journal de l'écoute</b> (F-233 / SF-233-15) : une transition ou une erreur de la boucle d'écoute
 * mains libres de l'écran {@code /jarvis}. <b>Aucun contenu</b> : ni texte dit ou entendu, ni audio.
 */
@Entity
@Table(name = "jarvis_listen_traces")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JarvisListenTraceEvent {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire : toute lecture filtre dessus. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** L'ouverture de page (aléatoire, côté écran). */
    @Column(name = "page_id", nullable = false, length = 36)
    private String pageId;

    /** L'empreinte du {@code main-*.js} servi. */
    @Column(name = "page_version", length = 24)
    private String pageVersion;

    @Builder.Default
    @Column(name = "companion", nullable = false)
    private boolean companion = false;

    @Column(name = "seq", nullable = false)
    private int seq;

    /** Millisecondes depuis l'ouverture de la page. */
    @Column(name = "at_ms", nullable = false)
    private long atMs;

    @Column(name = "event", nullable = false, length = 40)
    private String event;

    @Column(name = "state", length = 16)
    private String state;

    @Column(name = "mode", length = 16)
    private String mode;

    @Column(name = "detail", length = 120)
    private String detail;

    @Builder.Default
    @Column(name = "repeat_count", nullable = false)
    private int repeatCount = 1;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
