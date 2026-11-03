package fr.claudegateway.jarvis.thread;

import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceSource;

/**
 * <b>Le fil Jarvis</b> (F-245 / SF-245-01, D1) : un espace {@code workspaces} par utilisateur, créé à la première
 * utilisation, nommé « Jarvis », sans poste (client « Hébergé »), cible {@code SANDBOX}. Il est commun à tout ce qui
 * n'appartient à aucun sujet : salutations, tour d'horizon, vie personnelle (SF-245-02).
 *
 * <p><b>Unicité</b> : portée en base par la contrainte {@code UNIQUE} de {@code jarvis_thread_user_id}. Deux appels
 * concurrents peuvent tous deux ne rien trouver ; le second échoue à l'insertion, dans <b>sa propre transaction</b>
 * (jamais celle de l'appelant, qui serait marquée en échec), puis relit le fil créé par le premier.</p>
 *
 * <p><b>Isolation</b> : toute lecture filtre sur le {@code user_id} reçu, qui vient toujours du principal.</p>
 */
@Service
public class JarvisThreadService {

    /** Le nom du fil, fixe (il n'est pas renommable). */
    public static final String NAME = "Jarvis";

    private final WorkspaceRepository workspaces;
    private final TransactionTemplate creation;

    public JarvisThreadService(WorkspaceRepository workspaces, PlatformTransactionManager transactions) {
        this.workspaces = workspaces;
        this.creation = new TransactionTemplate(transactions);
        this.creation.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Le fil Jarvis du compte s'il existe déjà — lecture seule, ne crée rien. */
    public Optional<Workspace> find(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return workspaces.findFirstByUserIdAndJarvisThreadUserId(userId, userId);
    }

    /** L'identifiant du fil Jarvis du compte s'il existe déjà (ne crée rien). */
    public Optional<UUID> idOf(UUID userId) {
        return find(userId).map(Workspace::getId);
    }

    /** Le fil Jarvis du compte, créé s'il n'existe pas encore. Idempotent et sûr sous la concurrence. */
    public Workspace open(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId requis");
        }
        Optional<Workspace> existing = find(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            Workspace created = creation.execute(status -> workspaces.saveAndFlush(Workspace.builder()
                    .userId(userId)
                    .name(NAME)
                    .source(WorkspaceSource.ARCHIVE)
                    .executionTarget(WorkspaceExecutionTarget.SANDBOX)
                    .jarvisThreadUserId(userId)
                    .build()));
            if (created != null) {
                return created;
            }
        } catch (DataIntegrityViolationException raced) {
            // Un appel concurrent l'a créé entre la lecture et l'écriture : on relit le sien.
        }
        return find(userId).orElseThrow(() -> new IllegalStateException("fil Jarvis introuvable après création"));
    }
}
