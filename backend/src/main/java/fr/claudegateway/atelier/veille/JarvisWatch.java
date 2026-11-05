package fr.claudegateway.atelier.veille;

import java.math.BigDecimal;
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
 * <b>Une veille Jarvis</b> (F-230 / SF-230-01, D1) : une sonde en lecture seule, écrite une fois par l'agent
 * sur le poste, lancée aux heures données par le PO, <b>du lundi au vendredi</b> (imposé : aucune colonne de
 * jours). Une veille n'est pas une routine (D7) : elle ne lance jamais un tour d'agent.
 *
 * <p><b>Isolation.</b> Toute lecture et toute écriture filtrent {@code (user_id, workspace_id)} ; une veille
 * d'un autre compte est introuvable (404).</p>
 *
 * <p><b>Ce qui tourne est ce que le PO a validé</b> (D5) : la commande est relue au test, et le script de sonde
 * est épinglé par son empreinte — modifié, il ne tourne plus.</p>
 */
@Entity
@Table(name = "jarvis_watches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JarvisWatch {

    /** Origine : posée depuis l'écran. */
    public static final String ORIGIN_USER = "USER";
    /** Origine : proposée par l'agent (outil {@code veille_poser}). */
    public static final String ORIGIN_AGENT = "AGENT";

    /** F-247 / SF-247-07 — une veille d'alerte (F-230) : diff, rédaction, notification. */
    public static final String PURPOSE_ALERT = "ALERT";
    /** F-247 / SF-247-07 — un relevé d'état : l'instantané devient des faits, sans alerte ni IA, en basse priorité. */
    public static final String PURPOSE_STATE = "STATE";

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    /** Le chantier du sujet que la veille suit ; {@code null} = le fil commun du sujet. */
    @Column(name = "chantier_number")
    private Integer chantierNumber;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    /** La plateforme, en clair (« Jira », « Monday »…). */
    @Column(name = "platform", nullable = false, length = 40)
    private String platform;

    /** Le périmètre, en clair : ce que la sonde regarde, ce qu'elle ignore. */
    @Column(name = "scope", nullable = false, length = 500)
    private String scope;

    /** La ligne lancée sur le poste. Jamais de secret en clair (contrôle D5 à l'enregistrement). */
    @Column(name = "probe_command", nullable = false, length = 2000)
    private String probeCommand;

    /** Le script de sonde appelé par la commande, relatif au projet ; {@code null} si la commande se suffit. */
    @Column(name = "probe_script_path", length = 300)
    private String probeScriptPath;

    /** Empreinte SHA-256 du script de sonde relu à la proposition. */
    @Column(name = "probe_script_hash", length = 64)
    private String probeScriptHash;

    /** {@code HH:mm} séparées par des virgules, triées. */
    @Column(name = "run_times", nullable = false, length = 64)
    private String runTimes;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = false;

    @Builder.Default
    @Column(name = "proposed", nullable = false)
    private boolean proposed = true;

    @Column(name = "origin", nullable = false, length = 16)
    private String origin;

    /** Dernier test réussi devant le PO, depuis la dernière proposition ; {@code null} = jamais testée. */
    @Column(name = "tested_at")
    private OffsetDateTime testedAt;

    @Column(name = "test_item_count")
    private Integer testItemCount;

    @Column(name = "test_summary", length = 500)
    private String testSummary;

    /** Dernier créneau consommé (SF-230-02) : verrou entre pods. */
    @Column(name = "last_slot_at")
    private OffsetDateTime lastSlotAt;

    /** Créneau déjà noté manqué, poste hors ligne (SF-230-02). */
    @Column(name = "missed_slot_at")
    private OffsetDateTime missedSlotAt;

    /** Échecs de sonde d'affilée (SF-230-02, D6). */
    @Builder.Default
    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures = 0;

    /** En panne : deux échecs d'affilée (SF-230-02, D6). */
    @Builder.Default
    @Column(name = "broken", nullable = false)
    private boolean broken = false;

    @Column(name = "last_run_at")
    private OffsetDateTime lastRunAt;

    @Column(name = "last_run_status", length = 24)
    private String lastRunStatus;

    /** Budget mensuel de la rédaction IA de cette veille, en dollars (SF-230-03) — saisi et affiché en euros. */
    @Builder.Default
    @Column(name = "monthly_budget_usd", nullable = false, precision = 10, scale = 2)
    private BigDecimal monthlyBudgetUsd = JarvisWatchService.DEFAULT_MONTHLY_BUDGET_USD;

    /** F-247 / SF-247-07 — {@link #PURPOSE_ALERT} (défaut) ou {@link #PURPOSE_STATE}. */
    @Builder.Default
    @Column(name = "purpose", nullable = false, length = 8)
    private String purpose = PURPOSE_ALERT;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Vrai si le planificateur doit l'examiner : active et validée. */
    public boolean isScheduled() {
        return active && !proposed;
    }

    /** F-247 / SF-247-07 — un relevé d'état (pas une veille d'alerte). */
    public boolean isState() {
        return PURPOSE_STATE.equals(purpose);
    }
}
