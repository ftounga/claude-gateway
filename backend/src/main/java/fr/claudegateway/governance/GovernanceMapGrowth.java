package fr.claudegateway.governance;

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
 * <b>Ce qu'un fichier de carte a gagné</b>, d'une lecture à l'autre (F-93 / SF-93-02).
 *
 * <p>SF-92-02 compte les faits d'une carte et l'écran les affiche. Un compteur ne montre pourtant
 * <b>aucune augmentation</b> : il dit 16 aujourd'hui et 16 demain, et rien ne distingue une carte qui
 * grossit d'une carte morte. Ce qui manquait est la <b>mémoire de la lecture précédente</b> — sans
 * elle, la promotion reste une corvée invisible.</p>
 *
 * <p><b>Une ligne par fichier, jamais un journal.</b> La question tient en une phrase — « qu'est-ce
 * que la carte a gagné, et quand ? » — et un journal d'observations grossirait sans fin pour la même
 * réponse. {@link #firstFacts} / {@link #firstSeenAt} donnent le point de départ, {@link #facts} /
 * {@link #observedAt} l'état courant, {@link #lastGain} / {@link #lastGainAt} le dernier gain.</p>
 *
 * <p><b>On constate, on ne croit pas sur parole.</b> Ce qui est retenu ici est le <b>delta
 * observé</b> du nombre de faits, jamais ce qu'un modèle a déclaré avoir promu : le cadrage a déjà
 * jugé l'auto-déclaration insuffisante — un modèle qui oublie de promouvoir oubliera de le déclarer.
 * Le delta, lui, vaut quelle que soit la main qui a écrit : un tour d'agent, le terminal du poste, ou
 * l'utilisateur dans son éditeur.</p>
 *
 * <p><b>Isolation.</b> {@link #userId} est en tête de l'index d'unicité
 * {@code (user_id, host_id, path)}, et aucune lecture n'existe sans lui.</p>
 */
@Entity
@Table(name = "governance_map_growth")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GovernanceMapGrowth {

    /** Borne du chemin, alignée sur celle des fichiers de paquet : même chemin, même borne. */
    public static final int MAX_PATH_LENGTH = 512;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire (= {@code users.id}). Filtre d'isolation obligatoire. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Poste observé (= {@code runner_hosts.id}), ou la clé réservée du poste « Hébergé ». */
    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    /** Chemin du fichier de carte, relatif à la racine du poste. */
    @Column(name = "path", nullable = false, updatable = false, length = MAX_PATH_LENGTH)
    private String path;

    /** Nombre de faits à la <b>dernière</b> lecture complète. */
    @Column(name = "facts", nullable = false)
    private int facts;

    /** Instant de cette dernière lecture. */
    @Column(name = "observed_at", nullable = false)
    private OffsetDateTime observedAt;

    /**
     * Nombre de faits à la <b>première</b> observation — la référence.
     *
     * <p>Elle existe pour que le produit ne présente jamais comme un « gain » ce qu'il a simplement
     * découvert : une carte déjà pleine le premier jour n'a rien gagné, elle était là.</p>
     */
    @Column(name = "first_facts", nullable = false)
    private int firstFacts;

    /** Instant de cette première observation. */
    @Column(name = "first_seen_at", nullable = false)
    private OffsetDateTime firstSeenAt;

    /** Dernier gain constaté, ou {@code null} si ce fichier n'a jamais gagné. */
    @Column(name = "last_gain")
    private Integer lastGain;

    /** Instant de ce dernier gain, ou {@code null}. */
    @Column(name = "last_gain_at")
    private OffsetDateTime lastGainAt;
}
