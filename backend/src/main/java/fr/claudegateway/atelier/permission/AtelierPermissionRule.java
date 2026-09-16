package fr.claudegateway.atelier.permission;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
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
 * Règle de permission d'un outil de la boucle maison (F-121 / SF-121-02), <b>persistée par
 * workspace/utilisateur</b> : c'est ce qui donne au modèle de permission allow/ask/deny une mémoire
 * qui survit au tour et au redémarrage, là où la porte de confirmation d'avant SF-121-02 remettait
 * tout à zéro à chaque message.
 *
 * <p><b>Isolation</b> : une règle appartient à un couple {@code (user_id, workspace_id)} et n'est
 * jamais lue ni écrite hors de ce couple — un identifiant de workspace deviné n'ouvre rien.</p>
 *
 * <p>{@code command_prefix} n'a de sens que pour {@code bash} : c'est le préfixe de commande couvert
 * par la règle (le « toujours autoriser cette commande »). {@code null} pour une règle qui porte sur
 * <b>tout l'outil</b> (par exemple « toujours autoriser edit_file »).</p>
 */
@Entity
@Table(name = "atelier_permission_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AtelierPermissionRule {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire du workspace (= {@code users.id}). Filtre d'isolation obligatoire. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Workspace concerné. La règle ne vaut que pour ce projet — jamais pour la machine entière. */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    /** Outil visé ({@code bash}, {@code edit_file}, {@code write_file}, …). */
    @Column(name = "tool", nullable = false, length = 32, updatable = false)
    private String tool;

    /**
     * Préfixe de commande couvert (uniquement pour {@code bash}), ou {@code null} pour une règle qui
     * porte sur tout l'outil. Une commande dont le premier mot est ce préfixe — ou qui commence par
     * lui — est couverte.
     */
    @Column(name = "command_prefix", length = 512, updatable = false)
    private String commandPrefix;

    /** {@code ALLOW} | {@code ASK} | {@code DENY}. Mis à jour lorsqu'une règle existante change. */
    @Column(name = "effect", nullable = false, length = 8)
    private String effect;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** L'effet typé de la règle. */
    public PermissionEffect effect() {
        return PermissionEffect.fromStored(effect);
    }
}
