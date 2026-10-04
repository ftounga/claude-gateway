package fr.claudegateway.atelier.actions;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;

/**
 * <b>La reprise de l'existant</b> (F-175 / SF-175-07, décision D9) : les attentes ouvertes héritées de
 * F-154 n'ont jamais dit si la demande était partie. Elles sont présentées <b>une fois</b> avec un
 * état proposé ; l'utilisateur valide en bloc ou une par une. <b>Rien n'est changé sans sa
 * validation</b> — ne pas hériter d'une liste fausse, mais ne pas la corriger à sa place non plus.
 *
 * <p><b>Isolation</b> : lecture et écriture sous {@code user_id} ; un identifiant d'autrui est ignoré,
 * comme s'il n'existait pas. Les changements d'état passent par {@link TerminalActionService} (et donc
 * par {@code requireOwned} du terminal de l'attente).</p>
 */
@Service
public class TerminalActionReviewService {

    /** Les verbes qui disent qu'une demande est (réputée) partie : on propose « Demandé ». */
    static final List<String> REQUESTED_VERBS = List.of("demander", "relancer", "transferer");

    private final TerminalActionRepository repository;
    private final WorkspaceRepository workspaces;
    private final TerminalActionService actions;

    public TerminalActionReviewService(TerminalActionRepository repository, WorkspaceRepository workspaces,
                                       TerminalActionService actions) {
        this.repository = repository;
        this.workspaces = workspaces;
        this.actions = actions;
    }

    /** L'état proposé pour une attente héritée : « Demandé » si elle commence par un verbe de demande. */
    static TerminalActionStatus suggest(String description) {
        String folded = Normalizer.normalize(description == null ? "" : description.strip().toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        for (String verb : REQUESTED_VERBS) {
            if (folded.startsWith(verb)) {
                return TerminalActionStatus.DEMANDE;
            }
        }
        return TerminalActionStatus.A_FAIRE;
    }

    /** Les attentes à vérifier du compte, ouvertes, avec leur terminal et l'état proposé. */
    @Transactional(readOnly = true)
    public List<ReviewItem> pending(UUID userId) {
        Map<UUID, String> names = new HashMap<>();
        return repository.findByUserIdAndReviewPendingTrueOrderByCreatedAtAsc(userId).stream()
                .filter(TerminalAction::isOpen)
                .map(a -> new ReviewItem(
                        TerminalActionResponse.from(a, names.computeIfAbsent(a.getWorkspaceId(),
                                id -> workspaces.findByIdAndUserId(id, userId).map(Workspace::getName)
                                        .orElse("Projet supprimé"))),
                        suggest(a.getDescription())))
                .toList();
    }

    /**
     * Applique les décisions de l'utilisateur. Chaque attente décidée quitte « à vérifier » ; un état
     * différent de l'actuel est appliqué par le service des attentes. Un identifiant inconnu, d'autrui,
     * ou déjà vérifié est <b>ignoré</b> (compté), jamais une erreur.
     */
    @Transactional
    public ReviewResult apply(UUID userId, List<Decision> decisions) {
        int applied = 0;
        int ignored = 0;
        for (Decision decision : decisions == null ? List.<Decision>of() : decisions) {
            if (decision == null || decision.id() == null) {
                ignored++;
                continue;
            }
            var found = repository.findByIdAndUserId(decision.id(), userId);
            if (found.isEmpty() || !found.get().isReviewPending()) {
                ignored++;
                continue;
            }
            TerminalAction action = found.get();
            if (decision.status() != null && decision.status() != action.getStatus()) {
                action = actions.changeStatus(userId, action.getWorkspaceId(), action.getId(),
                        decision.status(), null, null, null);
            }
            action.setReviewPending(false);
            repository.save(action);
            applied++;
        }
        return new ReviewResult(applied, ignored);
    }

    /** Une attente à vérifier et l'état proposé. */
    public record ReviewItem(TerminalActionResponse action, TerminalActionStatus suggestedStatus) {
    }

    /** La décision de l'utilisateur pour une attente : l'état retenu ({@code null} = garder l'actuel). */
    public record Decision(UUID id, TerminalActionStatus status) {
    }

    /** Combien de décisions ont été appliquées, combien ignorées. */
    public record ReviewResult(int applied, int ignored) {
    }
}
