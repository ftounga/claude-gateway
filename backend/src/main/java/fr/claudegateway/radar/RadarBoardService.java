package fr.claudegateway.radar;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.radar.dto.RadarBoardViews.BoardCommitment;
import fr.claudegateway.radar.dto.RadarBoardViews.BoardSubject;
import fr.claudegateway.radar.dto.RadarBoardViews.BoardView;
import fr.claudegateway.radar.dto.RadarViews.CommitmentView;
import fr.claudegateway.radar.dto.RadarViews.SubjectSummary;

/**
 * <b>Les trois colonnes</b> de l'onglet Radar (F-102 / SF-102-02) : <i>À faire par moi</i> · <i>Sujets en
 * cours</i> · <i>J'attends des autres</i>, rangées pour que ce qui presse passe devant.
 *
 * <p>Lecture seule : les gestes passent par les corrections souveraines de F-99. Toutes les lectures portent
 * {@code user_id} et {@code host_id}.</p>
 */
@Service
@Transactional(readOnly = true)
public class RadarBoardService {

    /** Personnes écrites sous un sujet. */
    static final int MAX_PEOPLE = 3;

    private final RadarReadService readService;
    private final RadarSubjectFactRepository facts;
    private final RadarSubjectRoleRepository roles;
    private final RadarPersonRepository people;
    private final RadarEvidenceRepository evidence;
    private final RadarEvidenceLinkRepository links;
    private final Clock clock;

    public RadarBoardService(RadarReadService readService, RadarSubjectFactRepository facts,
            RadarSubjectRoleRepository roles, RadarPersonRepository people, RadarEvidenceRepository evidence,
            RadarEvidenceLinkRepository links, Clock clock) {
        this.readService = readService;
        this.facts = facts;
        this.roles = roles;
        this.people = people;
        this.evidence = evidence;
        this.links = links;
        this.clock = clock;
    }

    public BoardView board(RadarScope scope) {
        LocalDate today = OffsetDateTime.now(clock).atZoneSameInstant(ZoneOffset.UTC).toLocalDate();

        List<CommitmentView> pending = readService.commitments(scope, null, null, false, false).stream()
                .filter(c -> c.status() != null && c.status().isPending())
                .toList();
        Set<UUID> evidenceIds = new HashSet<>();
        pending.forEach(c -> evidenceIds.addAll(c.evidenceIds()));
        Map<UUID, RadarEvidence> proofs = evidenceIds.isEmpty() ? Map.of()
                : evidence.findByUserIdAndHostIdAndIdIn(scope.userId(), scope.hostId(), evidenceIds).stream()
                        .collect(Collectors.toMap(RadarEvidence::getId, Function.identity()));

        List<BoardCommitment> toDo = pending.stream()
                .filter(c -> c.direction() == RadarCommitmentDirection.ME_TO_OTHER
                        || c.direction() == RadarCommitmentDirection.INTRODUCTION)
                .map(c -> item(c, proofs, today))
                .sorted(urgency())
                .toList();
        List<BoardCommitment> waiting = pending.stream()
                .filter(c -> c.direction() == RadarCommitmentDirection.OTHER_TO_ME)
                .map(c -> item(c, proofs, today))
                .sorted(urgency())
                .toList();

        return new BoardView(toDo, subjects(scope), waiting);
    }

    // --------------------------------------------------------------------------------- sujets

    private List<BoardSubject> subjects(RadarScope scope) {
        List<SubjectSummary> summaries = readService.subjects(scope, null, false, null);
        if (summaries.isEmpty()) {
            return List.of();
        }
        Map<UUID, Long> sources = links.findByUserIdAndHostId(scope.userId(), scope.hostId()).stream()
                .filter(l -> l.getTargetKind() == RadarLinkKind.CHRONOLOGY)
                .collect(Collectors.groupingBy(RadarEvidenceLink::getSubjectId,
                        Collectors.mapping(RadarEvidenceLink::getEvidenceId,
                                Collectors.collectingAndThen(Collectors.toSet(), s -> (long) s.size()))));
        Map<UUID, RadarPerson> directory = people.findByUserIdAndHostIdOrderByDisplayNameAsc(scope.userId(),
                scope.hostId()).stream().collect(Collectors.toMap(RadarPerson::getId, Function.identity()));
        Map<UUID, List<RadarSubjectRole>> rolesBySubject = roles.findByUserIdAndHostId(scope.userId(), scope.hostId())
                .stream().collect(Collectors.groupingBy(RadarSubjectRole::getSubjectId));

        return summaries.stream()
                .map(s -> new BoardSubject(s, line(scope, s),
                        sources.getOrDefault(s.id(), 0L).intValue(),
                        rolesBySubject.getOrDefault(s.id(), List.of()).stream()
                                .map(r -> directory.get(r.getPersonId()))
                                .filter(Objects::nonNull)
                                .map(RadarPerson::getDisplayName)
                                .distinct()
                                .sorted(String.CASE_INSENSITIVE_ORDER)
                                .limit(MAX_PEOPLE)
                                .toList()))
                .toList();
    }

    /** La première phrase du résumé (prouvée), sinon la prochaine étape. */
    private String line(RadarScope scope, SubjectSummary s) {
        return facts.findByUserIdAndHostIdAndSubjectIdOrderByPositionAsc(scope.userId(), scope.hostId(), s.id())
                .stream()
                .map(RadarSubjectFact::getText)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse(s.nextStep() == null || s.nextStep().isBlank() ? null : s.nextStep());
    }

    // ---------------------------------------------------------------------------- engagements

    static BoardCommitment item(CommitmentView c, Map<UUID, RadarEvidence> proofs, LocalDate today) {
        RadarEvidence latest = c.evidenceIds().stream()
                .map(proofs::get)
                .filter(Objects::nonNull)
                .max(Comparator.comparing(RadarEvidence::getOccurredAt))
                .orElse(null);
        boolean question = c.certainty() == RadarCertainty.PROBABLE && !c.sovereign();
        boolean reached = c.dueDate() != null && !c.dueDate().isAfter(today);
        boolean due = c.direction() == RadarCommitmentDirection.OTHER_TO_ME
                ? reached || (c.followUpDueOn() != null && !c.followUpDueOn().isAfter(today))
                : reached;
        int overdue = c.dueDate() != null && c.dueDate().isBefore(today)
                ? (int) ChronoUnit.DAYS.between(c.dueDate(), today) : 0;
        return new BoardCommitment(c, latest == null ? null : latest.getSource(),
                latest == null ? null : latest.getOccurredAt(), latest == null ? null : latest.getDeepLink(),
                question, due, overdue);
    }

    /** Dus d'abord, puis questions, puis par échéance (sans échéance à la fin). */
    static Comparator<BoardCommitment> urgency() {
        return Comparator.comparing(BoardCommitment::due).reversed()
                .thenComparing(Comparator.comparing(BoardCommitment::question).reversed())
                .thenComparing(b -> b.commitment().dueDate(), Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
                .thenComparing(b -> b.commitment().createdAt(), Comparator.nullsLast(Comparator.<OffsetDateTime>naturalOrder()));
    }
}
