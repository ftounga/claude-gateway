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

    /**
     * Le bilan de session (F-155 / SF-155-03), branché par mutateur (null pour les formes
     * historiques et les tests) : sans lui, le nouveau départ se comporte exactement comme avant.
     */
    private fr.claudegateway.bilan.SessionBilanTriggerService bilanTrigger;
    /**
     * La <b>définition unique</b> de « qui est administrateur » (F-155 / SF-155-04). Comparer le
     * rôle ici serait une seconde définition — et le super-admin par e-mail, dont le rôle stocké
     * peut ne pas être promu, n'aurait jamais de bilan sans que rien ne le signale.
     */
    private fr.claudegateway.admin.AdminService adminService;

    /** Branche le bilan de session au nouveau départ (F-155 / SF-155-03). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBilan(fr.claudegateway.bilan.SessionBilanTriggerService bilanTrigger,
            fr.claudegateway.admin.AdminService adminService) {
        this.bilanTrigger = bilanTrigger;
        this.adminService = adminService;
    }

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
        // F-121 / SF-121-10 : le mode et le dernier plan persistés du fil, pour restaurer le sélecteur
        // de mode et réafficher le plan dès l'ouverture du projet. null/absent ⇒ comportement d'avant.
        AtelierPlan plan = AtelierPlan.fromJson(workspace.getChatThreadPlan());
        List<AtelierResumeResponse.PlanStep> planSteps = plan.steps().stream()
                .map(step -> new AtelierResumeResponse.PlanStep(step.title(), step.status().label()))
                .toList();
        return new AtelierResumeResponse(replayable.size(), last, workspace.getChatThreadStartedAt(),
                idle ? "IDLE" : "NONE", workspace.getChatThreadMode(), planSteps);
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
        // F-155 / SF-155-03 : relever la session QUI SE FERME, avant de déplacer la frontière —
        // après, il n'y aurait plus rien à relever. Le bilan ne doit jamais faire échouer le geste.
        OffsetDateTime closing = OffsetDateTime.now();
        fr.claudegateway.bilan.SessionBilanTriggerService.Decision decision =
                bilanOf(userId, workspace, closing);
        String bilan = decision.trigger().name();
        workspace.setChatThreadStartedAt(closing);
        // Repartir propre, c'est aussi oublier le résumé de compaction (F-117 / SF-117-01) : sans
        // cela, un « nouveau départ » rejouerait encore le résumé des tours désormais mis de côté.
        workspace.setChatThreadSummary(null);
        // ... et oublier le mode et le plan persistés (F-121 / SF-121-10) : un nouveau départ ne
        // reporte ni un plan ni un mode d'un fil qu'on vient de laisser derrière soi.
        workspace.setChatThreadMode(null);
        workspace.setChatThreadPlan(null);
        workspaceRepository.save(workspace);
        return new AtelierResumeResponse(0, null, workspace.getChatThreadStartedAt(), "NONE",
                null, List.of(), bilan, reportOf(decision, workspace));
    }

    /**
     * Le bilan tel que le terminal le montrera (F-155 / SF-155-07), ou {@code null} quand il n'y a
     * rien — rien à signaler, ou appelant non administrateur, auquel cas rien n'a même été calculé.
     */
    private static AtelierResumeResponse.BilanReport reportOf(
            fr.claudegateway.bilan.SessionBilanTriggerService.Decision decision,
            Workspace workspace) {
        fr.claudegateway.bilan.SessionLedger ledger = decision.ledger();
        if (ledger == null || decision.verdict() == null) {
            return null;
        }
        List<AtelierResumeResponse.BilanSuggestion> suggestions = decision.verdict().suggestions()
                .stream()
                .map(suggestion -> new AtelierResumeResponse.BilanSuggestion(
                        suggestion.kind().name(), suggestion.axis().name(), suggestion.advice(),
                        suggestion.measure(), suggestion.gainPct(), suggestion.gainEur()))
                .toList();
        return new AtelierResumeResponse.BilanReport(
                decision.bilanId() != null,
                workspace.getName(),
                ledger.turns(),
                ledger.elapsed() == null ? 0L : ledger.elapsed().toMinutes(),
                ledger.costEur(),
                ledger.cacheShare(),
                ledger.toolCalls(),
                ledger.failedTools(),
                ledger.filesWritten(),
                ledger.model(),
                decision.verdict().discarded(),
                suggestions);
    }

    /**
     * Ce que la fermeture décide du bilan. La fenêtre part de la frontière <b>précédente</b> — ou,
     * à défaut, de la création du projet : la première session est une session.
     *
     * <p>Réservé à l'administrateur ; pour les autres, rien n'est même calculé.</p>
     */
    private fr.claudegateway.bilan.SessionBilanTriggerService.Decision bilanOf(
            UUID userId, Workspace workspace, OffsetDateTime closing) {
        if (bilanTrigger == null || adminService == null) {
            return fr.claudegateway.bilan.SessionBilanTriggerService.Decision.none();
        }
        OffsetDateTime from = workspace.getChatThreadStartedAt() != null
                ? workspace.getChatThreadStartedAt()
                : workspace.getCreatedAt();
        // Le NOM du projet part avec la décision (F-155 / SF-155-07) : il n'était pas transmis, et
        // la colonne `workspace_name` des bilans gardés est restée vide depuis SF-155-04.
        return bilanTrigger.decide(userId, workspace.getId(), workspace.getName(),
                adminService.isAdmin(), from, closing);
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
