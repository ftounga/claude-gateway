package fr.claudegateway.governance.map.index;

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
 * <b>Une consultation de la carte par la gateway</b> (F-174 / SF-174-01).
 *
 * <p>Des compteurs, jamais du contenu : ni la question, ni les faits. C'est la mesure qui dira si
 * l'index de la carte (SF-174-02 et suivantes) apporte ce qu'il promet.</p>
 *
 * <p><b>Isolation</b> : {@code user_id} + {@code host_id} sur chaque ligne.</p>
 */
@Entity
@Table(name = "host_map_lookups")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostMapLookup {

    /** Les faits joints au message d'un tour. */
    public static final String KIND_TURN = "TURN";
    /** Un appel de l'outil serveur {@code carte_chercher}. */
    public static final String KIND_TOOL = "TOOL";

    /** Rien n'a été joint (aucun terme, aucun fait). */
    public static final String STRATEGY_NONE = "NONE";
    /** La recherche par mots-clés de F-137. */
    public static final String STRATEGY_LEXICAL = "LEXICAL";
    /** La recherche hybride sur l'index (F-174 / SF-174-03). */
    public static final String STRATEGY_HYBRID = "HYBRID";

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "workspace_id", updatable = false)
    private UUID workspaceId;

    @Column(name = "kind", nullable = false, length = 16, updatable = false)
    private String kind;

    @Column(name = "strategy", nullable = false, length = 16, updatable = false)
    private String strategy;

    @Column(name = "facts_count", nullable = false, updatable = false)
    private int factsCount;

    @Column(name = "chars", nullable = false, updatable = false)
    private int chars;

    @Column(name = "pitfalls_count", nullable = false, updatable = false)
    private int pitfallsCount;

    @Column(name = "deadlines_count", nullable = false, updatable = false)
    private int deadlinesCount;

    @Column(name = "sources", length = 1000, updatable = false)
    private String sources;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
