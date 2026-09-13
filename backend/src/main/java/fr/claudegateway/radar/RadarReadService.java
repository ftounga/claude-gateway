package fr.claudegateway.radar;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.radar.analysis.RadarAnalysisReport;
import fr.claudegateway.radar.analysis.RadarSyncAnalysisView;
import fr.claudegateway.radar.dto.RadarViews.AliasView;
import fr.claudegateway.radar.dto.RadarViews.CommitmentView;
import fr.claudegateway.radar.dto.RadarViews.EvidenceView;
import fr.claudegateway.radar.dto.RadarViews.PersonRef;
import fr.claudegateway.radar.dto.RadarViews.PersonSubjectView;
import fr.claudegateway.radar.dto.RadarViews.PersonView;
import fr.claudegateway.radar.dto.RadarViews.RoleView;
import fr.claudegateway.radar.dto.RadarViews.SentenceView;
import fr.claudegateway.radar.dto.RadarViews.SubjectDetail;
import fr.claudegateway.radar.dto.RadarViews.SubjectSummary;
import fr.claudegateway.radar.dto.RadarViews.SyncView;

/**
 * Lecture du Radar (F-99 / SF-99-01) : ce que les écrans affichent. Aucune écriture.
 *
 * <p>Chaque lecture part d'un {@link RadarScope} et ne consulte que des méthodes de dépôt qui portent
 * {@code user_id} et {@code host_id}.</p>
 */
@Service
@Transactional(readOnly = true)
public class RadarReadService {

    /** Nombre de synchros rendues par {@link #syncs(RadarScope)}. */
    static final int SYNC_PAGE = 20;

    private final RadarRegistry registry;
    private final RadarSubjectRepository subjects;
    private final RadarSubjectAliasRepository aliases;
    private final RadarSubjectFactRepository facts;
    private final RadarPersonRepository people;
    private final RadarSubjectRoleRepository roles;
    private final RadarCommitmentRepository commitments;
    private final RadarEvidenceRepository evidence;
    private final RadarEvidenceLinkRepository links;
    private final RadarSyncRepository syncs;
    private final ObjectMapper objectMapper;
    private final RadarAnalysisReport analysisReport;

    public RadarReadService(RadarRegistry registry, RadarSubjectRepository subjects,
            RadarSubjectAliasRepository aliases, RadarSubjectFactRepository facts,
            RadarPersonRepository people, RadarSubjectRoleRepository roles,
            RadarCommitmentRepository commitments, RadarEvidenceRepository evidence,
            RadarEvidenceLinkRepository links, RadarSyncRepository syncs, ObjectMapper objectMapper,
            RadarAnalysisReport analysisReport) {
        this.registry = registry;
        this.subjects = subjects;
        this.aliases = aliases;
        this.facts = facts;
        this.people = people;
        this.roles = roles;
        this.commitments = commitments;
        this.evidence = evidence;
        this.links = links;
        this.syncs = syncs;
        this.objectMapper = objectMapper;
        this.analysisReport = analysisReport;
    }

    /**
     * Les sujets du poste, plus récemment actifs d'abord.
     *
     * @param state         filtre facultatif
     * @param includeClosed les sujets clos sortent des listes par défaut (cadrage §6)
     */
    public List<SubjectSummary> subjects(RadarScope scope, RadarSubjectState state, boolean includeClosed) {
        return subjects(scope, state, includeClosed, null);
    }

    /**
     * Les sujets du poste, avec une recherche facultative sur le nom et les alias (SF-99-04). Une
     * recherche porte aussi sur les sujets clos : ils restent <b>consultables et cherchables</b>. Un
     * sujet clos qui s'est réveillé reste dans la liste par défaut.
     */
    public List<SubjectSummary> subjects(RadarScope scope, RadarSubjectState state, boolean includeClosed,
            String query) {
        String q = query == null ? "" : RadarText.key(query);
        Map<UUID, List<String>> aliasKeys = q.isEmpty() ? Map.of()
                : aliases.findByUserIdAndHostIdAndSubjectIdIn(scope.userId(), scope.hostId(),
                                subjects.findByUserIdAndHostId(scope.userId(), scope.hostId()).stream()
                                        .map(RadarSubject::getId).toList()).stream()
                        .filter(a -> !a.isRejected())
                        .collect(Collectors.groupingBy(RadarSubjectAlias::getSubjectId,
                                Collectors.mapping(RadarSubjectAlias::getNormalized, Collectors.toList())));
        Map<UUID, Long> open = commitments.findByUserIdAndHostId(scope.userId(), scope.hostId()).stream()
                .filter(c -> c.getStatus().isPending() && !c.isDisowned())
                .collect(Collectors.groupingBy(RadarCommitment::getSubjectId, Collectors.counting()));
        return subjects.findByUserIdAndHostId(scope.userId(), scope.hostId()).stream()
                .filter(s -> s.getMergedIntoId() == null)
                .filter(s -> state == null || s.getState() == state)
                .filter(s -> q.isEmpty() || RadarText.key(s.getName()).contains(q)
                        || aliasKeys.getOrDefault(s.getId(), List.of()).stream().anyMatch(k -> k.contains(q)))
                .filter(s -> includeClosed || !q.isEmpty() || state == RadarSubjectState.CLOSED
                        || s.getState() != RadarSubjectState.CLOSED || s.getWokeAt() != null)
                .sorted(byActivity())
                .map(s -> new SubjectSummary(s.getId(), s.getName(), s.getState(), s.getNextStep(),
                        s.getDueDate(), s.getLastActivityAt(), open.getOrDefault(s.getId(), 0L).intValue(),
                        s.getWokeAt() != null))
                .toList();
    }

    /** La page d'un sujet : valeurs, preuves de chaque valeur, résumé, personnes, engagements, chronologie. */
    public SubjectDetail subject(RadarScope scope, UUID subjectId) {
        RadarSubject subject = registry.requireSubject(scope, subjectId);
        List<RadarEvidenceLink> subjectLinks =
                links.findByUserIdAndHostIdAndSubjectId(scope.userId(), scope.hostId(), subject.getId());

        List<AliasView> aliasViews = aliases.findByUserIdAndHostIdAndSubjectIdOrderByCreatedAtAsc(
                        scope.userId(), scope.hostId(), subject.getId()).stream()
                .map(a -> new AliasView(a.getId(), a.getAlias(), a.getOrigin(), a.isRejected()))
                .toList();

        List<SentenceView> summary = facts.findByUserIdAndHostIdAndSubjectIdOrderByPositionAsc(
                        scope.userId(), scope.hostId(), subject.getId()).stream()
                .map(f -> new SentenceView(f.getId(), f.getPosition(), f.getText(),
                        evidenceOf(subjectLinks, RadarLinkKind.SUMMARY, f.getId())))
                .toList();

        Map<UUID, RadarPerson> directory = directory(scope);
        List<RoleView> roleViews = roles.findByUserIdAndHostIdAndSubjectId(
                        scope.userId(), scope.hostId(), subject.getId()).stream()
                .filter(r -> directory.containsKey(r.getPersonId()))
                .map(r -> {
                    RadarPerson p = directory.get(r.getPersonId());
                    return new RoleView(r.getId(), p.getId(), p.getDisplayName(), p.getJobTitle(),
                            r.getRole(), evidenceOf(subjectLinks, RadarLinkKind.ROLE, r.getId()));
                })
                .sorted(Comparator.comparing(RoleView::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        List<CommitmentView> commitmentViews = commitments.findByUserIdAndHostIdAndSubjectId(
                        scope.userId(), scope.hostId(), subject.getId()).stream()
                .sorted(byDueDate())
                .map(c -> commitmentView(c, subject, directory, subjectLinks))
                .toList();

        List<UUID> chronologyIds = evidenceOf(subjectLinks, RadarLinkKind.CHRONOLOGY, null);
        List<EvidenceView> chronology = chronologyIds.isEmpty() ? List.of()
                : evidence.findByUserIdAndHostIdAndIdIn(scope.userId(), scope.hostId(), chronologyIds).stream()
                        .sorted(Comparator.comparing(RadarEvidence::getOccurredAt).reversed())
                        .map(RadarReadService::evidenceView)
                        .toList();

        return new SubjectDetail(subject.getId(), subject.getName(), subject.getState(),
                subject.getNextStep(), subject.getDueDate(), subject.getLastActivityAt(),
                subject.getCreatedAt(), subject.getMergedIntoId(), subject.getPreviousState(),
                subject.getCloseProposedAt(), evidenceOf(subjectLinks, RadarLinkKind.CLOSE_SIGNAL, null),
                subject.getClosedAt(), subject.getDormantSince(), subject.getWokeAt(),
                List.copyOf(registry.wakeEvidenceIds(scope, subject, subjectLinks.stream()
                        .filter(l -> l.getTargetKind() == RadarLinkKind.CHRONOLOGY).toList())),
                subject.isNameSovereign(), subject.isStateSovereign(),
                subject.isNextStepSovereign(), subject.isDueDateSovereign(), aliasViews,
                evidenceOf(subjectLinks, RadarLinkKind.STATE, null),
                evidenceOf(subjectLinks, RadarLinkKind.NEXT_STEP, null),
                evidenceOf(subjectLinks, RadarLinkKind.DUE_DATE, null),
                summary, roleViews, commitmentViews, chronology);
    }

    /**
     * Les engagements du poste, échéance la plus proche d'abord.
     *
     * @param includeDisowned les engagements désavoués (« pas moi ») sortent des listes par défaut
     */
    public List<CommitmentView> commitments(RadarScope scope, RadarCommitmentDirection direction,
            RadarCommitmentStatus status, boolean includeDisowned) {
        return commitments(scope, direction, status, includeDisowned, false);
    }

    /**
     * Les engagements du poste, échéance la plus proche d'abord.
     *
     * @param followUpDueOnly seulement les relances échues (F-101 / SF-101-04)
     */
    public List<CommitmentView> commitments(RadarScope scope, RadarCommitmentDirection direction,
            RadarCommitmentStatus status, boolean includeDisowned, boolean followUpDueOnly) {
        Map<UUID, RadarSubject> subjectsById = subjects.findByUserIdAndHostId(scope.userId(), scope.hostId())
                .stream().collect(Collectors.toMap(RadarSubject::getId, Function.identity()));
        Map<UUID, RadarPerson> directory = directory(scope);
        List<RadarEvidenceLink> allLinks = links.findByUserIdAndHostId(scope.userId(), scope.hostId());
        return commitments.findByUserIdAndHostId(scope.userId(), scope.hostId()).stream()
                .filter(c -> direction == null || c.getDirection() == direction)
                .filter(c -> status == null || c.getStatus() == status)
                .filter(c -> includeDisowned || !c.isDisowned())
                .filter(c -> !followUpDueOnly || followUpDue(c))
                .filter(c -> subjectsById.containsKey(c.getSubjectId()))
                .sorted(byDueDate())
                .map(c -> commitmentView(c, subjectsById.get(c.getSubjectId()), directory, allLinks))
                .toList();
    }

    /** L'annuaire : les personnes, leurs sujets et leurs rôles. */
    public List<PersonView> people(RadarScope scope) {
        Map<UUID, RadarSubject> subjectsById = subjects.findByUserIdAndHostId(scope.userId(), scope.hostId())
                .stream().collect(Collectors.toMap(RadarSubject::getId, Function.identity()));
        Map<UUID, List<RadarSubjectRole>> rolesByPerson = roles.findByUserIdAndHostId(scope.userId(), scope.hostId())
                .stream().collect(Collectors.groupingBy(RadarSubjectRole::getPersonId));
        return people.findByUserIdAndHostIdOrderByDisplayNameAsc(scope.userId(), scope.hostId()).stream()
                .map(p -> new PersonView(p.getId(), p.getSourceKey(), p.getDisplayName(), p.getJobTitle(),
                        p.getLastInteractionAt(),
                        rolesByPerson.getOrDefault(p.getId(), List.of()).stream()
                                .filter(r -> subjectsById.containsKey(r.getSubjectId())
                                        && subjectsById.get(r.getSubjectId()).getMergedIntoId() == null)
                                .map(r -> {
                                    RadarSubject s = subjectsById.get(r.getSubjectId());
                                    return new PersonSubjectView(s.getId(), s.getName(), s.getState(), r.getRole());
                                })
                                .toList()))
                .toList();
    }

    /** Une preuve du périmètre. */
    public EvidenceView evidence(RadarScope scope, UUID evidenceId) {
        return evidence.findByIdAndUserIdAndHostId(evidenceId, scope.userId(), scope.hostId())
                .map(RadarReadService::evidenceView)
                .orElseThrow(() -> new RadarNotFoundException("Preuve introuvable."));
    }

    /** Les dernières synchros, plus récentes d'abord. */
    public List<SyncView> syncs(RadarScope scope) {
        List<RadarSync> page = syncs.findByUserIdAndHostIdOrderByStartedAtDesc(scope.userId(), scope.hostId(),
                PageRequest.of(0, SYNC_PAGE));
        Map<UUID, RadarSyncAnalysisView> analysis = analysisReport.bySync(scope, page);
        return page.stream()
                .map(s -> new SyncView(s.getId(), s.getStatus(), s.getStartedAt(), s.getFinishedAt(),
                        parse(s.getCoverage()), s.getConsumedTokens(), analysis.get(s.getId())))
                .toList();
    }

    // ---------------------------------------------------------------------------------- aides

    private Map<UUID, RadarPerson> directory(RadarScope scope) {
        return people.findByUserIdAndHostIdOrderByDisplayNameAsc(scope.userId(), scope.hostId()).stream()
                .collect(Collectors.toMap(RadarPerson::getId, Function.identity()));
    }

    private static CommitmentView commitmentView(RadarCommitment c, RadarSubject subject,
            Map<UUID, RadarPerson> directory, List<RadarEvidenceLink> scopeLinks) {
        return new CommitmentView(c.getId(), c.getSubjectId(), subject.getName(), c.getDirection(),
                c.getDescription(), ref(directory, c.getFromPersonId()), ref(directory, c.getToPersonId()),
                ref(directory, c.getOtherPersonId()), c.getDueDate(), c.isDueDeduced(), c.getStatus(),
                c.getCertainty(), c.isSovereign(), c.isDisowned(), evidenceOf(scopeLinks, RadarLinkKind.COMMITMENT, c.getId()),
                c.getLastEvidenceAt(), c.getFollowUpDueOn(), followUpDue(c),
                c.getCreatedAt(), c.getUpdatedAt());
    }

    /** Relance échue à la date du jour (UTC) : la date de relance est atteinte (F-101 / SF-101-04). */
    static boolean followUpDue(RadarCommitment c) {
        return c.getFollowUpDueOn() != null
                && !c.getFollowUpDueOn().isAfter(LocalDate.now(java.time.ZoneOffset.UTC));
    }

    private static PersonRef ref(Map<UUID, RadarPerson> directory, UUID personId) {
        if (personId == null) {
            return null;
        }
        RadarPerson person = directory.get(personId);
        return person == null ? null : new PersonRef(person.getId(), person.getDisplayName());
    }

    static List<UUID> evidenceOf(List<RadarEvidenceLink> scopeLinks, RadarLinkKind kind, UUID targetId) {
        return scopeLinks.stream()
                .filter(l -> l.getTargetKind() == kind && Objects.equals(l.getTargetId(), targetId))
                .map(RadarEvidenceLink::getEvidenceId)
                .distinct()
                .toList();
    }

    static EvidenceView evidenceView(RadarEvidence e) {
        return new EvidenceView(e.getId(), e.getSource(), e.getSourceRef(), e.getOccurredAt(), e.getQuote(),
                e.getDeepLink(), e.getAuthorPersonId());
    }

    private static Comparator<RadarSubject> byActivity() {
        return Comparator.comparing(RadarSubject::getLastActivityAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(RadarSubject::getName, String.CASE_INSENSITIVE_ORDER);
    }

    private static Comparator<RadarCommitment> byDueDate() {
        return Comparator.comparing(RadarCommitment::getDueDate, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
                .thenComparing(RadarCommitment::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
