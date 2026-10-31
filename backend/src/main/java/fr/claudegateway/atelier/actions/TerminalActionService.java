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

    /** Le dédoublonnage par le sens (F-175 / SF-175-03) ; absent = la clé seule juge. */
    private TerminalActionSemanticDedup semanticDedup;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSemanticDedup(TerminalActionSemanticDedup semanticDedup) {
        this.semanticDedup = semanticDedup;
    }

    /** Le sujet du terminal (F-180 / SF-180-02) ; absent = {@code subject_id} reste celui donné. */
    private TerminalActionSubjectResolver subjectResolver;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSubjectResolver(TerminalActionSubjectResolver subjectResolver) {
        this.subjectResolver = subjectResolver;
    }

    /** Une attente faite depuis moins de ce nombre de jours compte comme « déjà obtenu » (F-180 / SF-180-04). */
    static final int RECENT_DONE_DAYS = 30;

    /** Le registre des accès (F-180 / SF-180-04) ; absent = pas de comparaison au registre. */
    private AccesRegistryService registry;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRegistry(AccesRegistryService registry) {
        this.registry = registry;
    }

    /** Les interlocuteurs normalisés du poste (F-180 / SF-180-03) ; absent = texte libre seul. */
    private InterlocuteurService interlocuteurs;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setInterlocuteurs(InterlocuteurService interlocuteurs) {
        this.interlocuteurs = interlocuteurs;
    }

    /**
     * Rattache l'attente à l'interlocuteur du poste qui porte son nom — celui à qui la demande a été faite,
     * à défaut la personne concernée (F-180 / SF-180-03). Sans poste ou sans nom : rien.
     */
    private void linkInterlocuteur(TerminalAction action) {
        if (interlocuteurs == null || action.getHostId() == null) {
            return;
        }
        String name = action.getRequestedTo() != null ? action.getRequestedTo() : action.getPerson();
        if (name == null || name.isBlank()) {
            return;
        }
        interlocuteurs.resolve(action.getUserId(), action.getHostId(), name)
                .ifPresent(i -> action.setInterlocuteurId(i.getId()));
    }

    /** La nature et « qui doit agir » d'une attente (F-180 / SF-180-02, D1) — chacun nul = non dit. */
    public record Qualification(TerminalActionCategorie categorie, TerminalActionQui qui) {
        public static final Qualification NONE = new Qualification(null, null);

        /** Lu de deux textes (outil, requête) ; une valeur inconnue est une erreur lisible. */
        public static Qualification parse(String categorie, String qui) {
            return new Qualification(TerminalActionCategorie.parse(categorie), TerminalActionQui.parse(qui));
        }

        boolean isEmpty() {
            return categorie == null && qui == null;
        }
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
        return create(userId, workspaceId, subjectId, description, blocks, person, kind, Qualification.NONE);
    }

    /** Même chose, avec la nature et « qui doit agir » (F-180 / SF-180-02). */
    @Transactional
    public TerminalAction create(UUID userId, UUID workspaceId, UUID subjectId,
                                 String description, String blocks, String person,
                                 TerminalActionKind kind, Qualification qualification) {
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        return insert(userId, workspace, subjectId, description, blocks, person, kind, null, qualification);
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
                                  TerminalActionKind kind, String dedupKey, Qualification qualification) {
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
        if (subjectId == null && subjectResolver != null) {
            // F-180 / SF-180-02 (D3) : le sujet unique lié au terminal, sinon nul.
            subjectId = subjectResolver.subjectOf(userId, workspace).orElse(null);
        }
        Qualification q = qualification == null ? Qualification.NONE : qualification;
        TerminalAction built = TerminalAction.builder()
                .userId(userId)
                .workspaceId(workspace.getId())
                .hostId(workspace.getHostId())
                .subjectId(subjectId)
                .description(cleanDescription)
                .blocks(cleanBlocks)
                .person(cleanPerson)
                .kind(kind == null ? TerminalActionKind.ACTION : kind)
                .status(TerminalActionStatus.A_FAIRE)
                .categorie(q.categorie())
                .qui(q.qui())
                .dedupKey(dedupKey)
                .createdAt(now)
                .updatedAt(now)
                .build();
        linkInterlocuteur(built);
        TerminalAction saved = repository.save(built);
        embedAfterCommit(saved);
        return saved;
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
        return record(userId, workspaceId, subjectId, description, blocks, person, kind, key, Qualification.NONE);
    }

    /** Même chose, avec la nature et « qui doit agir » proposés par l'agent (F-180 / SF-180-02). */
    @Transactional
    public Recording record(UUID userId, UUID workspaceId, UUID subjectId,
                            String description, String blocks, String person,
                            TerminalActionKind kind, String key, Qualification qualification) {
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier

        String cleanDescription = required(description, MAX_DESCRIPTION,
                "L'action doit dire ce qu'il faut faire.");
        String dedupKey = normalizeKey(key == null || key.isBlank() ? cleanDescription : key);

        var existing = repository.findByUserIdAndWorkspaceIdAndDedupKey(userId, workspaceId, dedupKey);
        if (existing.isPresent()) {
            TerminalAction action = fillQualification(existing.get(), qualification);
            return new Recording(action, outcomeOf(action.getStatus()), RecordingMatch.KEY);
        }

        // F-175 / SF-175-03 — la même clé, OUVERTE, ailleurs sur le poste : un accès appartient au
        // client, pas au terminal. Les fermées d'un autre terminal ne bloquent pas une nouvelle demande.
        UUID hostId = workspace.getHostId();
        if (hostId != null) {
            var onHost = repository.findByUserIdAndHostIdAndDedupKeyOrderByCreatedAtDesc(userId, hostId, dedupKey)
                    .stream().filter(TerminalAction::isOpen).findFirst();
            if (onHost.isPresent()) {
                return new Recording(fillQualification(onHost.get(), qualification),
                        outcomeOf(onHost.get().getStatus()), RecordingMatch.KEY_ON_HOST);
            }
        }

        // F-175 / SF-175-03 — la même demande DITE AUTREMENT (pgvector, seuil exigeant). Éteint sans clé.
        if (semanticDedup != null) {
            var similar = semanticDedup.findSimilarOpen(userId, hostId, workspaceId, cleanDescription)
                    .flatMap(match -> repository.findByIdAndUserId(match.actionId(), userId))
                    .filter(TerminalAction::isOpen);
            if (similar.isPresent()) {
                return new Recording(fillQualification(similar.get(), qualification),
                        outcomeOf(similar.get().getStatus()), RecordingMatch.MEANING);
            }
        }

        // F-180 / SF-180-04 — DÉJÀ OBTENU : l'accès est au registre (non expiré). Seulement pour un accès,
        // ou une attente dont la nature n'est pas dite : une validation ne se compare pas à un accès.
        TerminalActionCategorie categorie = qualification == null ? null : qualification.categorie();
        if (registry != null && (categorie == null || categorie == TerminalActionCategorie.ACCES)) {
            var obtained = registry.findObtained(userId, workspace, cleanDescription);
            if (obtained.isPresent()) {
                return new Recording(null, RecordingOutcome.ALREADY_OBTAINED, RecordingMatch.NONE, obtained.get());
            }
        }

        // F-180 / SF-180-04 — la même clé, FAITE récemment, ailleurs sur le poste : déjà obtenu.
        OffsetDateTime recent = OffsetDateTime.now(clock).minusDays(RECENT_DONE_DAYS);
        if (hostId != null) {
            var doneOnHost = repository.findByUserIdAndHostIdAndDedupKeyOrderByCreatedAtDesc(userId, hostId, dedupKey)
                    .stream()
                    .filter(a -> a.getStatus() == TerminalActionStatus.FAIT && a.getClosedAt() != null
                            && !a.getClosedAt().isBefore(recent))
                    .findFirst();
            if (doneOnHost.isPresent()) {
                return new Recording(doneOnHost.get(), RecordingOutcome.ALREADY_DONE, RecordingMatch.KEY_ON_HOST);
            }
        }
        if (semanticDedup != null) {
            var similarDone = semanticDedup.findSimilarRecentDone(userId, hostId, workspaceId, cleanDescription, recent)
                    .flatMap(match -> repository.findByIdAndUserId(match.actionId(), userId))
                    .filter(a -> a.getStatus() == TerminalActionStatus.FAIT);
            if (similarDone.isPresent()) {
                return new Recording(similarDone.get(), RecordingOutcome.ALREADY_DONE, RecordingMatch.MEANING);
            }
        }

        TerminalAction created = insert(userId, workspace, subjectId,
                cleanDescription, blocks, person, kind, dedupKey, qualification);
        return new Recording(created, RecordingOutcome.RECORDED, RecordingMatch.NONE);
    }

    /**
     * Une attente reconnue <b>ouverte</b> sans nature prend celle que l'agent vient de dire
     * (F-180 / SF-180-02) — jamais ne remplace une nature déjà dite.
     */
    private TerminalAction fillQualification(TerminalAction action, Qualification qualification) {
        if (qualification == null || qualification.isEmpty() || !action.isOpen()) {
            return action;
        }
        boolean changed = false;
        if (action.getCategorie() == null && qualification.categorie() != null) {
            action.setCategorie(qualification.categorie());
            changed = true;
        }
        if (action.getQui() == null && qualification.qui() != null) {
            action.setQui(qualification.qui());
            changed = true;
        }
        if (!changed) {
            return action;
        }
        action.setUpdatedAt(OffsetDateTime.now(clock));
        return repository.save(action);
    }

    /**
     * Après la validation de la transaction, l'embedding de la nouvelle attente (F-175 / SF-175-03) :
     * calculé avant, il viserait une ligne que la base ne montre pas encore.
     */
    private void embedAfterCommit(TerminalAction action) {
        if (semanticDedup == null || action == null || action.getId() == null) {
            return;
        }
        UUID id = action.getId();
        String description = action.getDescription();
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            semanticDedup.embedAsync(id, description);
                        }
                    });
        } else {
            semanticDedup.embedAsync(id, description);
        }
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
    public record Recording(TerminalAction action, RecordingOutcome outcome, RecordingMatch match, AccesObtenu acces) {

        public Recording(TerminalAction action, RecordingOutcome outcome, RecordingMatch match) {
            this(action, outcome, match, null);
        }

        public Recording(TerminalAction action, RecordingOutcome outcome) {
            this(action, outcome, RecordingMatch.NONE, null);
        }
    }

    /** Comment une inscription a reconnu une attente déjà là (F-175 / SF-175-03). */
    public enum RecordingMatch {
        /** Rien de reconnu : l'attente est neuve. */
        NONE,
        /** La même clé, dans ce terminal. */
        KEY,
        /** La même clé, ouverte, dans un autre terminal du poste. */
        KEY_ON_HOST,
        /** La même demande dite autrement (similarité sémantique). */
        MEANING
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
        ALREADY_DONE,
        /** L'accès est au registre, non expiré (F-180 / SF-180-04) : rien n'est inscrit. */
        ALREADY_OBTAINED
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

    /**
     * <b>L'état de l'attente qui porte cette clé</b> (F-176 / SF-176-03) : celle de ce terminal d'abord,
     * sinon la plus récente du même poste. Lecture seule, sous {@code user_id} ; l'appelant a déjà le
     * terminal possédé. Vide si aucune attente ne porte la clé.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<TerminalActionStatus> statusOfKey(UUID userId, Workspace workspace, String key) {
        return actionOfKey(userId, workspace, key).map(TerminalAction::getStatus);
    }

    /**
     * <b>L'attente qui porte cette clé</b> (F-220 / SF-220-01) — même recherche que {@link #statusOfKey} : celle
     * de ce terminal d'abord, sinon la plus récente du même poste. Lecture seule, sous {@code user_id} ; l'appelant
     * a déjà le terminal possédé. Vide si aucune attente ne porte la clé.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<TerminalAction> actionOfKey(UUID userId, Workspace workspace, String key) {
        if (userId == null || workspace == null || key == null || key.isBlank()) {
            return java.util.Optional.empty();
        }
        String dedupKey = normalizeKey(key);
        var here = repository.findByUserIdAndWorkspaceIdAndDedupKey(userId, workspace.getId(), dedupKey);
        if (here.isPresent()) {
            return here;
        }
        if (workspace.getHostId() == null) {
            return java.util.Optional.empty();
        }
        return repository.findByUserIdAndHostIdAndDedupKeyOrderByCreatedAtDesc(
                        userId, workspace.getHostId(), dedupKey).stream()
                .findFirst();
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
                linkInterlocuteur(action); // F-180 / SF-180-03 : à qui la demande est partie
            }
            case FAIT, ANNULE -> {
                if (action.getStatus() == target) {
                    return action;
                }
                action.setStatus(target);
                action.setClosedReason(cleanNote);
                action.setClosedAt(now);
                action.clearProposal();
            }
        }
        action.setUpdatedAt(now);
        action.setReviewPending(false); // un geste de l'utilisateur vaut vérification (SF-175-07)
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
        return edit(userId, workspaceId, actionId, description, blocks, person, kind, Qualification.NONE);
    }

    /** Même chose ; une nature ou un « qui » donné remplace l'actuel (F-180 / SF-180-02). */
    @Transactional
    public TerminalAction edit(UUID userId, UUID workspaceId, UUID actionId,
                               String description, String blocks, String person,
                               TerminalActionKind kind, Qualification qualification) {
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
            linkInterlocuteur(action); // F-180 / SF-180-03
        }
        if (kind != null) {
            action.setKind(kind);
        }
        if (qualification != null && qualification.categorie() != null) {
            action.setCategorie(qualification.categorie());
        }
        if (qualification != null && qualification.qui() != null) {
            action.setQui(qualification.qui());
        }
        action.setUpdatedAt(OffsetDateTime.now(clock));
        TerminalAction saved = repository.save(action);
        if (description != null) {
            embedAfterCommit(saved); // le sens a changé : le vecteur suit (F-175 / SF-175-03)
        }
        return saved;
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

    // ---- F-180 / SF-180-06 : l'échéance ----

    /**
     * L'échéance donnée en texte ISO ({@code AAAA-MM-JJ}) : {@code null} si vide ; erreur lisible si
     * illisible. Sert à l'édition (la chaîne vide efface) et à l'outil de l'agent.
     */
    static java.time.LocalDate parseDue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(raw.strip());
        } catch (java.time.format.DateTimeParseException e) {
            throw new InvalidTerminalActionException("L'échéance s'écrit AAAA-MM-JJ.");
        }
    }

    /** L'ajout à la main avec son échéance (F-180 / SF-180-06), dans une seule transaction. */
    @Transactional
    public TerminalAction create(UUID userId, UUID workspaceId, UUID subjectId, String description, String blocks,
                                 String person, TerminalActionKind kind, Qualification qualification,
                                 java.time.LocalDate due) {
        TerminalAction created = create(userId, workspaceId, subjectId, description, blocks, person, kind, qualification);
        return due == null ? created : applyDue(created, due);
    }

    /**
     * L'édition avec l'échéance en texte (F-180 / SF-180-06) : {@code null} = inchangée, chaîne vide =
     * effacée, sinon {@code AAAA-MM-JJ}.
     */
    @Transactional
    public TerminalAction edit(UUID userId, UUID workspaceId, UUID actionId, String description, String blocks,
                               String person, TerminalActionKind kind, Qualification qualification, String dueRaw) {
        java.time.LocalDate due = dueRaw == null ? null : parseDue(dueRaw);
        TerminalAction edited = edit(userId, workspaceId, actionId, description, blocks, person, kind, qualification);
        return dueRaw == null ? edited : applyDue(edited, due);
    }

    /** Pose (ou efface, {@code null}) l'échéance d'une attente de ce terminal ou de son poste. */
    @Transactional
    public TerminalAction setDue(UUID userId, UUID workspaceId, UUID actionId, java.time.LocalDate due) {
        TerminalAction action = require(userId, workspaceId, actionId);
        return applyDue(action, due);
    }

    /** L'échéance posée sur une attente déjà résolue sous l'isolation (agent, création). */
    @Transactional
    TerminalAction applyDue(TerminalAction action, java.time.LocalDate due) {
        if (action == null || java.util.Objects.equals(action.getDueAt(), due)) {
            return action;
        }
        action.setDueAt(due);
        action.setUpdatedAt(OffsetDateTime.now(clock));
        return repository.save(action);
    }

    /**
     * Range une attente que l'agent vient d'inscrire sous le chantier de son tour (F-227 / SF-227-01). Sans effet
     * sur une attente déjà rangée (une attente existante retrouvée par sa clé garde son chantier).
     */
    @Transactional
    TerminalAction applyChantier(TerminalAction action, Integer chantier) {
        if (action == null || chantier == null || chantier <= 0 || action.getChantierNumber() != null) {
            return action;
        }
        action.setChantierNumber(chantier);
        return repository.save(action);
    }

    /** Les chantiers du sujet, pour vérifier un rattachement (F-227) ; absent = rattachement refusé. */
    private fr.claudegateway.atelier.journey.SubjectJourneyChantierRepository chantiers;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setChantiers(fr.claudegateway.atelier.journey.SubjectJourneyChantierRepository chantiers) {
        this.chantiers = chantiers;
    }

    /**
     * <b>[Rattacher à un chantier]</b> (F-227 / SF-227-01) : range l'attente sous le chantier OUVERT {@code chantier}
     * de son sujet, ou la rend « au fil de l'eau » ({@code null}). {@code requireOwned} d'abord : 404 chez autrui.
     *
     * @throws InvalidTerminalActionException le chantier n'est pas ouvert sur ce sujet
     */
    @Transactional
    public TerminalAction attachToChantier(UUID userId, UUID workspaceId, UUID actionId, Integer chantier) {
        TerminalAction action = require(userId, workspaceId, actionId);
        if (chantier != null) {
            boolean open = chantier > 0 && chantiers != null && chantiers
                    .findFirstByUserIdAndWorkspaceIdAndNumber(userId, workspaceId, chantier)
                    .filter(fr.claudegateway.atelier.journey.SubjectJourneyChantier::isOpen).isPresent();
            if (!open) {
                throw new InvalidTerminalActionException("Le chantier " + chantier + " n'est pas ouvert sur ce sujet.");
            }
        }
        action.setChantierNumber(chantier);
        action.setUpdatedAt(OffsetDateTime.now(clock));
        return repository.save(action);
    }

    /** Les attentes ouvertes d'un sujet rangées sous un chantier (F-227) — l'appelant a le terminal possédé. */
    @Transactional(readOnly = true)
    public List<TerminalAction> openByChantier(UUID userId, UUID workspaceId) {
        return repository.findByUserIdAndWorkspaceIdAndChantierNumberIsNotNullAndStatusIn(userId, workspaceId,
                TerminalActionStatus.OPEN_STATES).stream()
                .filter(a -> userId.equals(a.getUserId()) && a.getMergedIntoId() == null)
                .toList();
    }

    /**
     * <b>[Revoir les attentes de plus de N jours]</b> (F-227 / SF-227-01) : PROPOSE la clôture (F-175, « n'a plus lieu
     * d'être ») de chaque attente ouverte, non fusionnée, sans proposition, sans geste depuis {@code days} jours,
     * parmi {@code candidates} (déjà lues sous l'isolation). Rien n'est fermé : l'utilisateur confirme. La date du
     * dernier geste n'est PAS touchée (l'ancienneté reste lisible).
     *
     * @return les attentes proposées
     */
    @Transactional
    public List<TerminalAction> proposeStaleClosure(UUID userId, List<TerminalAction> candidates, int days) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime threshold = now.minusDays(Math.max(1, days));
        List<TerminalAction> proposed = new java.util.ArrayList<>();
        for (TerminalAction a : candidates) {
            OffsetDateTime last = a.getUpdatedAt() != null ? a.getUpdatedAt() : a.getCreatedAt();
            if (!userId.equals(a.getUserId()) || !a.isOpen() || a.getMergedIntoId() != null || a.hasProposal()
                    || last == null || !last.isBefore(threshold)) {
                continue;
            }
            long idle = java.time.Duration.between(last, now).toDays();
            a.setProposedStatus(TerminalActionStatus.ANNULE);
            a.setProposedReason("Sans geste depuis " + idle + " jours : périmée ?");
            a.setProposedAt(now);
            proposed.add(repository.save(a));
        }
        return proposed;
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

    // ---- F-175 / SF-175-02 : ce que l'agent fait de la liste ----

    /**
     * <b>Retrouve l'attente que l'agent désigne</b> — par son identifiant (lu dans la liste jointe au
     * tour) ou par sa clé — <b>dans ce terminal ou sur son poste</b>, jamais ailleurs.
     *
     * <p>Par clé : ce terminal d'abord, puis le poste (une ouverte de préférence). Le compte et le
     * terminal viennent <b>du tour</b> ; une attente d'un autre poste ou d'un autre compte est
     * introuvable, comme si elle n'existait pas.</p>
     */
    java.util.Optional<TerminalAction> resolveForAgent(UUID userId, Workspace workspace,
                                                       String key, String id) {
        UUID hostId = workspace.getHostId();
        if (id != null && !id.isBlank()) {
            UUID parsed;
            try {
                parsed = UUID.fromString(id.strip());
            } catch (IllegalArgumentException e) {
                return java.util.Optional.empty();
            }
            return repository.findByIdAndUserId(parsed, userId)
                    .filter(a -> a.getWorkspaceId().equals(workspace.getId())
                            || (hostId != null && hostId.equals(a.getHostId())));
        }
        if (key == null || key.isBlank()) {
            return java.util.Optional.empty();
        }
        String normalized = normalizeKey(key);
        var here = repository.findByUserIdAndWorkspaceIdAndDedupKey(userId, workspace.getId(), normalized);
        if (here.isPresent() || hostId == null) {
            return here;
        }
        List<TerminalAction> onHost = repository.findByUserIdAndHostIdAndDedupKeyOrderByCreatedAtDesc(
                userId, hostId, normalized);
        return onHost.stream().filter(TerminalAction::isOpen).findFirst()
                .or(() -> onHost.stream().findFirst());
    }

    /**
     * <b>L'agent fait avancer une attente</b> entre « À faire » et « Demandé » (F-175 / SF-175-02) —
     * typiquement après avoir envoyé la demande. Fermer n'est pas de son ressort : il le
     * <b>propose</b> ({@link #proposeClose}).
     */
    @Transactional
    public AgentChange agentUpdate(UUID userId, Workspace workspace, String key, String id,
                                   TerminalActionStatus target, String requestedTo, String channel) {
        return agentUpdate(userId, workspace, key, id, target, requestedTo, channel, Qualification.NONE);
    }

    /** Même chose ; la nature et « qui » donnés par l'agent sont posés (F-180 / SF-180-02). */
    @Transactional
    public AgentChange agentUpdate(UUID userId, Workspace workspace, String key, String id,
                                   TerminalActionStatus target, String requestedTo, String channel,
                                   Qualification qualification) {
        if (target != TerminalActionStatus.A_FAIRE && target != TerminalActionStatus.DEMANDE) {
            throw new InvalidTerminalActionException(
                    "status vaut A_FAIRE ou DEMANDE. Pour fermer, propose-le avec close_blocker.");
        }
        var found = resolveForAgent(userId, workspace, key, id);
        if (found.isEmpty()) {
            return new AgentChange(null, AgentChangeOutcome.UNKNOWN);
        }
        TerminalAction action = found.get();
        if (!action.isOpen()) {
            return new AgentChange(action, AgentChangeOutcome.ALREADY_CLOSED);
        }
        TerminalAction changed = changeStatus(userId, action.getWorkspaceId(), action.getId(),
                target, null, requestedTo, channel);
        if (qualification != null && !qualification.isEmpty()) {
            if (qualification.categorie() != null) {
                changed.setCategorie(qualification.categorie());
            }
            if (qualification.qui() != null) {
                changed.setQui(qualification.qui());
            }
            changed = repository.save(changed);
        }
        return new AgentChange(changed, AgentChangeOutcome.CHANGED);
    }

    // ---- F-211 / SF-211-01 : un message marqué envoyé fait partir la demande ----

    /**
     * Ce qu'un « Marquer envoyé » a changé sur une attente, pour pouvoir l'annuler : l'état d'avant et
     * l'instant de la modification (l'attente n'est restaurée que si elle n'a pas bougé depuis).
     */
    public record RequestMark(UUID actionId, String key, TerminalActionStatus previousStatus,
                              OffsetDateTime previousRequestedAt, String previousRequestedTo,
                              String previousChannel, OffsetDateTime markedAt) {
    }

    /**
     * <b>L'utilisateur a marqué envoyé un message qui sert l'attente {@code key}</b> (F-211 / SF-211-01) :
     * l'attente ouverte passe {@code DEMANDE}, résolue comme l'agent la résout (ce terminal, puis son
     * poste). Vide si la clé est inconnue ou l'attente fermée — un geste n'échoue jamais pour si peu.
     */
    @Transactional
    public java.util.Optional<RequestMark> markRequestedByKey(UUID userId, Workspace workspace, String key,
                                                              String requestedTo, String channel) {
        if (userId == null || workspace == null || key == null || key.isBlank()) {
            return java.util.Optional.empty();
        }
        var found = resolveForAgent(userId, workspace, key, null);
        if (found.isEmpty() || !found.get().isOpen()) {
            return java.util.Optional.empty();
        }
        TerminalAction action = found.get();
        TerminalActionStatus previousStatus = action.getStatus();
        OffsetDateTime previousAt = action.getRequestedAt();
        String previousTo = action.getRequestedTo();
        String previousChannel = action.getChannel();
        TerminalAction changed = changeStatus(userId, action.getWorkspaceId(), action.getId(),
                TerminalActionStatus.DEMANDE, null,
                truncate(requestedTo, MAX_REQUESTED_TO), truncate(channel, MAX_CHANNEL));
        return java.util.Optional.of(new RequestMark(changed.getId(), changed.getDedupKey(), previousStatus,
                previousAt, previousTo, previousChannel, changed.getUpdatedAt()));
    }

    /**
     * <b>Annule le passage à « Demandé »</b> d'un « Marquer envoyé » (F-211 / SF-211-01) — seulement si
     * l'attente n'a pas bougé entre-temps (toujours {@code DEMANDE}, même {@code updated_at}). Lecture
     * sous {@code user_id}. Vrai si l'attente a été remise dans son état d'avant.
     */
    @Transactional
    public boolean undoRequestMark(UUID userId, RequestMark mark) {
        if (userId == null || mark == null || mark.actionId() == null || mark.markedAt() == null) {
            return false;
        }
        var found = repository.findByIdAndUserId(mark.actionId(), userId);
        if (found.isEmpty()) {
            return false;
        }
        TerminalAction action = found.get();
        if (action.getStatus() != TerminalActionStatus.DEMANDE || action.getUpdatedAt() == null
                || !sameInstant(action.getUpdatedAt(), mark.markedAt())) {
            return false;
        }
        action.setStatus(mark.previousStatus() == null ? TerminalActionStatus.A_FAIRE : mark.previousStatus());
        action.setRequestedAt(mark.previousRequestedAt());
        action.setRequestedTo(mark.previousRequestedTo());
        action.setChannel(mark.previousChannel());
        action.setUpdatedAt(OffsetDateTime.now(clock));
        repository.save(action);
        return true;
    }

    /** Deux instants égaux à la milliseconde : la base tronque, la mémoire non. */
    private static boolean sameInstant(OffsetDateTime a, OffsetDateTime b) {
        return a.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                .equals(b.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() > max ? stripped.substring(0, max) : stripped;
    }

    /**
     * <b>L'agent propose une fermeture</b> (F-175 / SF-175-02, décision D5) : l'attente reste
     * <b>ouverte</b> ; la proposition attend le geste de l'utilisateur — [Confirmer] ou [Pas encore].
     *
     * @param cancelled vrai s'il propose « n'avait pas lieu d'être » plutôt que « c'est fait »
     */
    @Transactional
    public AgentChange proposeClose(UUID userId, Workspace workspace, String key, String id,
                                    String reason, boolean cancelled) {
        String cleanReason = optional(reason, MAX_REASON,
                "La raison tient en " + MAX_REASON + " caractères.");
        var found = resolveForAgent(userId, workspace, key, id);
        if (found.isEmpty()) {
            return new AgentChange(null, AgentChangeOutcome.UNKNOWN);
        }
        TerminalAction action = found.get();
        if (!action.isOpen()) {
            return new AgentChange(action, AgentChangeOutcome.ALREADY_CLOSED);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        action.setProposedStatus(cancelled ? TerminalActionStatus.ANNULE : TerminalActionStatus.FAIT);
        action.setProposedReason(cleanReason);
        action.setProposedAt(now);
        action.setUpdatedAt(now);
        return new AgentChange(repository.save(action), AgentChangeOutcome.PROPOSED);
    }

    /** Ce qu'un geste de l'agent sur la liste a donné, et l'attente concernée ({@code null} si inconnue). */
    public record AgentChange(TerminalAction action, AgentChangeOutcome outcome) {
    }

    /** Les issues d'un geste de l'agent (F-175 / SF-175-02). */
    public enum AgentChangeOutcome {
        /** L'état a changé. */
        CHANGED,
        /** La fermeture est proposée ; l'attente reste ouverte jusqu'au geste de l'utilisateur. */
        PROPOSED,
        /** Aucune attente de ce terminal ou de son poste ne répond à cette clé / cet identifiant. */
        UNKNOWN,
        /** Elle est déjà fermée — rien changé. */
        ALREADY_CLOSED
    }

    /**
     * <b>[Confirmer]</b> — l'utilisateur valide la fermeture proposée : l'attente prend l'état
     * proposé, avec la raison recopiée. Sans proposition en attente : sans effet.
     */
    @Transactional
    public TerminalAction confirmProposal(UUID userId, UUID workspaceId, UUID actionId) {
        TerminalAction action = require(userId, workspaceId, actionId);
        if (!action.hasProposal() || !action.isOpen()) {
            return action;
        }
        TerminalActionStatus target = action.getProposedStatus();
        String reason = action.getProposedReason();
        OffsetDateTime now = OffsetDateTime.now(clock);
        action.setStatus(target);
        action.setClosedReason(reason);
        action.setClosedAt(now);
        action.clearProposal();
        action.setUpdatedAt(now);
        action.setReviewPending(false);
        return repository.save(action);
    }

    /** <b>[Pas encore]</b> — l'utilisateur écarte la proposition : l'attente reste ouverte, telle quelle. */
    @Transactional
    public TerminalAction dismissProposal(UUID userId, UUID workspaceId, UUID actionId) {
        TerminalAction action = require(userId, workspaceId, actionId);
        if (!action.hasProposal()) {
            return action;
        }
        action.clearProposal();
        action.setUpdatedAt(OffsetDateTime.now(clock));
        return repository.save(action);
    }

    private static void reopenAs(TerminalAction action, TerminalActionStatus status) {
        action.setStatus(status);
        action.setClosedReason(null);
        action.setClosedAt(null);
        action.clearProposal();
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
        action.clearProposal();
        action.setUpdatedAt(now);
        action.setReviewPending(false);
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
