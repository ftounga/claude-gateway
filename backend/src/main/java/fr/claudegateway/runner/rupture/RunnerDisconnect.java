package fr.claudegateway.runner.rupture;

import java.time.OffsetDateTime;
import java.util.UUID;

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
 * <b>Une rupture de canal, consignée</b> (F-161 / SF-161-03).
 *
 * <p><b>Cette table ne corrige rien.</b> Le cadrage F-161 §6 refuse explicitement de corriger la
 * cause des déconnexions : le runner bat toutes les 30 s, la gateway tolère 90 s — trois battements
 * de marge, ce n'est pas un réglage mal posé. Le poste a réellement disparu, et <b>on ne sait pas
 * pourquoi</b>. Deviner produirait un correctif qui ne corrige rien. Alors on mesure.</p>
 */
@Entity
@Table(name = "runner_disconnects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RunnerDisconnect {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** ISOLATION : toute lecture filtre dessus. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Enumerated(EnumType.STRING)
    @Column(name = "cause", nullable = false, length = 32, updatable = false)
    private RunnerDisconnectCause cause;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport", nullable = false, length = 16, updatable = false)
    private RunnerTransport transport;

    /** Durée de vie du canal, du premier contact à sa fermeture. */
    @Column(name = "lived_ms", updatable = false)
    private Long livedMs;

    /**
     * Temps écoulé depuis le dernier signe du poste. C'est <b>lui</b> qui dira si la marge de 90 s
     * est généreuse ou juste — la seule mesure qui puisse trancher, un jour, la question du réglage.
     */
    @Column(name = "silent_ms", updatable = false)
    private Long silentMs;

    /**
     * Le {@code CloseStatus} du WebSocket, aujourd'hui jeté dans un {@code log.debug}. Le
     * renseignement le plus précieux du lot : il sépare une coupure réseau d'un arrêt applicatif.
     * {@code null} en long-polling, qui n'en a pas.
     */
    @Column(name = "close_status", length = 120, updatable = false)
    private String closeStatus;

    /** Des appels étaient-ils <b>en vol</b> ? Le cas qui tue un tour, et le seul qui coûte. */
    @Column(name = "calls_in_flight", nullable = false, updatable = false)
    private int callsInFlight;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
