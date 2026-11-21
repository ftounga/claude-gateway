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
 * <b>Le dernier lancement d'une action rapide</b> (F-275 / SF-275-01, D7) — depuis le bouton, action perso
 * ({@code c:<uuid>}) ou déclencheur de skill ({@code s:<ancre>:<skill>:<déclencheur>}). Une ligne par compte et clé.
 */
@Entity
@Table(name = "quick_action_launches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuickActionLaunch {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "action_key", nullable = false, length = 200, updatable = false)
    private String actionKey;

    @Column(name = "last_launched_at", nullable = false)
    private OffsetDateTime lastLaunchedAt;

    @Column(name = "last_workspace_id")
    private UUID lastWorkspaceId;

    @Column(name = "launch_count", nullable = false)
    private int launchCount;
}
