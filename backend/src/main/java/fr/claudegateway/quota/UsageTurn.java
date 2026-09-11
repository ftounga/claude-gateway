package fr.claudegateway.quota;

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
 * Relevé de consommation d'un <b>tour</b> facturé (F-61 / SF-61-01) : combien de tokens d'entrée et
 * de sortie, pour quel projet, sous quel poste, et quand.
 *
 * <p><b>Pourquoi cette table existe</b> : {@code workspaces.agent_input_tokens} (migration 040) est
 * un <b>repère de delta remis à zéro à chaque ouverture de session</b> — l'agréger ferait
 * <b>rétrécir</b> les totaux, et un consultant verrait la consommation d'un client baisser toute
 * seule. {@link UsageCounter} (F-10) est monotone mais n'a aucune dimension projet. Ce journal est
 * la seule forme dont on puisse prouver qu'elle ne rétrécit pas <b>et</b> qu'elle sait de quel
 * client elle parle.</p>
 *
 * <p><b>Ce qu'il ne porte pas</b> : aucun texte. Ni message, ni commande, ni chemin, ni nom. Les
 * écrans de F-61 montrent des volumes et des coûts, jamais des contenus — et cette garantie est
 * tenue par la <b>structure</b> de la table, pas par la prudence des requêtes.</p>
 *
 * <p><b>Append-only</b> : une ligne écrite n'est jamais modifiée. Seule la suppression de compte
 * (F-11) les efface, par {@code user_id}.</p>
 */
@Entity
@Table(name = "usage_turns")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UsageTurn {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire (= {@code users.id}). Racine de l'isolation : toute lecture filtre dessus. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /**
     * Projet du tour (= {@code workspaces.id}), ou {@code null} pour un tour hors projet
     * ({@code /chat}, {@code /ask}). Volontairement <b>sans clé étrangère</b> : une pièce de
     * refacturation ne disparaît pas parce qu'on a rangé un projet.
     */
    @Column(name = "workspace_id", updatable = false)
    private UUID workspaceId;

    /**
     * Poste du tour (= {@code runner_hosts.id}), <b>tel qu'il était au moment du tour</b>, ou
     * {@code null} si le projet n'était rattaché à aucun poste.
     *
     * <p>C'est un <b>instantané</b>, pas une jointure différée (arbitrage A-2 du cadrage) :
     * déplacer un projet d'un poste à l'autre ne doit pas déplacer rétroactivement une dépense
     * déjà refacturée à un client.</p>
     */
    @Column(name = "host_id", updatable = false)
    private UUID hostId;

    /** Tokens d'entrée du tour. Jamais négatif. */
    @Column(name = "input_tokens", nullable = false, updatable = false)
    @Builder.Default
    private long inputTokens = 0L;

    /**
     * Tokens de sortie du tour. Jamais négatif, et <b>jamais confondus avec l'entrée</b> : leurs
     * coûts unitaires n'ont rien à voir (5 €/M contre 25 €/M au tarif configuré).
     */
    @Column(name = "output_tokens", nullable = false, updatable = false)
    @Builder.Default
    private long outputTokens = 0L;

    /** Instant du tour (horloge applicative). Posé à l'écriture, jamais fourni par un client. */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    /** Total de tokens du tour (entrée + sortie). Commodité d'affichage, jamais de tarification. */
    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
