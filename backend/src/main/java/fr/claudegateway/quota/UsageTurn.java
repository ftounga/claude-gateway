package fr.claudegateway.quota;

import java.math.BigDecimal;
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

    /**
     * Tokens servis <b>depuis</b> le cache (F-133 / SF-133-01). Compris dans {@link #inputTokens}
     * jusqu'à F-133 ; désormais à part, parce qu'ils coûtent un <b>dixième</b> de l'entrée et que
     * les confondre surestimait la dépense d'un ordre de grandeur en usage agentique.
     */
    @Column(name = "cache_read_tokens", nullable = false, updatable = false)
    @Builder.Default
    private long cacheReadTokens = 0L;

    /** Tokens <b>écrits</b> dans le cache : deux fois l'entrée au TTL d'une heure, celui que pose la boucle. */
    @Column(name = "cache_write_tokens", nullable = false, updatable = false)
    @Builder.Default
    private long cacheWriteTokens = 0L;

    /**
     * Ce que le tour a <b>réellement coûté</b>, en dollars (F-133 / SF-133-01).
     *
     * <p>C'est la colonne qui manquait : le coût était calculé à chaque tour puis jeté
     * ({@code QuotaService:204}), si bien qu'aucune requête ne pouvait le reconstituer après coup —
     * ni le cache, ni le modèle n'étaient conservés. Les lignes antérieures à la migration 118 la
     * laissent à {@code null} : leurs données n'existent pas, et les inventer serait pire que de
     * les manquer.</p>
     */
    @Column(name = "provider_cost_usd", precision = 12, scale = 6, updatable = false)
    private BigDecimal providerCostUsd;

    /**
     * D'où vient le montant : {@code PROVIDER} quand le fournisseur l'a rapporté lui-même,
     * {@code CALCULATED} quand il a été reconstitué des tokens. Porté avec le montant, jamais
     * déduit après coup — c'est cette distinction qui dira où chercher un écart avec la facture.
     *
     * <p><b>Un énuméré, pas une chaîne</b> : la table n'accueille que des valeurs qu'on a nommées.
     * Une colonne de texte de plus serait une porte de plus par où un contenu pourrait entrer, et
     * la garantie de F-61 est tenue par la structure, pas par la prudence des appelants.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "cost_source", length = 16, updatable = false)
    private TurnCost.Source costSource;

    /** Modèle servi, tel que rapporté par le fournisseur. {@code null} s'il ne l'a pas dit. */
    @Column(name = "model", length = 64, updatable = false)
    private String model;

    /**
     * Date du relevé de la grille de tarifs ayant servi au calcul (ex. {@code 2026-09-20}). Les
     * prix changent ; sans cette colonne, un montant ancien deviendrait inexplicable.
     */
    @Column(name = "pricing_version", length = 32, updatable = false)
    private String pricingVersion;

    /** Vrai si le modèle était absent de la grille et que les tarifs de repli ont servi : montant approché. */
    @Column(name = "pricing_fallback", nullable = false, updatable = false)
    @Builder.Default
    private boolean pricingFallback = false;

    /**
     * Recherches web facturées sur le tour (F-133 / SF-133-08), à 10 $ les mille, <b>en plus</b>
     * des tokens qu'elles rapportent. Le compteur est conservé à côté du montant pour qu'un coût
     * élevé puisse être <b>expliqué</b>, et pas seulement constaté.
     */
    @Column(name = "web_search_requests", nullable = false, updatable = false)
    @Builder.Default
    private long webSearchRequests = 0L;

    /** Secondes de session {@code running} imputées au tour (0,08 $/heure chez le fournisseur). */
    @Column(name = "sandbox_seconds", nullable = false, updatable = false)
    @Builder.Default
    private long sandboxSeconds = 0L;

    /** Instant du tour (horloge applicative). Posé à l'écriture, jamais fourni par un client. */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    /**
     * Total de tokens du tour (entrée + sortie). Commodité d'affichage, jamais de tarification.
     *
     * <p>{@link #inputTokens} porte toujours le volume d'entrée <b>traité</b>, cache compris : les
     * colonnes de cache ajoutées en F-133 le <b>ventilent</b>, elles ne s'y ajoutent pas. Sans quoi
     * le rapport d'usage (F-16) et la consommation par client (F-61) compteraient deux fois.</p>
     */
    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
