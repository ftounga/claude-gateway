package fr.claudegateway.runner.audit;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Activité d'un projet, agrégée depuis le journal du runner (F-49 / SF-49-01) : <b>quand</b> il a
 * travaillé pour la dernière fois, et <b>combien</b> d'appels il a passés sur la fenêtre observée.
 *
 * <p>C'est une projection de lecture, pas une entité : elle ne sort jamais telle quelle de la
 * gateway et ne porte ni cible, ni contenu, ni sortie de commande.</p>
 */
public interface RunnerAuditActivity {

    /** Projet concerné. Jamais {@code null} : la requête écarte les lignes sans projet. */
    UUID getWorkspaceId();

    /** Instant de la dernière ligne de journal de ce projet dans la fenêtre observée. */
    OffsetDateTime getLastAt();

    /** Nombre de lignes de journal de ce projet dans la fenêtre observée. */
    long getCalls();
}
