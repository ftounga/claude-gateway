package fr.claudegateway.bilan;

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
 * <b>Un bilan de session, gardé</b> (F-155 / SF-155-04).
 *
 * <p>Un bilan qui n'existe que dans une réponse HTTP est un bilan qu'on ne relit jamais et qu'on ne
 * peut pas comparer. Or <b>comparer est tout l'intérêt</b> : « le cache est remonté depuis la
 * semaine dernière ».</p>
 *
 * <p><b>Deux colonnes JSON, cinq colonnes de tête.</b> Le relevé et les suggestions sont des
 * photographies d'un instant, pas des données à interroger. Les cinq chiffres que la liste trie et
 * compare sont en colonnes — l'inverse obligerait à désérialiser la base entière pour afficher une
 * liste.</p>
 */
@Entity
@Table(name = "session_bilans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SessionBilan {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    /**
     * Le nom du projet, <b>figé</b> au moment du bilan : renommer un projet ne doit pas rendre un
     * ancien bilan illisible, et le projet peut disparaître.
     */
    @Column(name = "workspace_name", length = 255)
    private String workspaceName;

    @Column(name = "from_at", nullable = false, updatable = false)
    private OffsetDateTime fromAt;

    @Column(name = "to_at", nullable = false, updatable = false)
    private OffsetDateTime toAt;

    /** {@code AUTOMATIQUE} (seuil atteint) ou {@code MANUEL} (demandé d'un clic). */
    @Column(name = "origin", nullable = false, length = 16)
    private String origin;

    @Column(name = "turns", nullable = false)
    private int turns;

    @Column(name = "cost_eur", nullable = false, precision = 12, scale = 2)
    private BigDecimal costEur;

    @Column(name = "cache_share", nullable = false)
    private int cacheShare;

    @Column(name = "suggestion_count", nullable = false)
    private int suggestionCount;

    /** Combien de suggestions ont été écartées faute d'impact — le dire vaut mieux que les diluer. */
    @Column(name = "discarded_count", nullable = false)
    private int discardedCount;

    /** La photographie du relevé. {@code columnDefinition = "text"} : convention du projet. */
    @Column(name = "ledger_json", columnDefinition = "text")
    private String ledgerJson;

    /** La photographie des suggestions retenues. */
    @Column(name = "suggestions_json", columnDefinition = "text")
    private String suggestionsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
