package fr.claudegateway.atelier.actions;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;

/**
 * <b>Les attentes d'un terminal</b> (F-154 / SF-154-01, F-175 / SF-175-01) : les créer, les lister,
 * changer leur état (À faire → Demandé → Fait, ou Annulé), les éditer, les rétablir.
 *
 * <p><b>Isolation.</b> Chaque méthode appelle {@code requireOwned} <b>en premier</b> — un projet
 * d'autrui rend 404 avant que quoi que ce soit d'autre ne soit lu. Les requêtes portent ensuite
 * {@code user_id} <i>et</i> {@code workspace_id} : deux verrous, pas un. Le {@code host_id} d'une
 * attente est <b>déduit du terminal possédé</b>, jamais lu dans la requête.</p>
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
    static final int MAX_REQUESTED_TO = 120;
    static final int MAX_CHANNEL = 60;

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
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        return insert(userId, workspace, subjectId, description, blocks, person, kind, null);
    }

    /**
     * L'insertion elle-même : bornes, saturation, écriture. Appelée par {@link #create} (l'ajout à
     * la main) et par {@link #record} (l'inscription par l'agent) — <b>une seule</b> implémentation,
     * pour que les bornes soient les mêmes des deux côtés.
     *
     * <p>L'appelant a déjà passé {@code requireOwned} : le {@code host_id} vient du terminal possédé.</p>
     */
    private TerminalAction insert(UUID userId, Workspace workspace, UUID subjectId,
                                  String description, String blocks, String person,
                                  TerminalActionKind kind, String dedupKey) {
        String cleanDescription = required(description, MAX_DESCRIPTION,
                "L'action doit dire ce qu'il faut faire.");
        String cleanBlocks = optional(blocks, MAX_BLOCKS,
                "Ce que l'action débloque tient en " + MAX_BLOCKS + " caractères.");
        String cleanPerson = optional(person, MAX_PERSON,
                "Le nom de la personne tient en " + MAX_PERSON + " caractères.");

        int open = repository.countByUserIdAndWorkspaceIdAndStatusIn(
                userId, workspace.getId(), TerminalActionStatus.OPEN_STATES);
        if (open >= MAX_OPEN_PER_WORKSPACE) {
            throw new InvalidTerminalActionException(
                    "Ce terminal a déjà " + MAX_OPEN_PER_WORKSPACE + " actions ouvertes. "
                            + "Fermez-en ou annulez-en avant d'en ajouter.");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        return repository.save(TerminalAction.builder()
                .userId(userId)
                .workspaceId(workspace.getId())
                .hostId(workspace.getHostId())
                .subjectId(subjectId)
                .description(cleanDescription)
                .blocks(cleanBlocks)
                .person(cleanPerson)
                .kind(kind == null ? TerminalActionKind.ACTION : kind)
                .status(TerminalActionStatus.A_FAIRE)
                .dedupKey(dedupKey)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    /**
     * <b>Inscrit un blocage détecté par l'agent</b> (F-154 / SF-154-02), dédoublonné par sa clé.
     *
     * <p>Ce que dit l'issue rendue : {@code RECORDED} (c'est neuf), {@code ALREADY_OPEN} (le même
     * blocage attend déjà), {@code ALREADY_REQUESTED} (la demande est déjà partie — F-175),
     * {@code REFUSED_BY_USER} (l'utilisateur avait annulé — on ne recrée pas, et l'agent doit le
     * savoir plutôt que redemander), {@code ALREADY_DONE} (c'est déjà réglé).</p>
     *
     * <p>Le {@code userId} et le {@code workspaceId} viennent <b>du tour</b>, jamais des paramètres
     * de l'outil.</p>
     */
    @Transactional
    public Recording record(UUID userId, UUID workspaceId, UUID subjectId,
                            String description, String blocks, String person,
                            TerminalActionKind kind, String key) {
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier

        String cleanDescription = required(description, MAX_DESCRIPTION,
                "L'action doit dire ce qu'il faut faire.");
        String dedupKey = normalizeKey(key == null || key.isBlank() ? cleanDescription : key);

        var existing = repository.findByUserIdAndWorkspaceIdAndDedupKey(userId, workspaceId, dedupKey);
        if (existing.isPresent()) {
            TerminalAction action = existing.get();
            return new Recording(action, outcomeOf(action.getStatus()));
        }

        TerminalAction created = insert(userId, workspace, subjectId,
                cleanDescription, blocks, person, kind, dedupKey);
        return new Recording(created, RecordingOutcome.RECORDED);
    }

    /** L'issue d'une inscription qui retombe sur une attente déjà connue, selon son état. */
    static RecordingOutcome outcomeOf(TerminalActionStatus status) {
        return switch (status) {
            case A_FAIRE -> RecordingOutcome.ALREADY_OPEN;
            case DEMANDE -> RecordingOutcome.ALREADY_REQUESTED;
            case ANNULE -> RecordingOutcome.REFUSED_BY_USER;
            case FAIT -> RecordingOutcome.ALREADY_DONE;
        };
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

    /** Les issues d'une inscription (F-154 / SF-154-02, F-175 / SF-175-01). */
    public enum RecordingOutcome {
        /** C'est neuf : l'action est inscrite. */
        RECORDED,
        /** Le même blocage attend déjà, rien n'a encore été demandé. */
        ALREADY_OPEN,
        /** La demande est déjà partie : on attend la réponse. Ne pas redemander. */
        ALREADY_REQUESTED,
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
                cancelled ? TerminalActionStatus.ANNULE : TerminalActionStatus.FAIT, reason);
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

    /** Les actions d'un terminal, les plus anciennes d'abord. Ouvertes = À faire + Demandé. */
    @Transactional(readOnly = true)
    public List<TerminalAction> list(UUID userId, UUID workspaceId, boolean openOnly) {
        workspaceService.requireOwned(userId, workspaceId);
        return openOnly
                ? repository.findByUserIdAndWorkspaceIdAndStatusInOrderByCreatedAtAsc(
                        userId, workspaceId, TerminalActionStatus.OPEN_STATES)
                : repository.findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(userId, workspaceId);
    }

    /** Toutes les actions ouvertes du compte : ce que le terminal racine regroupe. */
    @Transactional(readOnly = true)
    public List<TerminalAction> listAllOpen(UUID userId) {
        return repository.findByUserIdAndStatusInOrderByCreatedAtAsc(userId, TerminalActionStatus.OPEN_STATES);
    }

    /** Combien reste-t-il ici (À faire + Demandé). C'est le chiffre de la pastille. */
    @Transactional(readOnly = true)
    public int countOpen(UUID userId, UUID workspaceId) {
        workspaceService.requireOwned(userId, workspaceId);
        return repository.countByUserIdAndWorkspaceIdAndStatusIn(
                userId, workspaceId, TerminalActionStatus.OPEN_STATES);
    }

    /** L'action est faite. La raison — la phrase qui l'a close — est conservée. */
    @Transactional
    public TerminalAction close(UUID userId, UUID workspaceId, UUID actionId, String reason) {
        return settle(userId, workspaceId, actionId, TerminalActionStatus.FAIT, reason);
    }

    /** L'action n'avait pas lieu d'être. La parole de l'utilisateur prime. */
    @Transactional
    public TerminalAction cancel(UUID userId, UUID workspaceId, UUID actionId, String reason) {
        return settle(userId, workspaceId, actionId, TerminalActionStatus.ANNULE, reason);
    }

    /**
     * <b>Change l'état d'une attente</b> (F-175 / SF-175-01) — le geste de l'utilisateur sur le
     * panneau : glisser d'une colonne à l'autre, ou le bouton.
     *
     * <ul>
     *   <li>→ {@code DEMANDE} : pose {@code requested_at} (horloge du serveur), {@code requested_to}
     *       et {@code channel} ; depuis {@code DEMANDE}, ne met à jour que ce qui est donné.</li>
     *   <li>→ {@code A_FAIRE} : « pas de réponse, on refait » ; la demande passée reste notée.</li>
     *   <li>→ {@code FAIT} / {@code ANNULE} : la note devient la raison de fermeture.</li>
     * </ul>
     * Changer vers l'état courant est sans effet — jamais une erreur.
     */
    @Transactional
    public TerminalAction changeStatus(UUID userId, UUID workspaceId, UUID actionId,
                                       TerminalActionStatus target, String note,
                                       String requestedTo, String channel) {
        if (target == null) {
            throw new InvalidTerminalActionException(
                    "status est requis : A_FAIRE, DEMANDE, FAIT ou ANNULE.");
        }
        String cleanTo = optional(requestedTo, MAX_REQUESTED_TO,
                "À qui la demande a été faite tient en " + MAX_REQUESTED_TO + " caractères.");
        String cleanChannel = optional(channel, MAX_CHANNEL,
                "Le canal tient en " + MAX_CHANNEL + " caractères.");
        String cleanNote = optional(note, MAX_REASON,
                "La note tient en " + MAX_REASON + " caractères.");

        TerminalAction action = require(userId, workspaceId, actionId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        switch (target) {
            case A_FAIRE -> {
                if (action.getStatus() == TerminalActionStatus.A_FAIRE) {
                    return action;
                }
                reopenAs(action, TerminalActionStatus.A_FAIRE);
            }
            case DEMANDE -> {
                if (action.getStatus() == TerminalActionStatus.DEMANDE) {
                    if (cleanTo == null && cleanChannel == null) {
                        return action;
                    }
                } else {
                    reopenAs(action, TerminalActionStatus.DEMANDE);
                    action.setRequestedAt(now);
                }
                if (cleanTo != null) {
                    action.setRequestedTo(cleanTo);
                } else if (action.getRequestedTo() == null) {
                    action.setRequestedTo(action.getPerson());
                }
                if (cleanChannel != null) {
                    action.setChannel(cleanChannel);
                }
            }
            case FAIT, ANNULE -> {
                if (action.getStatus() == target) {
                    return action;
                }
                action.setStatus(target);
                action.setClosedReason(cleanNote);
                action.setClosedAt(now);
            }
        }
        action.setUpdatedAt(now);
        return repository.save(action);
    }

    /**
     * <b>Édite une attente ouverte</b> (F-175 / SF-175-01). Seuls les champs donnés changent ; une
     * attente fermée ne s'édite pas — on la rétablit d'abord.
     */
    @Transactional
    public TerminalAction edit(UUID userId, UUID workspaceId, UUID actionId,
                               String description, String blocks, String person,
                               TerminalActionKind kind) {
        TerminalAction action = require(userId, workspaceId, actionId);
        if (!action.isOpen()) {
            throw new InvalidTerminalActionException(
                    "Cette attente est fermée : rétablissez-la d'abord pour la modifier.");
        }
        if (description != null) {
            action.setDescription(required(description, MAX_DESCRIPTION,
                    "L'action doit dire ce qu'il faut faire."));
        }
        if (blocks != null) {
            action.setBlocks(optional(blocks, MAX_BLOCKS,
                    "Ce que l'action débloque tient en " + MAX_BLOCKS + " caractères."));
        }
        if (person != null) {
            action.setPerson(optional(person, MAX_PERSON,
                    "Le nom de la personne tient en " + MAX_PERSON + " caractères."));
        }
        if (kind != null) {
            action.setKind(kind);
        }
        action.setUpdatedAt(OffsetDateTime.now(clock));
        return repository.save(action);
    }

    /**
     * Rouvre une action fermée — le « Rétablir » de l'écran, pour la fermeture qui s'est trompée.
     * Elle revient <b>dans l'état d'avant</b> : « Demandé » si une demande était partie, « À faire »
     * sinon.
     */
    @Transactional
    public TerminalAction reopen(UUID userId, UUID workspaceId, UUID actionId) {
        TerminalAction action = require(userId, workspaceId, actionId);
        if (action.isOpen()) {
            return action; // déjà ouverte : sans effet, jamais une erreur
        }
        reopenAs(action, action.getRequestedAt() != null
                ? TerminalActionStatus.DEMANDE : TerminalActionStatus.A_FAIRE);
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

    private static void reopenAs(TerminalAction action, TerminalActionStatus status) {
        action.setStatus(status);
        action.setClosedReason(null);
        action.setClosedAt(null);
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
