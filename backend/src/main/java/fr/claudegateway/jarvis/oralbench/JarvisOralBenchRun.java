package fr.claudegateway.jarvis.oralbench;

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

/** <b>Un passage du banc de la parole</b>, rangé en base (F-240 / SF-240-02, D12) — lu sous {@code user_id}. */
@Entity
@Table(name = "jarvis_oral_bench_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JarvisOralBenchRun {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** Le rapport (JSON) : longueurs, verdicts, coût ; le jeu est fictif. */
    @Column(name = "report_json", nullable = false, columnDefinition = "text")
    private String reportJson;
}
