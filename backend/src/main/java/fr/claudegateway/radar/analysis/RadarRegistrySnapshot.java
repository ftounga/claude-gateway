package fr.claudegateway.radar.analysis;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.radar.RadarCommitment;
import fr.claudegateway.radar.RadarCommitmentDirection;
import fr.claudegateway.radar.RadarCommitmentRepository;
import fr.claudegateway.radar.RadarCommitmentStatus;
import fr.claudegateway.radar.RadarPerson;
import fr.claudegateway.radar.RadarPersonRepository;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.analysis.RadarExtractionContext.CommitmentSnapshot;
import fr.claudegateway.radar.RadarSubject;
import fr.claudegateway.radar.RadarSubjectAlias;
import fr.claudegateway.radar.RadarSubjectAliasRepository;
import fr.claudegateway.radar.RadarSubjectFactRepository;
import fr.claudegateway.radar.RadarSubjectRepository;
import fr.claudegateway.radar.RadarSubjectState;
import fr.claudegateway.radar.analysis.RadarExtractionContext.FactSnapshot;
import fr.claudegateway.radar.analysis.RadarExtractionContext.SubjectSnapshot;

/**
 * Lit ce que l'extraction doit savoir du registre d'<b>un</b> poste (F-101 / SF-101-03) : sujets non
 * fusionnés, alias acceptés et refusés, phrases de résumé. Toute lecture porte {@code user_id} et
 * {@code host_id} ; seules les phrases des sujets dont le résumé sera montré sont chargées.
 */
@Component
public class RadarRegistrySnapshot {

    private final RadarSubjectRepository subjects;
    private final RadarSubjectAliasRepository aliases;
    private final RadarSubjectFactRepository facts;
    private final RadarCommitmentRepository commitments;
    private final RadarPersonRepository people;

    public RadarRegistrySnapshot(RadarSubjectRepository subjects, RadarSubjectAliasRepository aliases,
            RadarSubjectFactRepository facts, RadarCommitmentRepository commitments, RadarPersonRepository people) {
        this.subjects = subjects;
        this.aliases = aliases;
        this.facts = facts;
        this.commitments = commitments;
        this.people = people;
    }

    @Transactional(readOnly = true)
    public List<SubjectSnapshot> read(RadarScope scope) {
        Comparator<RadarSubject> recent = Comparator.comparing(RadarSubject::getLastActivityAt,
                Comparator.nullsLast(Comparator.reverseOrder()));
        List<RadarSubject> live = subjects.findByUserIdAndHostId(scope.userId(), scope.hostId()).stream()
                .filter(s -> s.getMergedIntoId() == null)
                .sorted(recent)
                .toList();
        List<RadarSubject> open = live.stream().filter(s -> s.getState() != RadarSubjectState.CLOSED)
                .limit(RadarExtractionContext.MAX_OPEN_SUBJECTS).toList();
        List<RadarSubject> closed = live.stream().filter(s -> s.getState() == RadarSubjectState.CLOSED)
                .limit(RadarExtractionContext.MAX_CLOSED_SUBJECTS).toList();
        List<RadarSubject> kept = new java.util.ArrayList<>(open);
        kept.addAll(closed);
        Map<UUID, List<RadarSubjectAlias>> aliasesBySubject = kept.isEmpty() ? Map.of()
                : aliases.findByUserIdAndHostIdAndSubjectIdIn(scope.userId(), scope.hostId(),
                        kept.stream().map(RadarSubject::getId).toList()).stream()
                        .collect(Collectors.groupingBy(RadarSubjectAlias::getSubjectId));
        java.util.Set<UUID> withSummary = open.stream().limit(RadarExtractionContext.MAX_SUMMARIES_SHOWN)
                .map(RadarSubject::getId).collect(Collectors.toSet());
        Map<UUID, String> names = people.findByUserIdAndHostIdOrderByDisplayNameAsc(scope.userId(), scope.hostId())
                .stream().collect(Collectors.toMap(RadarPerson::getId, RadarPerson::getDisplayName));
        Map<UUID, List<CommitmentSnapshot>> openBySubject = commitments.findByUserIdAndHostId(scope.userId(), scope.hostId())
                .stream()
                .filter(c -> c.getStatus() == RadarCommitmentStatus.OPEN && !c.isDisowned())
                .sorted(Comparator.comparing(RadarCommitment::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.groupingBy(RadarCommitment::getSubjectId, java.util.LinkedHashMap::new,
                        Collectors.mapping(c -> new CommitmentSnapshot(c.getId(), c.getDirection(), parties(c, names),
                                c.getDescription(), c.getDueDate()), Collectors.toList())));
        return kept.stream().map(s -> {
            List<RadarSubjectAlias> own = aliasesBySubject.getOrDefault(s.getId(), List.of());
            List<FactSnapshot> summary = withSummary.contains(s.getId())
                    ? facts.findByUserIdAndHostIdAndSubjectIdOrderByPositionAsc(scope.userId(), scope.hostId(), s.getId())
                            .stream().map(f -> new FactSnapshot(f.getId(), f.getText())).toList()
                    : List.<FactSnapshot>of();
            return new SubjectSnapshot(s.getId(), s.getName(), s.getState(), s.getLastActivityAt(),
                    own.stream().filter(a -> !a.isRejected()).map(RadarSubjectAlias::getAlias).toList(),
                    own.stream().filter(RadarSubjectAlias::isRejected).map(RadarSubjectAlias::getAlias).toList(),
                    summary, s.getState() == RadarSubjectState.CLOSED ? List.of()
                            : openBySubject.getOrDefault(s.getId(), List.of()));
        }).toList();
    }

    /** « Marc Durand → MOI », « MOI → Léa Martin », « MOI présente Marc Durand à Léa Martin ». */
    static String parties(RadarCommitment c, Map<UUID, String> names) {
        String from = name(c.getFromPersonId(), names, "MOI");
        String to = name(c.getToPersonId(), names, c.getDirection() == RadarCommitmentDirection.ME_TO_OTHER ? "?" : "MOI");
        if (c.getDirection() == RadarCommitmentDirection.INTRODUCTION) {
            return "MOI présente " + to + " à " + name(c.getOtherPersonId(), names, "?");
        }
        return from + " → " + to;
    }

    private static String name(UUID id, Map<UUID, String> names, String fallback) {
        return id == null ? fallback : names.getOrDefault(id, "?");
    }
}
