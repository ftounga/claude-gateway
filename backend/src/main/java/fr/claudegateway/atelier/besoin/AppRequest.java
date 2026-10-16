package fr.claudegateway.atelier.besoin;

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
 * <b>Un besoin de l'application</b> (F-205 / SF-205-01) : un bug ou une évolution de l'application
 * elle-même, signalé depuis un terminal par l'outil {@code signaler_besoin}. {@link #PROPOSE} tant que
 * l'utilisateur n'a pas cliqué [Enregistrer] ; {@link #EN_ATTENTE} ensuite, puis cadré, rejeté ou livré.
 */
@Entity
@Table(name = "app_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppRequest {

    public static final String BUG = "BUG";
    public static final String EVOLUTION = "EVOLUTION";

    public static final String PROPOSE = "PROPOSE";
    public static final String EN_ATTENTE = "EN_ATTENTE";
    public static final String CADRE = "CADRE";
    public static final String REJETE = "REJETE";
    public static final String LIVRE = "LIVRE";

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Le terminal d'où vient le signalement (sans clé étrangère : le besoin lui survit). */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "message_id", updatable = false)
    private UUID messageId;

    /** {@code BUG} | {@code EVOLUTION}. */
    @Column(name = "kind", nullable = false, length = 16, updatable = false)
    private String kind;

    @Column(name = "title", nullable = false, length = 200, updatable = false)
    private String title;

    /** Les mots de l'utilisateur, tels quels. */
    @Column(name = "description", nullable = false, length = 8000, updatable = false)
    private String description;

    /** La reformulation de l'agent. */
    @Column(name = "reformulation", length = 8000, updatable = false)
    private String reformulation;

    /** Ce qui a été observé : écran, étapes, message d'erreur. */
    @Column(name = "observed_context", length = 8000, updatable = false)
    private String observedContext;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** La feature qui porte le besoin une fois cadré (ex. {@code F-210}). */
    @Column(name = "feature_ref", length = 16)
    private String featureRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "recorded_at")
    private OffsetDateTime recordedAt;
}
