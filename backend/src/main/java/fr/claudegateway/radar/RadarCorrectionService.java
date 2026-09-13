package fr.claudegateway.radar;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.radar.dto.RadarCorrectionRequests.CommitmentCorrectionRequest;
import fr.claudegateway.radar.dto.RadarCorrectionRequests.SubjectCorrectionRequest;
import fr.claudegateway.radar.dto.RadarViews.CorrectionView;

/**
 * Les <b>corrections souveraines</b> (F-99 / SF-99-02) : l'utilisateur a le dernier mot.
 *
 * <p>Chaque correction fait trois choses, dans une seule transaction : elle écrit la valeur, elle
 * pose la <b>marque de souveraineté</b> qui interdit à une synchro de la réécrire
 * ({@link RadarRegistry}), et elle ajoute une ligne au <b>journal</b> avec les valeurs avant et après
 * — <b>des seuls champs touchés</b>. L'annulation rétablit ces champs et rien d'autre.</p>
 *
 * <p>Une correction de l'utilisateur ne demande pas de preuve : elle <b>est</b> sa propre
 * justification, et le journal la montre dans la chronologie.</p>
 */
@Service
@Transactional
public class RadarCorrectionService {

    static final String ALIAS_ID = "aliasId";

    private final RadarRegistry registry;
    private final RadarSubjectRepository subjects;
    private final RadarCommitmentRepository commitments;
    private final RadarSubjectAliasRepository aliases;
    private final RadarCorrectionRepository corrections;
    private final RadarCorrectionJournal journalWriter;
    private final RadarStructureService structure;

    public RadarCorrectionService(RadarRegistry registry, RadarSubjectRepository subjects,
            RadarCommitmentRepository commitments, RadarSubjectAliasRepository aliases,
            RadarCorrectionRepository corrections, RadarCorrectionJournal journalWriter,
            RadarStructureService structure) {
        this.registry = registry;
        this.subjects = subjects;
        this.commitments = commitments;
        this.aliases = aliases;
        this.corrections = corrections;
        this.journalWriter = journalWriter;
        this.structure = structure;
    }

    // ------------------------------------------------------------------------------------ sujet

    /** Corrige un sujet. */
    public CorrectionView correctSubject(RadarScope scope, UUID subjectId, SubjectCorrectionRequest request) {
        RadarCorrectionAction action = requireAction(request == null ? null : request.action(),
                RadarCorrectionAction.Target.SUBJECT);
        RadarSubject subject = registry.requireSubject(scope, subjectId);
        if (subject.getMergedIntoId() != null) {
            throw new RadarSubjectMergedException("Ce sujet a été fusionné : corrigez le sujet qui l'a absorbé.");
        }
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        switch (action) {
            case RENAME -> {
                String name = RadarText.required(request.name(), RadarSubject.MAX_NAME_LENGTH, "name");
                before.put("name", subject.getName());
                before.put("nameSovereign", subject.isNameSovereign());
                UUID aliasId = learnAlias(scope, subject, subject.getName(), name);
                subject.setName(name);
                subject.setNameSovereign(true);
                after.put("name", name);
                after.put("nameSovereign", true);
                after.put(ALIAS_ID, aliasId == null ? null : aliasId.toString());
            }
            case SET_STATE -> {
                RadarSubjectState state = request.state();
                if (state == null || !state.isOpenWork()) {
                    throw new InvalidRadarInputException(
                            "L'état dit par l'utilisateur est NEW, ADVANCING, WAITING ou BLOCKED ; la clôture a son geste.");
                }
                before.put("state", subject.getState().name());
                before.put("stateSovereign", subject.isStateSovereign());
                subject.setState(state);
                subject.setStateSovereign(true);
                after.put("state", state.name());
                after.put("stateSovereign", true);
            }
            case SET_NEXT_STEP -> {
                String next = RadarText.optional(request.nextStep(), RadarSubject.MAX_NEXT_STEP_LENGTH, "next_step");
                before.put("nextStep", subject.getNextStep());
                before.put("nextStepSovereign", subject.isNextStepSovereign());
                subject.setNextStep(next);
                subject.setNextStepSovereign(true);
                after.put("nextStep", next);
                after.put("nextStepSovereign", true);
            }
            case SET_DUE_DATE -> {
                before.put("dueDate", text(subject.getDueDate()));
                before.put("dueDateSovereign", subject.isDueDateSovereign());
                subject.setDueDate(request.dueDate());
                subject.setDueDateSovereign(true);
                after.put("dueDate", text(request.dueDate()));
                after.put("dueDateSovereign", true);
            }
            default -> throw new InvalidRadarInputException("Action inconnue pour un sujet.");
        }
        subjects.save(subject);
        return journalWriter.view(journalWriter.record(scope, subject.getId(), RadarCorrectionAction.Target.SUBJECT, subject.getId(),
                action, before, after));
    }

    // ------------------------------------------------------------------------------- engagement

    /** Corrige un engagement ; toute correction le rend souverain. */
    public CorrectionView correctCommitment(RadarScope scope, UUID commitmentId,
            CommitmentCorrectionRequest request) {
        RadarCorrectionAction action = requireAction(request == null ? null : request.action(),
                RadarCorrectionAction.Target.COMMITMENT);
        RadarCommitment commitment = registry.requireCommitment(scope, commitmentId);
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        before.put("sovereign", commitment.isSovereign());
        switch (action) {
            case DONE -> setStatus(commitment, RadarCommitmentStatus.KEPT, before, after);
            case ABANDON -> setStatus(commitment, RadarCommitmentStatus.ABANDONED, before, after);
            case REOPEN -> setStatus(commitment, RadarCommitmentStatus.OPEN, before, after);
            case POSTPONE -> {
                if (request.dueDate() == null) {
                    throw new InvalidRadarInputException("Reporter demande une nouvelle échéance.");
                }
                setStatus(commitment, RadarCommitmentStatus.POSTPONED, before, after);
                before.put("dueDate", text(commitment.getDueDate()));
                before.put("dueDeduced", commitment.isDueDeduced());
                commitment.setDueDate(request.dueDate());
                commitment.setDueDeduced(false);
                after.put("dueDate", text(request.dueDate()));
                after.put("dueDeduced", false);
            }
            case NOT_MINE -> {
                before.put("disowned", commitment.isDisowned());
                commitment.setDisowned(true);
                after.put("disowned", true);
            }
            case CONFIRM -> {
                before.put("certainty", commitment.getCertainty().name());
                before.put("disowned", commitment.isDisowned());
                commitment.setCertainty(RadarCertainty.CERTAIN);
                commitment.setDisowned(false);
                after.put("certainty", RadarCertainty.CERTAIN.name());
                after.put("disowned", false);
            }
            default -> throw new InvalidRadarInputException("Action inconnue pour un engagement.");
        }
        commitment.setSovereign(true);
        after.put("sovereign", true);
        commitments.save(commitment);
        return journalWriter.view(journalWriter.record(scope, commitment.getSubjectId(), RadarCorrectionAction.Target.COMMITMENT,
                commitment.getId(), action, before, after));
    }

    // ---------------------------------------------------------------------------------- journal

    /** Le journal du poste, ou d'un sujet, plus récent d'abord. */
    @Transactional(readOnly = true)
    public List<CorrectionView> journal(RadarScope scope, UUID subjectId) {
        List<RadarCorrection> rows = subjectId == null
                ? corrections.findByUserIdAndHostIdOrderByCreatedAtDesc(scope.userId(), scope.hostId())
                : corrections.findByUserIdAndHostIdAndSubjectIdOrderByCreatedAtDesc(
                        scope.userId(), scope.hostId(), subjectId);
        return rows.stream().map(journalWriter::view).toList();
    }

    /**
     * Annule une correction : rétablit les champs qu'elle avait touchés.
     *
     * @throws RadarCorrectionConflictException si elle est déjà annulée, ou si une correction plus
     *                                          récente et active a recouvert l'un de ses champs
     */
    public CorrectionView undo(RadarScope scope, UUID correctionId) {
        RadarCorrection correction = corrections.findByIdAndUserIdAndHostId(correctionId, scope.userId(), scope.hostId())
                .orElseThrow(() -> new RadarNotFoundException("Correction introuvable."));
        if (correction.getUndoneAt() != null) {
            throw new RadarCorrectionConflictException("Cette correction est déjà annulée.");
        }
        if (correction.getAction() == RadarCorrectionAction.MERGE
                || correction.getAction() == RadarCorrectionAction.SPLIT) {
            structure.undo(scope, correction);
            correction.setUndoneAt(OffsetDateTime.now());
            return journalWriter.view(corrections.save(correction));
        }
        Map<String, Object> before = journalWriter.parse(correction.getBeforeValues());
        List<RadarCorrection> others = corrections.findByUserIdAndHostIdAndTargetIdAndUndoneAtIsNull(
                        scope.userId(), scope.hostId(), correction.getTargetId()).stream()
                .filter(other -> !other.getId().equals(correction.getId()))
                .toList();
        Set<String> valueFields = valueFields(before.keySet());
        Set<String> flagsHeldElsewhere = new HashSet<>();
        for (RadarCorrection other : others) {
            Set<String> otherFields = journalWriter.parse(other.getBeforeValues()).keySet();
            boolean later = !other.getCreatedAt().isBefore(correction.getCreatedAt());
            if (later && valueFields.stream().anyMatch(otherFields::contains)) {
                throw new RadarCorrectionConflictException(
                        "Une correction plus récente a redit la même chose : annulez-la d'abord.");
            }
            otherFields.stream().filter(RadarCorrectionService::isFlag).forEach(flagsHeldElsewhere::add);
        }
        before.keySet().removeIf(field -> isFlag(field) && flagsHeldElsewhere.contains(field));

        if (correction.getTargetKind() == RadarCorrectionAction.Target.SUBJECT) {
            RadarSubject subject = registry.requireSubject(scope, correction.getTargetId());
            restoreSubject(subject, before);
            subjects.save(subject);
            Object aliasId = journalWriter.parse(correction.getAfterValues()).get(ALIAS_ID);
            if (aliasId != null) {
                aliases.findById(UUID.fromString(aliasId.toString()))
                        .filter(alias -> alias.getUserId().equals(scope.userId())
                                && alias.getHostId().equals(scope.hostId()))
                        .ifPresent(aliases::delete);
            }
        } else {
            RadarCommitment commitment = registry.requireCommitment(scope, correction.getTargetId());
            restoreCommitment(commitment, before);
            commitments.save(commitment);
        }
        correction.setUndoneAt(OffsetDateTime.now());
        return journalWriter.view(corrections.save(correction));
    }

    // ------------------------------------------------------------------------------------ aides

    private static RadarCorrectionAction requireAction(RadarCorrectionAction action,
            RadarCorrectionAction.Target target) {
        if (action == null) {
            throw new InvalidRadarInputException("L'action est requise.");
        }
        if (action.target() != target) {
            throw new InvalidRadarInputException("L'action " + action + " ne porte pas sur ce genre d'objet.");
        }
        return action;
    }

    private static void setStatus(RadarCommitment commitment, RadarCommitmentStatus status,
            Map<String, Object> before, Map<String, Object> after) {
        before.put("status", commitment.getStatus().name());
        commitment.setStatus(status);
        after.put("status", status.name());
    }

    /** L'ancien nom devient un alias, s'il n'en est pas déjà un et diffère du nouveau. */
    private UUID learnAlias(RadarScope scope, RadarSubject subject, String oldName, String newName) {
        String key = RadarText.key(oldName);
        if (key.isEmpty() || key.equals(RadarText.key(newName))) {
            return null;
        }
        boolean known = aliases.findByUserIdAndHostIdAndSubjectIdOrderByCreatedAtAsc(
                        scope.userId(), scope.hostId(), subject.getId()).stream()
                .anyMatch(alias -> alias.getNormalized().equals(key));
        if (known) {
            return null;
        }
        return aliases.save(RadarSubjectAlias.builder()
                .userId(scope.userId()).hostId(scope.hostId()).subjectId(subject.getId())
                .alias(oldName).normalized(key).origin(RadarAliasOrigin.USER).build()).getId();
    }

    private static void restoreSubject(RadarSubject subject, Map<String, Object> values) {
        values.forEach((field, value) -> {
            switch (field) {
                case "name" -> subject.setName((String) value);
                case "nameSovereign" -> subject.setNameSovereign(Boolean.TRUE.equals(value));
                case "state" -> subject.setState(RadarSubjectState.valueOf((String) value));
                case "stateSovereign" -> subject.setStateSovereign(Boolean.TRUE.equals(value));
                case "nextStep" -> subject.setNextStep((String) value);
                case "nextStepSovereign" -> subject.setNextStepSovereign(Boolean.TRUE.equals(value));
                case "dueDate" -> subject.setDueDate(date(value));
                case "dueDateSovereign" -> subject.setDueDateSovereign(Boolean.TRUE.equals(value));
                default -> {
                    // champ inconnu : ignoré (une version ultérieure du journal)
                }
            }
        });
    }

    private static void restoreCommitment(RadarCommitment commitment, Map<String, Object> values) {
        values.forEach((field, value) -> {
            switch (field) {
                case "status" -> commitment.setStatus(RadarCommitmentStatus.valueOf((String) value));
                case "dueDate" -> commitment.setDueDate(date(value));
                case "dueDeduced" -> commitment.setDueDeduced(Boolean.TRUE.equals(value));
                case "certainty" -> commitment.setCertainty(RadarCertainty.valueOf((String) value));
                case "disowned" -> commitment.setDisowned(Boolean.TRUE.equals(value));
                case "sovereign" -> commitment.setSovereign(Boolean.TRUE.equals(value));
                default -> {
                    // champ inconnu : ignoré
                }
            }
        });
    }

    static boolean isFlag(String field) {
        return field.equals("sovereign") || field.endsWith("Sovereign");
    }

    private static Set<String> valueFields(Set<String> fields) {
        Set<String> values = new HashSet<>();
        fields.stream().filter(field -> !isFlag(field) && !field.equals(ALIAS_ID)).forEach(values::add);
        return values;
    }

    private static String text(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static LocalDate date(Object value) {
        return value == null ? null : LocalDate.parse(value.toString());
    }
}
