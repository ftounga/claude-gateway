package fr.claudegateway.atelier;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.dto.AtelierResumeResponse;

/**
 * Reprise du fil d'Atelier (F-39 / SF-39-04, décision D5).
 *
 * <p>Depuis SF-39-03, la mémoire du travail vit chez nous et non plus dans la survie d'une sandbox
 * chez le fournisseur : la reprise cesse d'être un effet de bord de l'infrastructure pour devenir
 * une décision produit. Par défaut, le fil reprend <b>sans rien demander</b> ; l'utilisateur n'est
 * sollicité que lorsque la reprise ne va pas de soi.</p>
 *
 * <p>Isolation multi-tenant : tout passe par {@code requireOwned(userId, workspaceId)} — un projet
 * qu'on ne possède pas est introuvable, et rien n'est écrit.</p>
 */
@Service
public class AtelierThreadService {

    /**
     * Au-delà de ce délai sans message, la reprise ne va plus de soi et l'écran pose la question
     * (décision D2 : une constante nommée, pas un huitième réglage que personne n'a demandé).
     */
    static final Duration IDLE_AFTER = Duration.ofDays(14);

    private final WorkspaceService workspaceService;
    private final WorkspaceRepository workspaceRepository;
    private final AtelierMessageRepository messageRepository;

    public AtelierThreadService(WorkspaceService workspaceService, WorkspaceRepository workspaceRepository,
            AtelierMessageRepository messageRepository) {
        this.workspaceService = workspaceService;
        this.workspaceRepository = workspaceRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * État de reprise du fil : ce qui sera rejoué, depuis quand, et s'il faut poser la question.
     */
    @Transactional(readOnly = true)
    public AtelierResumeResponse resumeState(UUID userId, UUID workspaceId) {
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
        List<AtelierMessage> replayable = replayable(userId, workspace);
        OffsetDateTime last = replayable.isEmpty()
                ? null
                : replayable.get(replayable.size() - 1).getCreatedAt();
        boolean idle = last != null && last.isBefore(OffsetDateTime.now().minus(IDLE_AFTER));
        return new AtelierResumeResponse(replayable.size(), last, workspace.getChatThreadStartedAt(),
                idle ? "IDLE" : "NONE");
    }

    /**
     * Nouveau départ : pose la frontière de rejeu à l'instant courant.
     *
     * <p>Aucun message n'est supprimé — la conversation reste lisible, seule la mémoire de l'agent
     * repart de zéro (décision D1). Idempotent : redemander un nouveau départ redéplace simplement
     * la frontière.</p>
     */
    @Transactional
    public AtelierResumeResponse restart(UUID userId, UUID workspaceId) {
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
        workspace.setChatThreadStartedAt(OffsetDateTime.now());
        workspaceRepository.save(workspace);
        return new AtelierResumeResponse(0, null, workspace.getChatThreadStartedAt(), "NONE");
    }

    /** Messages que le prochain tour rejouera : tout le fil, ou ce qui suit la frontière. */
    private List<AtelierMessage> replayable(UUID userId, Workspace workspace) {
        OffsetDateTime since = workspace.getChatThreadStartedAt();
        return since == null
                ? messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspace.getId(), userId)
                : messageRepository.findByWorkspaceIdAndUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                        workspace.getId(), userId, since);
    }
}
