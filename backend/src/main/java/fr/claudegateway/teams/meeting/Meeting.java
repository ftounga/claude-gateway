package fr.claudegateway.teams.meeting;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Un <b>artefact réunion</b> (F-128 / SF-128-01) : une réunion Teams rejointe et capturée depuis un
 * poste, rattachée éventuellement à un sujet du Radar.
 *
 * <p><b>Isolation.</b> {@link #userId} et {@link #hostId} ; aucune lecture n'existe sans les deux
 * (cadrage F-128 §6). {@link #subjectId} est un pointeur nullable vers un {@code radar_subjects} du
 * même couple (résolu à la lecture, sans clé étrangère).</p>
 */
@Entity
@Table(name = "meetings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Meeting {

    public static final int MAX_TITLE_LENGTH = 300;
    public static final int MAX_URL_LENGTH = 2048;
    public static final int MAX_CAPTURE_REF_LENGTH = 200;
    public static final int DEFAULT_RETENTION_DAYS = 30;
    public static final int MIN_RETENTION_DAYS = 1;
    public static final int MAX_RETENTION_DAYS = 365;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    /** Sujet du Radar que la réunion enrichit (pointeur nullable, même portée user_id+host_id). */
    @Column(name = "subject_id")
    private UUID subjectId;

    @Column(name = "title", length = MAX_TITLE_LENGTH)
    private String title;

    @Column(name = "meeting_url", nullable = false, length = MAX_URL_LENGTH)
    private String meetingUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private MeetingState state;

    /** L'utilisateur a déclaré avoir prévenu les participants (consentement léger, D3). */
    @Column(name = "consent_acknowledged", nullable = false)
    private boolean consentAcknowledged;

    /**
     * L'utilisateur est <b>réellement en réunion</b> (in-call) au moment du join (F-128 / SF-128-16),
     * constaté best-effort par le runner. L'UI n'active « Démarrer l'enregistrement » que si vrai ;
     * {@code false} si le join s'est arrêté au pré-join (plafond atteint sans signal in-call).
     */
    @Column(name = "in_call", nullable = false)
    @Builder.Default
    private boolean inCall = false;

    /** Durée de conservation en jours (défaut 30 ; purge active = SF-128-07). */
    @Column(name = "retention_days", nullable = false)
    private int retentionDays;

    /** Référence de capture rendue par le runner (identifiant d'onglet/session), si connue. */
    @Column(name = "capture_ref", length = MAX_CAPTURE_REF_LENGTH)
    private String captureRef;

    /** Clé de stockage objet de l'audio capturé (F-128 / SF-128-02), ou {@code null} tant qu'aucun audio. */
    @Column(name = "audio_key", length = 300)
    private String audioKey;

    /** Taille de l'audio capturé, en octets (F-128 / SF-128-02), ou {@code null}. */
    @Column(name = "audio_bytes")
    private Long audioBytes;

    /** Nombre d'images clés retenues du partage d'écran (F-128 / SF-128-03), ou {@code null}. */
    @Column(name = "image_count")
    private Integer imageCount;

    /** Le transcript horodaté (F-128 / SF-128-04), ou {@code null} tant qu'aucune transcription. */
    @Column(name = "transcript", columnDefinition = "text")
    private String transcript;

    /** État de la transcription (F-128 / SF-128-04). Jamais {@code null} (défaut {@code NONE}). */
    @Enumerated(EnumType.STRING)
    @Column(name = "transcript_status", nullable = false, length = 20)
    @Builder.Default
    private TranscriptStatus transcriptStatus = TranscriptStatus.NONE;

    /** Langue détectée du transcript (verbose_json), ou {@code null}. */
    @Column(name = "transcript_lang", length = 20)
    private String transcriptLang;

    /** Message nommé du dernier échec de transcription (F-128 / SF-128-04), ou {@code null}. */
    @Column(name = "transcript_error", length = 500)
    private String transcriptError;

    /**
     * Instant où les <b>médias lourds</b> (audio + images) ont été purgés au-delà de {@link #retentionDays}
     * (F-128 / SF-128-07), ou {@code null} tant qu'aucune purge. Une réunion horodatée n'est plus
     * candidate (idempotence) ; l'artefact et le transcript, eux, sont conservés.
     */
    @Column(name = "media_purged_at")
    private OffsetDateTime mediaPurgedAt;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
