package fr.claudegateway.radar.sync;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
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
 * Les réglages Radar <b>d'un poste</b> (F-100) : la vérification guidée (SF-100-01) et, depuis
 * SF-100-02, la planification de la synchro du soir. Une ligne par poste, {@code user_id} +
 * {@code host_id}.
 */
@Entity
@Table(name = "radar_host_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadarHostSettings {

    /** Borne du JSON de vérification. */
    public static final int MAX_VERIFICATION_CHARS = 8_000;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    /** JSON des quatre cases ; {@code null} tant qu'aucune vérification n'a été faite. */
    @Column(name = "verification", length = MAX_VERIFICATION_CHARS)
    private String verification;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    // ------------------------------------------------------------ planification (SF-100-02)

    /** Le Radar synchronise ce poste tous les soirs. */
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = false;

    /** Instant où l'utilisateur a confirmé que son client autorise le Radar (§14). */
    @Column(name = "client_authorized_at")
    private OffsetDateTime clientAuthorizedAt;

    /** Heure de la synchro, {@code HH:mm}, dans {@link #timeZone}. */
    @Column(name = "sync_time", nullable = false, length = 5)
    @Builder.Default
    private String syncTime = "22:00";

    /** Fuseau du poste (identifiant IANA). */
    @Column(name = "time_zone", nullable = false, length = 64)
    @Builder.Default
    private String timeZone = "Europe/Paris";

    /** Dernier créneau traité : date locale du poste. */
    @Column(name = "last_slot_date")
    private LocalDate lastSlotDate;

    /** Créneau manqué (runner muet), en attente de rattrapage. */
    @Column(name = "missed_slot_at")
    private OffsetDateTime missedSlotAt;

    /** Le verrou : la synchro en cours sur ce poste, ou {@code null}. */
    @Column(name = "running_sync_id")
    private UUID runningSyncId;

    // ------------------------------------------------------------ le résumé du matin par courriel (SF-110-04)

    /** L'utilisateur reçoit le résumé du matin de ce client par courriel, après la synchro du soir. */
    @Column(name = "morning_email", nullable = false)
    @Builder.Default
    private boolean morningEmail = false;

    /** La dernière synchro du soir traitée pour le résumé (mis en file ou écarté) ; marqueur conditionnel. */
    @Column(name = "morning_email_sync_id")
    private UUID morningEmailSyncId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
