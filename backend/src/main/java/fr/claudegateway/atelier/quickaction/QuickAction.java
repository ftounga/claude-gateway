package fr.claudegateway.atelier.quickaction;

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
 * <b>Une action rapide personnalisée</b> (F-275 / SF-275-01, D3) : une phrase de démarrage lancée d'un clic depuis le
 * bouton « Actions rapides » du terminal, éventuellement dans un autre sujet. Visible selon sa portée : COMPTE
 * partout, POSTE dans les terminaux de {@link #hostId}, SUJET dans le terminal {@link #workspaceId}.
 */
@Entity
@Table(name = "quick_actions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuickAction {

    /** Portées. */
    public static final String SUJET = "SUJET";
    public static final String POSTE = "POSTE";
    public static final String COMPTE = "COMPTE";

    /** Gestes du clic. */
    public static final String DEPOSER = "DEPOSER";
    public static final String ENVOYER = "ENVOYER";

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "scope", nullable = false, length = 10)
    private String scope;

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "host_id")
    private UUID hostId;

    @Column(name = "label", nullable = false, length = 60)
    private String label;

    /** Le libellé normalisé (minuscules, espaces réduits) : unicité parmi les actions visibles ensemble. */
    @Column(name = "label_key", nullable = false, length = 60)
    private String labelKey;

    @Column(name = "phrase", nullable = false, length = 2000)
    private String phrase;

    /** Le sujet où lancer la phrase ; {@code null} = le terminal où l'on clique. */
    @Column(name = "target_workspace_id")
    private UUID targetWorkspaceId;

    @Column(name = "click_mode", nullable = false, length = 10)
    private String clickMode;

    /** Jours ISO (1 = lundi) de la suggestion, séparés par des virgules ; {@code null} = pas de suggestion. */
    @Column(name = "suggest_days", length = 20)
    private String suggestDays;

    @Column(name = "suggest_moment", length = 12)
    private String suggestMoment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
