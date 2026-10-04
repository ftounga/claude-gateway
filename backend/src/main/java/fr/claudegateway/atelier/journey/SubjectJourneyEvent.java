package fr.claudegateway.atelier.journey;

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
 * <b>Un geste du parcours</b> (F-176) : mode changé, porte qui bloque, plan validé, retour en
 * investigation… Le journal nourrit la mesure (SF-176-06). Jamais de contenu de commande ni de clé :
 * un {@code detail} court et lisible.
 */
@Entity
@Table(name = "subject_journey_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubjectJourneyEvent {

    /** Le mode du sujet a changé ({@code detail} = qui l'a changé : USER). */
    public static final String MODE_CHANGED = "MODE_CHANGED";

    /** L'agent a proposé le mode guidé (SF-176-02). */
    public static final String GUIDED_PROPOSED = "GUIDED_PROPOSED";

    /** [Passer en guidé] (SF-176-02). */
    public static final String GUIDED_ACCEPTED = "GUIDED_ACCEPTED";

    /** [Rester libre] (SF-176-02). */
    public static final String GUIDED_DECLINED = "GUIDED_DECLINED";

    /** L'agent a posé un plan (SF-176-03). */
    public static final String PLAN_SET = "PLAN_SET";

    /** L'agent a modifié un plan déjà validé : un amendement (SF-176-03/04). */
    public static final String PLAN_AMENDED = "PLAN_AMENDED";

    /** [Valider le plan] (SF-176-03). */
    public static final String PLAN_VALIDATED = "PLAN_VALIDATED";

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "type", nullable = false, length = 32)
    private String type;

    @Column(name = "mode", length = 16)
    private String mode;

    @Column(name = "phase", length = 16)
    private String phase;

    @Column(name = "detail", length = 300)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
