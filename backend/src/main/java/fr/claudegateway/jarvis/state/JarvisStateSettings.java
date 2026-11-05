package fr.claudegateway.jarvis.state;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
 * <b>Le réglage du registre pour un client</b> (F-247 / SF-247-02, D3) : <b>ligne absente = tout activé</b> (décision
 * PO). Couper un client arrête toute écriture et purge ses faits.
 */
@Entity
@Table(name = "jarvis_state_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JarvisStateSettings {

    public static final int DEFAULT_FACT_RETENTION_DAYS = 30;
    public static final int DEFAULT_CAPTURE_RETENTION_DAYS = 90;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Builder.Default
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** Les familles coupées ({@link JarvisStateObjectKind}), séparées par des virgules ; {@code null} = aucune. */
    @Column(name = "disabled_families", length = 300)
    private String disabledFamilies;

    @Builder.Default
    @Column(name = "extract_from_turns", nullable = false)
    private boolean extractFromTurns = true;

    @Builder.Default
    @Column(name = "notes_enabled", nullable = false)
    private boolean notesEnabled = true;

    @Builder.Default
    @Column(name = "releve_enabled", nullable = false)
    private boolean releveEnabled = true;

    @Builder.Default
    @Column(name = "captures_enabled", nullable = false)
    private boolean capturesEnabled = true;

    @Builder.Default
    @Column(name = "fact_retention_days", nullable = false)
    private int factRetentionDays = DEFAULT_FACT_RETENTION_DAYS;

    @Builder.Default
    @Column(name = "capture_retention_days", nullable = false)
    private int captureRetentionDays = DEFAULT_CAPTURE_RETENTION_DAYS;

    /** F-247 / SF-247-08 — la dernière mise en place du relevé (tour d'inventaire), {@code null} = jamais. */
    @Column(name = "releve_setup_at")
    private OffsetDateTime releveSetupAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Le réglage par défaut d'un client sans ligne : tout activé. */
    public static JarvisStateSettings defaults(UUID userId, UUID hostId) {
        return JarvisStateSettings.builder().userId(userId).hostId(hostId).build();
    }

    /** Les familles coupées, lues. */
    public Set<String> disabledFamilySet() {
        if (disabledFamilies == null || disabledFamilies.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(disabledFamilies.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Vrai si la famille de cet objet est admise. */
    public boolean familyAllowed(String objectKind) {
        return !disabledFamilySet().contains(objectKind);
    }
}
