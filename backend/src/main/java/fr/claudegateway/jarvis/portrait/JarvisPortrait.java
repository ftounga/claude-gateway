package fr.claudegateway.jarvis.portrait;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <b>Le portrait d'un compte</b> (F-241 / SF-241-01) — une ligne par compte, clé {@code user_id}. Les sections sont
 * un JSON <b>chiffré</b> ({@link PortraitSeal}) : rien de lisible en base.
 */
@Entity
@Table(name = "jarvis_portraits")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JarvisPortrait {

    /** Propriétaire et clé : toute lecture et toute écriture filtrent dessus. */
    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Le JSON des sections (texte et date par section), scellé ; {@code null} = aucune section écrite. */
    @Column(name = "sections_enc", length = 32000)
    private String sectionsEnc;

    /** D20 — « Jarvis se sert de mon portrait » (vrai par défaut). */
    @Builder.Default
    @Column(name = "portrait_enabled", nullable = false)
    private boolean portraitEnabled = true;

    /** Incrémentée à chaque écriture du portrait ou d'un souvenir : la clé de rechargement du bloc. */
    @Builder.Default
    @Column(name = "version", nullable = false)
    private long version = 1L;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "last_interview_at")
    private OffsetDateTime lastInterviewAt;

    @Column(name = "last_checkin_at")
    private OffsetDateTime lastCheckinAt;

    @Column(name = "interview_offered_at")
    private OffsetDateTime interviewOfferedAt;

    @Builder.Default
    @Column(name = "interview_offers", nullable = false)
    private int interviewOffers = 0;
}
