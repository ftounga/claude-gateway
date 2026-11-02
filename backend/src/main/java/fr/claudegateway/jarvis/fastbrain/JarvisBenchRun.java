package fr.claudegateway.jarvis.fastbrain;

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

/** <b>Un passage du banc du cerveau rapide</b>, rangé en base (F-239 / SF-239-13) — lu sous {@code user_id}. */
@Entity
@Table(name = "jarvis_bench_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JarvisBenchRun {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** Le rapport (JSON) : verdicts sans la parole du compte. */
    @Column(name = "report_json", nullable = false, columnDefinition = "text")
    private String reportJson;
}
