package fr.claudegateway.runner.update;

import java.time.OffsetDateTime;
import java.util.Set;
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
 * Une <b>mise à jour du runner</b> d'un poste (F-111 / SF-111-04) : qui, quand, de → vers, où elle en
 * est, et comment elle a fini.
 */
@Entity
@Table(name = "runner_update_journal")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RunnerUpdateJournalEntry {

    /** Longueur maximale du détail (motif, activités en cours). */
    public static final int MAX_DETAIL_LENGTH = 500;

    /** Où en est une mise à jour. */
    public enum State {
        REQUESTED, DOWNLOADING, WAITING, RESTARTING, SUCCEEDED, FAILED, ROLLED_BACK;

        private static final Set<State> TERMINAL = Set.of(SUCCEEDED, FAILED, ROLLED_BACK);

        public boolean terminal() {
            return TERMINAL.contains(this);
        }
    }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire du poste — jamais l'ADMIN qui a déclenché. Filtre d'isolation. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    /** Qui a cliqué : le propriétaire, ou un ADMIN. */
    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "from_version", length = 64, updatable = false)
    private String fromVersion;

    @Column(name = "to_version", nullable = false, length = 64, updatable = false)
    private String toVersion;

    @Column(name = "forced", nullable = false)
    private boolean forced;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private State state;

    @Column(name = "detail", length = MAX_DETAIL_LENGTH)
    private String detail;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    /** Passe à un état, avec son détail (tronqué) ; un état terminal date la fin. */
    public void moveTo(State next, String newDetail, OffsetDateTime now) {
        this.state = next;
        this.detail = newDetail == null ? null
                : newDetail.length() > MAX_DETAIL_LENGTH ? newDetail.substring(0, MAX_DETAIL_LENGTH) : newDetail;
        this.updatedAt = now;
        if (next.terminal()) {
            this.finishedAt = now;
        }
    }
}
