package fr.claudegateway.radar;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.radar.dto.RadarCorrectionRequests.CommitmentCorrectionRequest;
import fr.claudegateway.radar.dto.RadarCorrectionRequests.SubjectCorrectionRequest;
import fr.claudegateway.radar.dto.RadarViews.ClosureView;
import fr.claudegateway.radar.dto.RadarViews.CommitmentView;
import fr.claudegateway.radar.dto.RadarViews.CorrectionView;
import fr.claudegateway.radar.dto.RadarViews.PersonRef;
import fr.claudegateway.radar.dto.RadarViews.SubjectDetail;

/**
 * <b>L'exécution des outils Radar</b> (F-104 / SF-104-01) : lire et écrire le registre d'un seul poste à
 * partir de la parole de l'utilisateur.
 *
 * <h2>Une écriture est une correction souveraine</h2>
 *
 * <p>Chaque écriture passe par les services qui portent déjà les gestes de l'écran
 * ({@link RadarCorrectionService}, {@link RadarClosureService}, {@link RadarStructureService}) ou par le
 * registre ({@link RadarRegistry}) : elle est souveraine, journalisée, et <b>annulable</b>. Elle est de plus
 * <b>marquée de sa preuve</b> — la {@link RadarNote} tenue par l'appelant — rangée dans la chronologie du
 * sujet. C'est ce qui permet d'annuler une nouvelle entière (SF-104-02).</p>
 *
 * <h2>Tout ou rien</h2>
 *
 * <p>Chaque appel d'outil est <b>une</b> transaction : une écriture qui échoue à mi-chemin ne laisse ni
 * preuve, ni correction, ni lien. L'échec revient au modèle comme un résultat en erreur portant la phrase
 * qui dit quoi corriger, jamais comme une trace technique.</p>
 *
 * <h2>Isolation</h2>
 *
 * <p>Le périmètre vient de l'appelant, jamais du modèle. Un identifiant d'un autre poste est
 * « introuvable sur ce poste ».</p>
 */
@Service
public class RadarToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(RadarToolExecutor.class);

    /** Sujets rendus par la recherche. */
    static final int MAX_SUBJECTS = 15;
    static final int MAX_SENTENCES = 5;
    static final int MAX_COMMITMENTS = 10;
    /** Borne du résultat de la recherche, en caractères. */
    static final int MAX_RESULT_CHARS = 12_000;
    static final int MAX_QUERY_LENGTH = 200;

    /** Mots trop communs pour retrouver un sujet. */
    private static final Set<String> STOP_WORDS = Set.of("les", "des", "une", "sur", "pour", "avec", "dans",
            "sujet", "projet", "the", "and", "est", "qui", "que", "quoi", "mon", "mes", "son", "ses", "aux",
            "par", "pas", "plus", "chantier");

    /** Ce qu'une écriture a changé, dit par la gateway — jamais par le modèle. */
    public record Change(String kind, UUID subjectId, String subjectName, UUID correctionId, String sentence) {
    }

    /**
     * Issue d'un appel.
     *
     * @param content    ce que le modèle reçoit
     * @param error      vrai si rien n'a été fait
     * @param changes    ce qui a été écrit (vide pour une lecture ou un échec)
     * @param evidenceId la preuve rangée, s'il y a eu écriture
     */
    public record Outcome(String content, boolean error, List<Change> changes, UUID evidenceId) {

        static Outcome failure(String message) {
            return new Outcome(message, true, List.of(), null);
        }

        static Outcome read(String content) {
            return new Outcome(content, false, List.of(), null);
        }
    }

    private final RadarRegistry registry;
    private final RadarReadService readService;
    private final RadarCorrectionService corrections;
    private final RadarClosureService closure;
    private final RadarStructureService structure;
    private final RadarCorrectionJournal journal;
    private final RadarSubjectRepository subjects;
    private final RadarSubjectAliasRepository aliases;
    private final RadarCommitmentRepository commitments;
    private final RadarPersonRepository people;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public RadarToolExecutor(RadarRegistry registry, RadarReadService readService,
            RadarCorrectionService corrections, RadarClosureService closure, RadarStructureService structure,
            RadarCorrectionJournal journal, RadarSubjectRepository subjects, RadarSubjectAliasRepository aliases,
            RadarCommitmentRepository commitments, RadarPersonRepository people, ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        this.registry = registry;
        this.readService = readService;
        this.corrections = corrections;
        this.closure = closure;
        this.structure = structure;
        this.journal = journal;
        this.subjects = subjects;
        this.aliases = aliases;
        this.commitments = commitments;
        this.people = people;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * Exécute un outil Radar.
     *
     * @param scope périmètre du tour, vérifié par l'appelant
     * @param tool  nom de l'outil
     * @param input paramètres donnés par le modèle
     * @param note  la preuve des écritures (la parole de l'utilisateur)
     */
    public Outcome execute(RadarScope scope, String tool, JsonNode input, RadarNote note) {
        Objects.requireNonNull(scope, "scope");
        JsonNode params = input == null ? mapper.createObjectNode() : input;
        try {
            if (RadarToolCatalog.FIND_SUBJECT.equals(tool)) {
                return tx.execute(status -> find(scope, params));
            }
            if (!RadarToolCatalog.isWrite(tool)) {
                return Outcome.failure("Outil Radar inconnu : " + tool + ".");
            }
            if (note == null) {
                return Outcome.failure("Aucune parole de l'utilisateur à citer : rien n'est écrit.");
            }
            return tx.execute(status -> switch (tool) {
                case RadarToolCatalog.UPDATE_SUBJECT -> update(scope, params, note);
                case RadarToolCatalog.CLOSE_SUBJECT -> close(scope, params, note);
                case RadarToolCatalog.ADD_ENGAGEMENT -> addEngagement(scope, params, note);
                case RadarToolCatalog.MARK_ENGAGEMENT -> markEngagement(scope, params, note);
                default -> merge(scope, params, note);
            });
        } catch (RadarNotFoundException e) {
            return Outcome.failure(e.getMessage().replace(".", "") + " sur ce poste : rien n'est écrit. "
                    + "Retrouve l'identifiant avec " + RadarToolCatalog.FIND_SUBJECT + ".");
        } catch (InvalidRadarInputException | RadarStateConflictException | RadarSubjectMergedException
                | RadarEvidenceRequiredException | RadarCorrectionConflictException e) {
            return Outcome.failure(e.getMessage() + " Rien n'est écrit.");
        } catch (RuntimeException e) {
            log.warn("Outil Radar en échec (poste={}, outil={}) : {}", scope.hostId(), tool, e.getClass().getSimpleName());
            return Outcome.failure("L'écriture n'a pas abouti : le registre n'a rien retenu.");
        }
    }

    // ------------------------------------------------------------------------------------ lecture

    private Outcome find(RadarScope scope, JsonNode params) {
        String query = optionalText(params, "query", MAX_QUERY_LENGTH);
        boolean includeClosed = params.path("include_closed").asBoolean(false);
        String key = query == null ? "" : RadarText.key(query);
        List<String> tokens = key.isEmpty() ? List.of()
                : java.util.Arrays.stream(key.split("[\\s'’,;:.!?()«»\"-]+"))
                        .filter(t -> t.length() >= 3 && !STOP_WORDS.contains(t))
                        .distinct().toList();

        List<RadarSubject> live = subjects.findByUserIdAndHostId(scope.userId(), scope.hostId()).stream()
                .filter(s -> s.getMergedIntoId() == null)
                .toList();
        Map<UUID, List<String>> aliasesBySubject = live.isEmpty() ? Map.of()
                : aliases.findByUserIdAndHostIdAndSubjectIdIn(scope.userId(), scope.hostId(),
                                live.stream().map(RadarSubject::getId).toList()).stream()
                        .filter(a -> !a.isRejected())
                        .collect(Collectors.groupingBy(RadarSubjectAlias::getSubjectId,
                                Collectors.mapping(RadarSubjectAlias::getNormalized, Collectors.toList())));

        record Hit(RadarSubject subject, int score) {
        }
        List<Hit> hits = new ArrayList<>();
        for (RadarSubject subject : live) {
            List<String> names = new ArrayList<>(aliasesBySubject.getOrDefault(subject.getId(), List.of()));
            names.add(RadarText.key(subject.getName()));
            int score;
            if (key.isEmpty()) {
                if (!includeClosed && subject.getState() == RadarSubjectState.CLOSED) {
                    continue;
                }
                score = 0;
            } else if (names.stream().anyMatch(n -> n.contains(key))) {
                score = 1_000;
            } else {
                score = (int) tokens.stream().filter(t -> names.stream().anyMatch(n -> n.contains(t))).count();
                if (score == 0) {
                    continue;
                }
            }
            hits.add(new Hit(subject, score));
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed()
                .thenComparing(h -> h.subject().getLastActivityAt(), Comparator.nullsLast(Comparator.reverseOrder())));

        ObjectNode root = mapper.createObjectNode();
        root.put("query", query == null ? "" : query);
        root.put("found", hits.size());
        ArrayNode list = root.putArray("subjects");
        boolean truncated = hits.size() > MAX_SUBJECTS;
        int length = 0;
        for (Hit hit : hits.subList(0, Math.min(hits.size(), MAX_SUBJECTS))) {
            ObjectNode node = subjectNode(scope, hit.subject());
            int size = node.toString().length();
            if (length + size > MAX_RESULT_CHARS) {
                truncated = true;
                break;
            }
            length += size;
            list.add(node);
        }
        root.put("truncated", truncated);
        if (hits.isEmpty()) {
            root.put("note", key.isEmpty()
                    ? "Le registre de ce client ne suit encore aucun sujet ouvert."
                    : "Aucun sujet suivi ne correspond. Essaie un autre mot, ou crée le sujet si l'utilisateur "
                            + "en donne des nouvelles.");
        }
        return Outcome.read(root.toString());
    }

    private ObjectNode subjectNode(RadarScope scope, RadarSubject subject) {
        SubjectDetail detail = readService.subject(scope, subject.getId());
        ObjectNode node = mapper.createObjectNode();
        node.put("id", detail.id().toString());
        node.put("name", detail.name());
        node.put("state", detail.state().name());
        node.put("stateLabel", stateLabel(detail.state()));
        putText(node, "nextStep", detail.nextStep());
        putText(node, "dueDate", detail.dueDate() == null ? null : detail.dueDate().toString());
        putText(node, "lastActivityAt", detail.lastActivityAt() == null ? null : detail.lastActivityAt().toString());
        ArrayNode aliasNodes = node.putArray("aliases");
        detail.aliases().stream().filter(a -> !a.rejected()).forEach(a -> aliasNodes.add(a.alias()));
        ArrayNode summary = node.putArray("summary");
        detail.summary().stream().limit(MAX_SENTENCES).forEach(s -> summary.add(s.text()));
        ArrayNode open = node.putArray("openCommitments");
        detail.commitments().stream()
                .filter(c -> c.status().isPending() && !c.disowned())
                .limit(MAX_COMMITMENTS)
                .forEach(c -> open.add(commitmentNode(c)));
        return node;
    }

    private ObjectNode commitmentNode(CommitmentView c) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", c.id().toString());
        node.put("direction", c.direction().name());
        node.put("description", c.description());
        node.put("from", party(c.fromPerson()));
        node.put("to", party(c.toPerson()));
        if (c.otherPerson() != null) {
            node.put("other", c.otherPerson().displayName());
        }
        putText(node, "dueDate", c.dueDate() == null ? null : c.dueDate().toString());
        node.put("status", c.status().name());
        node.put("certainty", c.certainty().name());
        node.put("followUpDue", c.followUpDue());
        return node;
    }

    // ----------------------------------------------------------------------------------- écritures

    private Outcome update(RadarScope scope, JsonNode params, RadarNote note) {
        String subjectRef = optionalText(params, "subject_id", 64);
        String newSubjectName = optionalText(params, "new_subject_name", RadarSubject.MAX_NAME_LENGTH);
        String name = optionalText(params, "name", RadarSubject.MAX_NAME_LENGTH);
        RadarSubjectState state = params.hasNonNull("state") && !params.path("state").asText("").isBlank()
                ? openState(params.path("state").asText()) : null;
        boolean nextStepGiven = params.has("next_step") && !params.path("next_step").isNull();
        String nextStep = nextStepGiven ? RadarText.optional(params.path("next_step").asText(""),
                RadarSubject.MAX_NEXT_STEP_LENGTH, "next_step") : null;
        boolean dueGiven = params.has("due_date") && !params.path("due_date").isNull();
        LocalDate due = dueGiven ? date(params.path("due_date").asText("")) : null;

        List<Change> changes = new ArrayList<>();
        RadarSubject subject;
        String redirectedFrom = null;
        if (subjectRef == null) {
            if (newSubjectName == null) {
                throw new InvalidRadarInputException(
                        "Donne « subject_id » (trouvé par " + RadarToolCatalog.FIND_SUBJECT
                                + ") ou « new_subject_name » pour un sujet qui n'existe pas encore.");
            }
            RadarEvidence proof = proof(scope, note);
            subject = registry.createSubject(scope, newSubjectName, state, List.of(proof.getId()));
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("name", subject.getName());
            after.put("state", subject.getState().name());
            RadarCorrection created = journal.record(scope, subject.getId(), RadarCorrectionAction.Target.SUBJECT,
                    subject.getId(), RadarCorrectionAction.CREATE_SUBJECT, new LinkedHashMap<>(), after, proof.getId());
            changes.add(new Change("CREATE_SUBJECT", subject.getId(), subject.getName(), created.getId(),
                    "Nouveau sujet « " + subject.getName() + " » (" + stateLabel(subject.getState()) + ")"));
            state = null;
            applySubjectFields(scope, subject, name, null, nextStepGiven, nextStep, dueGiven, due, proof, changes);
            return written(changes, proof, null);
        }

        RadarSubject designated = registry.requireSubject(scope, uuid(subjectRef, "subject_id"));
        subject = registry.requireLiveSubject(scope, designated.getId());
        if (!subject.getId().equals(designated.getId())) {
            redirectedFrom = designated.getName();
        }
        boolean anyChange = (name != null && !name.equals(subject.getName()))
                || (state != null && state != subject.getState())
                || (nextStepGiven && !Objects.equals(nextStep, subject.getNextStep()))
                || (dueGiven && !Objects.equals(due, subject.getDueDate()));
        if (!anyChange) {
            return Outcome.read("Rien à changer : le registre dit déjà cela de « " + subject.getName()
                    + " ». Rien n'est écrit.");
        }
        RadarEvidence proof = proof(scope, note);
        applySubjectFields(scope, subject, name, state, nextStepGiven, nextStep, dueGiven, due, proof, changes);
        return written(changes, proof, redirectedFrom == null ? null
                : "« " + redirectedFrom + " » avait été fusionné : écrit sur « " + subject.getName() + " ».");
    }

    private void applySubjectFields(RadarScope scope, RadarSubject subject, String name, RadarSubjectState state,
            boolean nextStepGiven, String nextStep, boolean dueGiven, LocalDate due, RadarEvidence proof,
            List<Change> changes) {
        UUID id = subject.getId();
        if (state != null && state != subject.getState()) {
            CorrectionView view = corrections.correctSubject(scope, id,
                    new SubjectCorrectionRequest(RadarCorrectionAction.SET_STATE, null, state, null, null));
            tag(scope, view, proof);
            changes.add(new Change("SET_STATE", id, subject.getName(), view.id(),
                    "État de « " + subject.getName() + " » : " + stateLabel(state)));
        }
        if (nextStepGiven && !Objects.equals(nextStep, subject.getNextStep())) {
            CorrectionView view = corrections.correctSubject(scope, id,
                    new SubjectCorrectionRequest(RadarCorrectionAction.SET_NEXT_STEP, null, null, nextStep, null));
            tag(scope, view, proof);
            changes.add(new Change("SET_NEXT_STEP", id, subject.getName(), view.id(), nextStep == null
                    ? "Prochaine étape de « " + subject.getName() + " » effacée"
                    : "Prochaine étape de « " + subject.getName() + " » : " + nextStep));
        }
        if (dueGiven && !Objects.equals(due, subject.getDueDate())) {
            CorrectionView view = corrections.correctSubject(scope, id,
                    new SubjectCorrectionRequest(RadarCorrectionAction.SET_DUE_DATE, null, null, null, due));
            tag(scope, view, proof);
            changes.add(new Change("SET_DUE_DATE", id, subject.getName(), view.id(), due == null
                    ? "Échéance de « " + subject.getName() + " » effacée"
                    : "Échéance de « " + subject.getName() + " » : " + due));
        }
        if (name != null && !name.equals(subject.getName())) {
            String before = subject.getName();
            CorrectionView view = corrections.correctSubject(scope, id,
                    new SubjectCorrectionRequest(RadarCorrectionAction.RENAME, name, null, null, null));
            tag(scope, view, proof);
            changes.add(new Change("RENAME", id, name, view.id(), "« " + before + " » renommé « " + name + " »"));
        }
        registry.addToChronology(scope, registry.requireSubject(scope, id), List.of(proof));
    }

    private Outcome close(RadarScope scope, JsonNode params, RadarNote note) {
        RadarSubject designated = registry.requireSubject(scope, uuid(requiredText(params, "subject_id"), "subject_id"));
        RadarSubject subject = registry.requireLiveSubject(scope, designated.getId());
        if (subject.getState() == RadarSubjectState.CLOSED) {
            throw new RadarStateConflictException("Le sujet « " + subject.getName() + " » est déjà clos.");
        }
        RadarEvidence proof = proof(scope, note);
        registry.addToChronology(scope, subject, List.of(proof));
        ClosureView view = closure.close(scope, subject.getId());
        tag(scope, view.correction(), proof);
        List<Change> changes = List.of(new Change("CLOSE", subject.getId(), subject.getName(),
                view.correction().id(), "Sujet « " + subject.getName() + " » clos"));
        StringBuilder extra = new StringBuilder();
        if (!view.openCommitments().isEmpty()) {
            extra.append(view.openCommitments().size())
                    .append(" engagement(s) encore ouvert(s) sur ce sujet — NE LES FERME PAS toi-même, demande à "
                            + "l'utilisateur s'il faut les fermer aussi :");
            view.openCommitments().forEach(c -> extra.append("\n- ").append(c.description())
                    .append(" (id ").append(c.id()).append(')'));
        }
        return written(changes, proof, extra.isEmpty() ? null : extra.toString());
    }

    private Outcome addEngagement(RadarScope scope, JsonNode params, RadarNote note) {
        RadarSubject subject = registry.requireLiveSubject(scope,
                uuid(requiredText(params, "subject_id"), "subject_id"));
        RadarCommitmentDirection direction = enumValue(RadarCommitmentDirection.class,
                requiredText(params, "direction"), "direction");
        String description = RadarText.required(params.path("description").asText(null),
                RadarCommitment.MAX_DESCRIPTION_LENGTH, "description");
        LocalDate due = params.hasNonNull("due_date") ? date(params.path("due_date").asText("")) : null;
        RadarEvidence proof = proof(scope, note);
        UUID from = person(scope, optionalText(params, "from_person", RadarPerson.MAX_NAME_LENGTH));
        UUID to = person(scope, optionalText(params, "to_person", RadarPerson.MAX_NAME_LENGTH));
        UUID other = person(scope, optionalText(params, "other_person", RadarPerson.MAX_NAME_LENGTH));
        RadarCommitment commitment = registry.recordCommitment(scope, new RadarRegistry.CommitmentInput(
                subject.getId(), direction, description, from, to, other, due, false, RadarCertainty.CERTAIN,
                null, List.of(proof.getId())));
        commitment.setSovereign(true);
        commitments.save(commitment);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", commitment.getStatus().name());
        after.put("description", commitment.getDescription());
        RadarCorrection added = journal.record(scope, subject.getId(), RadarCorrectionAction.Target.COMMITMENT,
                commitment.getId(), RadarCorrectionAction.ADD_COMMITMENT, new LinkedHashMap<>(), after, proof.getId());
        String label = switch (direction) {
            case ME_TO_OTHER -> "À faire par moi";
            case OTHER_TO_ME -> "J'attends des autres";
            case INTRODUCTION -> "Mise en relation";
        };
        return written(List.of(new Change("ADD_COMMITMENT", subject.getId(), subject.getName(), added.getId(),
                label + " sur « " + subject.getName() + " » : " + description
                        + (due == null ? "" : " (pour le " + due + ")"))), proof, null);
    }

    private Outcome markEngagement(RadarScope scope, JsonNode params, RadarNote note) {
        RadarCommitment commitment = registry.requireCommitment(scope,
                uuid(requiredText(params, "commitment_id"), "commitment_id"));
        String status = requiredText(params, "status").toUpperCase(Locale.ROOT);
        RadarCorrectionAction action = switch (status) {
            case "DONE" -> RadarCorrectionAction.DONE;
            case "ABANDON" -> RadarCorrectionAction.ABANDON;
            case "POSTPONE" -> RadarCorrectionAction.POSTPONE;
            case "REOPEN" -> RadarCorrectionAction.REOPEN;
            case "NOT_MINE" -> RadarCorrectionAction.NOT_MINE;
            default -> throw new InvalidRadarInputException(
                    "« status » vaut DONE, ABANDON, POSTPONE, REOPEN ou NOT_MINE.");
        };
        LocalDate due = params.hasNonNull("due_date") ? date(params.path("due_date").asText("")) : null;
        boolean unchanged = switch (action) {
            case DONE -> commitment.getStatus() == RadarCommitmentStatus.KEPT;
            case ABANDON -> commitment.getStatus() == RadarCommitmentStatus.ABANDONED;
            case REOPEN -> commitment.getStatus() == RadarCommitmentStatus.OPEN && !commitment.isDisowned();
            case NOT_MINE -> commitment.isDisowned();
            default -> false;
        };
        if (unchanged) {
            return Outcome.read("Rien à changer : l'engagement « " + commitment.getDescription()
                    + " » est déjà ainsi. Rien n'est écrit.");
        }
        RadarEvidence proof = proof(scope, note);
        CorrectionView view = corrections.correctCommitment(scope, commitment.getId(),
                new CommitmentCorrectionRequest(action, due));
        tag(scope, view, proof);
        RadarSubject subject = registry.requireSubject(scope, commitment.getSubjectId());
        registry.justify(scope, subject, RadarLinkKind.COMMITMENT, commitment.getId(), List.of(proof), false);
        String word = switch (action) {
            case DONE -> "tenu";
            case ABANDON -> "abandonné";
            case POSTPONE -> "reporté au " + due;
            case REOPEN -> "rouvert";
            default -> "pas le mien";
        };
        return written(List.of(new Change("MARK_COMMITMENT", subject.getId(), subject.getName(), view.id(),
                "Engagement « " + commitment.getDescription() + " » : " + word)), proof, null);
    }

    private Outcome merge(RadarScope scope, JsonNode params, RadarNote note) {
        RadarSubject source = registry.requireSubject(scope,
                uuid(requiredText(params, "source_subject_id"), "source_subject_id"));
        RadarSubject into = registry.requireSubject(scope,
                uuid(requiredText(params, "into_subject_id"), "into_subject_id"));
        RadarEvidence proof = proof(scope, note);
        CorrectionView view = structure.merge(scope, source.getId(), into.getId());
        tag(scope, view, proof);
        registry.addToChronology(scope, registry.requireSubject(scope, into.getId()), List.of(proof));
        return written(List.of(new Change("MERGE", into.getId(), into.getName(), view.id(),
                "« " + source.getName() + " » fusionné dans « " + into.getName() + " »")), proof, null);
    }

    // ------------------------------------------------------------------------------------ aides

    private void tag(RadarScope scope, CorrectionView view, RadarEvidence proof) {
        journal.attachEvidence(scope, view.id(), proof.getId());
    }

    private Outcome written(List<Change> changes, RadarEvidence proof, String extra) {
        StringBuilder text = new StringBuilder("Écrit dans le registre, d'après la parole de l'utilisateur :");
        changes.forEach(change -> text.append("\n- ").append(change.sentence()));
        if (extra != null) {
            text.append('\n').append(extra);
        }
        text.append("\nC'est annulable depuis la chronologie du sujet.");
        return new Outcome(text.toString(), false, List.copyOf(changes), proof.getId());
    }

    /**
     * La preuve de l'écriture : la note enregistrée (ou retrouvée par son identifiant de source), son auteur
     * rattaché à l'annuaire — une personne de même nom si elle existe, sinon créée sous la clé de la note.
     */
    private RadarEvidence proof(RadarScope scope, RadarNote note) {
        UUID author = null;
        if (note.hasAuthor()) {
            author = existingPerson(scope, note.authorName());
            if (author == null) {
                author = registry.upsertPerson(scope, note.authorKey(), note.authorName(), null).getId();
            }
        }
        return registry.recordEvidence(scope, note.input(author));
    }

    /** Une personne désignée par son nom : retrouvée dans l'annuaire du poste, sinon créée. */
    private UUID person(RadarScope scope, String name) {
        if (name == null) {
            return null;
        }
        UUID existing = existingPerson(scope, name);
        return existing != null ? existing
                : registry.upsertPerson(scope, "note:" + RadarText.key(name), name, null).getId();
    }

    private UUID existingPerson(RadarScope scope, String name) {
        String key = RadarText.key(name);
        return people.findByUserIdAndHostIdOrderByDisplayNameAsc(scope.userId(), scope.hostId()).stream()
                .filter(p -> RadarText.key(p.getDisplayName()).equals(key))
                .map(RadarPerson::getId)
                .findFirst()
                .orElse(null);
    }

    static String stateLabel(RadarSubjectState state) {
        return switch (state) {
            case NEW -> "nouveau";
            case ADVANCING -> "avance";
            case WAITING -> "en attente";
            case BLOCKED -> "bloqué";
            case DORMANT -> "en sommeil";
            case CLOSE_PROPOSED -> "clos ?";
            case CLOSED -> "clos";
        };
    }

    private static String party(PersonRef person) {
        return person == null ? "moi" : person.displayName();
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static RadarSubjectState openState(String raw) {
        RadarSubjectState state = enumValue(RadarSubjectState.class, raw, "state");
        if (!state.isOpenWork()) {
            throw new InvalidRadarInputException("« state » vaut NEW, ADVANCING, WAITING ou BLOCKED ; pour clore, "
                    + RadarToolCatalog.CLOSE_SUBJECT + ".");
        }
        return state;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String raw, String field) {
        try {
            return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidRadarInputException("« " + field + " » a une valeur inconnue : " + raw + ".");
        }
    }

    private static LocalDate date(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidRadarInputException("Date illisible : « " + value + " » (attendu AAAA-MM-JJ).");
        }
    }

    private static UUID uuid(String raw, String field) {
        try {
            return UUID.fromString(raw.strip());
        } catch (IllegalArgumentException e) {
            throw new RadarNotFoundException("« " + field + " » introuvable.");
        }
    }

    private static String requiredText(JsonNode params, String field) {
        String value = params.path(field).asText("").strip();
        if (value.isEmpty()) {
            throw new InvalidRadarInputException("Le paramètre « " + field + " » est requis.");
        }
        return value;
    }

    private static String optionalText(JsonNode params, String field, int max) {
        return RadarText.optional(params.path(field).isMissingNode() || params.path(field).isNull()
                ? null : params.path(field).asText(""), max, field);
    }
}
