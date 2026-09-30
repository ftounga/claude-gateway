package fr.claudegateway.atelier;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.dto.AtelierResumeResponse;
import fr.claudegateway.atelier.dto.ThreadContextSummaryResponse;
import fr.claudegateway.atelier.recall.AtelierSemanticRecall;
import fr.claudegateway.quota.UsageTurn;
import fr.claudegateway.quota.UsageTurnRepository;

/**
 * L'<b>état mémoire du fil courant</b> (F-165 / SF-165-03), servi à la commande vue {@code /contexte}.
 *
 * <p><b>Une vue, pas un moteur.</b> Ce service <b>compose</b> ce qui existe déjà — l'état de reprise
 * ({@code /resume}, F-39), le journal {@code usage_turns} (F-61), les réglages de compaction (F-117) et
 * la présence d'un résumé ancré (F-162) — pour répondre à une seule question : « où en est la mémoire de
 * ce fil ? ». Aucune capacité de Claude n'est réimplémentée (Provider-First) ; le backend reste une
 * passerelle (Gateway-First) : <b>rien n'est recalculé côté modèle</b>.</p>
 *
 * <p><b>Isolation.</b> L'ownership est délégué à {@link AtelierThreadService#resumeState} qui appelle
 * {@code requireOwned(userId, workspaceId)} — un fil d'autrui rend un <b>404 indiscernable</b>. La lecture
 * du journal et celle du workspace filtrent {@code user_id} : un utilisateur ne voit <b>que</b> son propre
 * fil.</p>
 *
 * <p><b>Aucun contenu</b> ne sort d'ici : des volumes, des drapeaux et un seuil seulement — jamais le
 * résumé ancré lui-même, seulement sa présence.</p>
 */
@Service
public class AtelierThreadContextService {

    /** Une « page » de classeur ≈ 500 tokens (~une page de texte). Langage accessible, pas du jargon. */
    private static final long PAGE_TOKENS = 500L;

    private final AtelierThreadService threadService;
    private final UsageTurnRepository usageTurnRepository;
    private final WorkspaceRepository workspaceRepository;
    private final AtelierCompactionProperties compactionProperties;
    private final AtelierSemanticRecall semanticRecall;

    public AtelierThreadContextService(AtelierThreadService threadService,
            UsageTurnRepository usageTurnRepository, WorkspaceRepository workspaceRepository,
            AtelierCompactionProperties compactionProperties, AtelierSemanticRecall semanticRecall) {
        this.threadService = threadService;
        this.usageTurnRepository = usageTurnRepository;
        this.workspaceRepository = workspaceRepository;
        this.compactionProperties = compactionProperties;
        this.semanticRecall = semanticRecall;
    }

    /**
     * L'état mémoire du fil courant du projet.
     *
     * @param userId      utilisateur du contexte de sécurité (jamais un paramètre client)
     * @param workspaceId projet consulté
     * @throws WorkspaceNotFoundException 404 indiscernable si le projet n'existe pas ou n'appartient pas à
     *                                    l'utilisateur (via {@code requireOwned} dans {@code resumeState})
     */
    @Transactional(readOnly = true)
    public ThreadContextSummaryResponse summary(UUID userId, UUID workspaceId) {
        // Ownership + état de reprise (tours vivants / rangés, frontière du fil) en une lecture isolée.
        AtelierResumeResponse resume = threadService.resumeState(userId, workspaceId);
        OffsetDateTime from = resume.threadStartedAt();

        // Contexte vivant : entrée traitée du DERNIER tour du fil courant (même proxy que /cout). Lecture
        // déjà bornée à (user_id, workspace_id) ; le filtre « fil courant » se fait en mémoire.
        List<UsageTurn> turns = usageTurnRepository
                .findByUserIdAndWorkspaceIdOrderByOccurredAtAsc(userId, workspaceId);
        List<UsageTurn> fil = from == null
                ? turns
                : turns.stream().filter(turn -> !turn.getOccurredAt().isBefore(from)).toList();
        long contextTokens = fil.isEmpty() ? 0L : fil.get(fil.size() - 1).getInputTokens();

        // Résumé ancré : la PRÉSENCE seulement (jamais le résumé lui-même). Lecture isolée user_id.
        boolean hasAnchoredSummary = workspaceRepository.findByIdAndUserId(workspaceId, userId)
                .map(Workspace::getChatThreadSummary)
                .map(summary -> !summary.isBlank())
                .orElse(false);

        int triggerTokens = compactionProperties.triggerTokens();
        int fillPercent = triggerTokens <= 0
                ? 0
                : (int) Math.min(100L, Math.round(contextTokens * 100.0 / triggerTokens));

        return new ThreadContextSummaryResponse(
                contextTokens,
                pagesOf(contextTokens),
                resume.turns(),
                resume.foldedTurns(),
                hasAnchoredSummary,
                Boolean.TRUE.equals(compactionProperties.enabled()),
                triggerTokens,
                pagesOf(triggerTokens),
                fillPercent,
                compactionProperties.keepRecentTurns(),
                semanticRecall.isEnabled());
    }

    private static int pagesOf(long tokens) {
        if (tokens <= 0L) {
            return 0;
        }
        return (int) Math.max(1L, Math.round((double) tokens / PAGE_TOKENS));
    }
}
