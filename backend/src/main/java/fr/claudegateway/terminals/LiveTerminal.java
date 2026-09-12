package fr.claudegateway.terminals;

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
 * Une <b>place de terminal vivant</b> (F-70 / SF-70-01) : un onglet de terminal ouvert, qui tient sa
 * place en la renouvelant.
 *
 * <p><b>Pourquoi en base et pas en mémoire.</b> Le plafond de quatre est un garde-fou de dépense :
 * il doit valoir pour l'utilisateur, pas pour le pod qui a répondu. Sous HPA, un registre en mémoire
 * compterait les terminaux d'un replica et laisserait passer ceux des autres. Une ligne en base est
 * vue par tous les pods, survit à un redémarrage, et se purge d'elle-même.</p>
 *
 * <p><b>Pourquoi un {@link #sessionId} fourni par l'écran.</b> Un rechargement de page doit
 * retrouver <b>sa</b> place plutôt qu'en consommer une seconde, et un onglet dupliqué doit en
 * prendre une nouvelle. Seul le navigateur sait distinguer les deux : l'identifiant vit dans son
 * {@code sessionStorage}. Il n'est jamais un secret — il ne donne accès à rien, il est toujours lu
 * <b>avec</b> le {@code user_id} du jeton.</p>
 *
 * <p><b>Pourquoi un {@link #lastSeenAt} plutôt qu'une fermeture propre.</b> Un onglet fermé
 * brutalement, un navigateur tué, un portable qui se referme : aucune libération n'arrive. Une place
 * qu'on ne renouvelle plus expire, et le plafond ne se transforme jamais en blocage permanent.</p>
 */
@Entity
@Table(name = "live_terminals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LiveTerminal {

    /** Longueur maximale de l'identifiant d'onglet accepté. */
    public static final int MAX_SESSION_ID_LENGTH = 64;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire (= {@code users.id}). Racine de l'isolation : aucune lecture sans ce filtre. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /**
     * Projet ouvert dans cet onglet. Modifiable : un onglet qui change de projet garde sa place
     * plutôt que d'en libérer une pour en reprendre une autre dans la seconde.
     */
    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    /** Identifiant d'onglet, unique par utilisateur. */
    @Column(name = "session_id", nullable = false, length = MAX_SESSION_ID_LENGTH, updatable = false)
    private String sessionId;

    /** Prise de la place. Sert à départager qui reste quand deux prises se croisent. */
    @Column(name = "opened_at", nullable = false, updatable = false)
    private OffsetDateTime openedAt;

    /** Dernier battement de cœur. Au-delà du délai de grâce, la place n'est plus vivante. */
    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    /**
     * <b>Ce que ce terminal fait à l'instant</b> (F-76 / SF-76-01). {@code null} tant qu'aucun
     * relevé n'est arrivé — un onglet qui vient de s'ouvrir n'a encore rien fait, et {@code IDLE}
     * serait déjà une affirmation.
     *
     * <p>Stocké en clair plutôt qu'en ordinal : une colonne qu'on lit en production doit se lire
     * sans table de correspondance, et un ordinal se décale au premier ajout dans l'énumération.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "activity", length = 24)
    private TerminalActivity activity;

    /**
     * Ce qui est en cours, en clair : « npm test ». Borné et nettoyé <b>au serveur</b>
     * ({@link TerminalPreviewSanitizer}) — une borne tenue par l'appelant n'est pas une borne.
     */
    @Column(name = "activity_detail", length = 120)
    private String activityDetail;

    /**
     * Les dernières lignes du terminal, séparées par des sauts de ligne. Un document d'affichage,
     * jamais un critère de lecture : aucune requête ne filtre dessus, d'où l'absence d'index.
     *
     * <p><b>Ce n'est pas l'historique</b> : la fiche porte le <b>dernier</b> aperçu, elle
     * n'accumule pas. Ce qu'un tour a réellement produit vit dans {@code atelier_messages}.</p>
     */
    @Column(name = "preview_lines", length = 1024)
    private String previewLines;

    /**
     * Instant du relevé. C'est lui qui <b>départage deux onglets</b> ouverts sur le même projet
     * quand un écran parle du projet et non de l'onglet.
     */
    @Column(name = "activity_at")
    private OffsetDateTime activityAt;
}
