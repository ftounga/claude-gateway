package fr.claudegateway.atelier.actions;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.WorkspaceService;

/**
 * <b>Les actions d'un terminal</b> (F-154 / SF-154-01) : les créer, les lister, les fermer, les
 * annuler.
 *
 * <p><b>Isolation.</b> Chaque méthode appelle {@code requireOwned} <b>en premier</b> — un projet
 * d'autrui rend 404 avant que quoi que ce soit d'autre ne soit lu. Les requêtes portent ensuite
 * {@code user_id} <i>et</i> {@code workspace_id} : deux verrous, pas un.</p>
 *
 * <p><b>La parole de l'utilisateur prime.</b> Il annule ce qu'il veut, et une action annulée ne
 * revient pas d'elle-même : l'agent qui redétecte le même blocage ne la recrée pas (SF-154-02 s'en
 * charge par la clé de détection).</p>
 */
@Service
public class TerminalActionService {

    /** Au-delà, ce n'est plus une liste d'actions : c'est un tas que personne ne traite. */
    static final int MAX_OPEN_PER_WORKSPACE = 50;

    static final int MAX_DESCRIPTION = 300;
    static final int MAX_BLOCKS = 200;
    static final int MAX_PERSON = 120;
    static final int MAX_REASON = 300;
    static final int MAX_KEY = 200;

    private final TerminalActionRepository repository;
    private final WorkspaceService workspaceService;
    private final Clock clock;

    public TerminalActionService(TerminalActionRepository repository,
                                 WorkspaceService workspaceService,
                                 Clock clock) {
        this.repository = repository;
        this.workspaceService = workspaceService;
        this.clock = clock;
    }

    /**
     * Inscrit une action à faire dans un terminal.
     *
     * @throws InvalidTerminalActionException description vide, borne dépassée, ou liste saturée
     */
    @Transactional
    public TerminalAction create(UUID userId, UUID workspaceId, UUID subjectId,
                                 String description, String blocks, String person,
                                 TerminalActionKind kind) {
        workspaceService.requireOwned(userId, workspaceId); // 404 si non possédé — TOUJOURS en premier
        return insert(userId, workspaceId, subjectId, description, blocks, person, kind, null);
    }

    /**
     * L'insertion elle-même : bornes, saturation, écriture. Appelée par {@link #create} (l'ajout à
     * la main) et par {@link #record} (l'inscription par l'agent) — <b>une seule</b> implémentation,
     * pour que les bornes soient les mêmes des deux côtés.
     *
     * <p>L'appelant a déjà passé {@code requireOwned}.</p>
     */
    private TerminalAction insert(UUID userId, UUID workspaceId, UUID subjectId,
                                  String description, String blocks, String person,
                                  TerminalActionKind kind, String dedupKey) {
        String cleanDescription = required(description, MAX_DESCRIPTION,
                "L'action doit dire ce qu'il faut faire.");
        String cleanBlocks = optional(blocks, MAX_BLOCKS,
                "Ce que l'action débloque tient en " + MAX_BLOCKS + " caractères.");
        String cleanPerson = optional(person, MAX_PERSON,
                "Le nom de la personne tient en " + MAX_PERSON + " caractères.");

        int open = repository.countByUserIdAndWorkspaceIdAndStatus(
                userId, workspaceId, TerminalActionStatus.OPEN);
        if (open >= MAX_OPEN_PER_WORKSPACE) {
            throw new InvalidTerminalActionException(
                    "Ce terminal a déjà " + MAX_OPEN_PER_WORKSPACE + " actions ouvertes. "
                            + "Fermez-en ou annulez-en avant d'en ajouter.");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        return repository.save(TerminalAction.builder()
                .userId(userId)
                .workspaceId(workspaceId)
                .subjectId(subjectId)
                .description(cleanDescription)
                .blocks(cleanBlocks)
                .person(cleanPerson)
                .kind(kind == null ? TerminalActionKind.ACTION : kind)
                .status(TerminalActionStatus.OPEN)
                .dedupKey(dedupKey)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    /**
     * <b>Inscrit un blocage détecté par l'agent</b> (F-154 / SF-154-02), dédoublonné par sa clé.
     *
     * <p>Ce que dit l'issue rendue : {@code RECORDED} (c'est neuf), {@code ALREADY_OPEN} (le même
     * blocage attend déjà), {@code REFUSED_BY_USER} (l'utilisateur avait annulé — on ne recrée pas,
     * et l'agent doit le savoir plutôt que redemander), {@code ALREADY_DONE} (c'est déjà réglé).</p>
     *
     * <p>Le {@code userId} et le {@code workspaceId} viennent <b>du tour</b>, jamais des paramètres
     * de l'outil.</p>
     */
    @Transactional
    public Recording record(UUID userId, UUID workspaceId, UUID subjectId,
                            String description, String blocks, String person,
                            TerminalActionKind kind, String key) {
        workspaceService.requireOwned(userId, workspaceId); // 404 si non possédé — TOUJOURS en premier

        String cleanDescription = required(description, MAX_DESCRIPTION,
                "L'action doit dire ce qu'il faut faire.");
        String dedupKey = normalizeKey(key == null || key.isBlank() ? cleanDescription : key);

        var existing = repository.findByUserIdAndWorkspaceIdAndDedupKey(userId, workspaceId, dedupKey);
        if (existing.isPresent()) {
            TerminalAction action = existing.get();
            return new Recording(action, switch (action.getStatus()) {
                case OPEN -> RecordingOutcome.ALREADY_OPEN;
                case CANCELLED -> RecordingOutcome.REFUSED_BY_USER;
                case DONE -> RecordingOutcome.ALREADY_DONE;
            });
        }

        TerminalAction created = insert(userId, workspaceId, subjectId,
                cleanDescription, blocks, person, kind, dedupKey);
        return new Recording(created, RecordingOutcome.RECORDED);
    }

    /**
     * La clé, ramenée à sa forme stable : minuscules, sans accent, un tiret pour tout le reste.
     * Dérivée de l'énoncé quand l'agent n'en donne pas — <b>jamais vide</b>, sinon le dédoublonnage
     * ne protégerait rien.
     */
    static String normalizeKey(String raw) {
        String folded = java.text.Normalizer.normalize(raw.strip().toLowerCase(java.util.Locale.ROOT),
                        java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (folded.isEmpty()) {
            folded = "action";
        }
        return folded.length() > MAX_KEY ? folded.substring(0, MAX_KEY) : folded;
    }

    /** Ce qu'une inscription a donné, et l'action concernée. */
    public record Recording(TerminalAction action, RecordingOutcome outcome) {
    }

    /** Les quatre issues d'une inscription (F-154 / SF-154-02). */
    public enum RecordingOutcome {
        /** C'est neuf : l'action est inscrite. */
        RECORDED,
        /** Le même blocage attend déjà : rien de plus à faire. */
        ALREADY_OPEN,
        /** L'utilisateur avait annulé. On ne recrée pas — sa parole prime. */
        REFUSED_BY_USER,
        /** C'est déjà réglé. */
        ALREADY_DONE
    }

    /**
     * <b>Ferme l'action ouverte portant cette clé</b> (F-154 / SF-154-04) — ce que l'agent appelle
     * quand l'utilisateur a répondu dans le terminal.
     *
     * <p>La raison conservée est <b>la parole de l'utilisateur</b>. Une action qui disparaît sans
     * raison est une action qu'on refait ; fermée sur un résumé approximatif, c'est pire : elle fait
     * croire à un fait qui n'a pas été dit.</p>
     *
     * @param cancelled vrai si l'utilisateur a dit que l'action n'avait pas lieu d'être — ce n'est
     *                  pas « c'est fait », et les confondre ferait mentir l'historique
     */
    @Transactional
    public Closing closeByKey(UUID userId, UUID workspaceId, String key, String reason,
                              boolean cancelled) {
        workspaceService.requireOwned(userId, workspaceId); // 404 si non possédé — TOUJOURS en premier

        if (key == null || key.isBlank()) {
            throw new InvalidTerminalActionException("key est requise : la clé de l'action à fermer.");
        }
        var found = repository.findByUserIdAndWorkspaceIdAndDedupKey(
                userId, workspaceId, normalizeKey(key));
        if (found.isEmpty()) {
            return new Closing(null, ClosingOutcome.UNKNOWN);
        }
        TerminalAction action = found.get();
        if (!action.isOpen()) {
            return new Closing(action, ClosingOutcome.ALREADY_CLOSED);
        }
        TerminalAction settled = settle(userId, workspaceId, action.getId(),
                cancelled ? TerminalActionStatus.CANCELLED : TerminalActionStatus.DONE, reason);
        return new Closing(settled, cancelled ? ClosingOutcome.CANCELLED : ClosingOutcome.CLOSED);
    }

    /** Ce qu'une fermeture par clé a donné, et l'action concernée ({@code null} si inconnue). */
    public record Closing(TerminalAction action, ClosingOutcome outcome) {
    }

    /** Les issues d'une fermeture par clé (F-154 / SF-154-04). */
    public enum ClosingOutcome {
        /** Fermée : c'est fait. */
        CLOSED,
        /** Fermée : elle n'avait pas lieu d'être. */
        CANCELLED,
        /** Aucune action ne porte cette clé ici. */
        UNKNOWN,
        /** Elle était déjà fermée — rien changé. */
        ALREADY_CLOSED
    }

    /** Les actions d'un terminal, les plus anciennes d'abord. */    /** Les actions d'un terminal, les plus anciennes d'abord. */
    @Transactional(readOnly = true)
    public List<TerminalAction> list(UUID userId, UUID workspaceId, boolean openOnly) {
        workspaceService.requireOwned(userId, workspaceId);
        return openOnly
                ? repository.findByUserIdAndWorkspaceIdAndStatusOrderByCreatedAtAsc(
                        userId, workspaceId, TerminalActionStatus.OPEN)
                : repository.findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(userId, workspaceId);
    }

    /** Toutes les actions ouvertes du compte : ce que le terminal racine regroupe. */
    @Transactional(readOnly = true)
    public List<TerminalAction> listAllOpen(UUID userId) {
        return repository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, TerminalActionStatus.OPEN);
    }

    /** Combien reste-t-il à faire ici. C'est le chiffre de la pastille. */
    @Transactional(readOnly = true)
    public int countOpen(UUID userId, UUID workspaceId) {
        workspaceService.requireOwned(userId, workspaceId);
        return repository.countByUserIdAndWorkspaceIdAndStatus(
                userId, workspaceId, TerminalActionStatus.OPEN);
    }

    /** L'action est faite. La raison — la phrase qui l'a close — est conservée. */
    @Transactional
    public TerminalAction close(UUID userId, UUID workspaceId, UUID actionId, String reason) {
        return settle(userId, workspaceId, actionId, TerminalActionStatus.DONE, reason);
    }

    /** L'action n'avait pas lieu d'être. La parole de l'utilisateur prime. */
    @Transactional
    public TerminalAction cancel(UUID userId, UUID workspaceId, UUID actionId, String reason) {
        return settle(userId, workspaceId, actionId, TerminalActionStatus.CANCELLED, reason);
    }

    /**
     * Rouvre une action fermée — le « Rétablir » de l'écran, pour la fermeture automatique qui
     * s'est trompée.
     */
    @Transactional
    public TerminalAction reopen(UUID userId, UUID workspaceId, UUID actionId) {
        TerminalAction action = require(userId, workspaceId, actionId);
        if (action.isOpen()) {
            return action; // déjà ouverte : sans effet, jamais une erreur
        }
        action.setStatus(TerminalActionStatus.OPEN);
        action.setClosedReason(null);
        action.setClosedAt(null);
        action.setUpdatedAt(OffsetDateTime.now(clock));
        return repository.save(action);
    }

    /** Purge des actions d'un projet supprimé. */
    @Transactional
    public int purgeWorkspace(UUID userId, UUID workspaceId) {
        return repository.purgeWorkspace(userId, workspaceId);
    }

    /** Purge à la suppression du compte. */
    @Transactional
    public int purgeUser(UUID userId) {
        return repository.purgeUser(userId);
    }

    private TerminalAction settle(UUID userId, UUID workspaceId, UUID actionId,
                                  TerminalActionStatus target, String reason) {
        TerminalAction action = require(userId, workspaceId, actionId);
        if (!action.isOpen()) {
            return action; // déjà fermée : sans effet. Ne jamais casser un tour pour si peu.
        }
        action.setStatus(target);
        action.setClosedReason(optional(reason, MAX_REASON,
                "La raison de fermeture tient en " + MAX_REASON + " caractères."));
        OffsetDateTime now = OffsetDateTime.now(clock);
        action.setClosedAt(now);
        action.setUpdatedAt(now);
        return repository.save(action);
    }

    private TerminalAction require(UUID userId, UUID workspaceId, UUID actionId) {
        workspaceService.requireOwned(userId, workspaceId);
        return repository.findByIdAndUserIdAndWorkspaceId(actionId, userId, workspaceId)
                .orElseThrow(() -> new TerminalActionNotFoundException("Action introuvable."));
    }

    private static String required(String value, int max, String emptyMessage) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) {
            throw new InvalidTerminalActionException(emptyMessage);
        }
        if (trimmed.length() > max) {
            throw new InvalidTerminalActionException(
                    "L'action tient en " + max + " caractères — dites l'essentiel.");
        }
        return trimmed;
    }

    private static String optional(String value, int max, String tooLongMessage) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > max) {
            throw new InvalidTerminalActionException(tooLongMessage);
        }
        return trimmed;
    }
}
