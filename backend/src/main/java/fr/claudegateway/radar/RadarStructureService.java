package fr.claudegateway.radar;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.radar.dto.RadarViews.AliasView;
import fr.claudegateway.radar.dto.RadarViews.CorrectionView;

/**
 * <b>Fusionner, séparer, les alias</b> (F-99 / SF-99-03).
 *
 * <p>Ces gestes déplacent des <b>liens</b>, jamais des preuves : une preuve reste unique par
 * identifiant de source, et c'est ce qu'elle justifie qui change de sujet. Chaque geste est
 * journalisé avec la liste exacte de ce qui a bougé, ce qui le rend annulable sans rien deviner.</p>
 *
 * <p>Et chaque geste <b>apprend</b> : le nom d'un sujet absorbé devient un alias de sa cible ; une
 * séparation laisse une <b>consigne</b> — un alias refusé — des deux côtés. C'est ce que l'analyse
 * (F-101) lira pour ne pas refaire l'erreur.</p>
 */
@Service
@Transactional
public class RadarStructureService {

    static final String SOURCE = "source";
    static final String INTO = "into";
    static final String CREATED = "created";
    static final String LINKS = "links";
    static final String FACTS = "facts";
    static final String COMMITMENTS = "commitments";
    static final String ROLES = "roles";
    static final String ALIASES = "aliases";
    static final String CREATED_ALIASES = "createdAliases";

    /** Liens qui justifient une valeur de la source : ils ne s'imposent pas à la cible. */
    private static final Set<RadarLinkKind> VALUE_KINDS =
            Set.of(RadarLinkKind.STATE, RadarLinkKind.NEXT_STEP, RadarLinkKind.DUE_DATE);

    private final RadarRegistry registry;
    private final RadarSubjectRepository subjects;
    private final RadarSubjectAliasRepository aliases;
    private final RadarSubjectFactRepository facts;
    private final RadarSubjectRoleRepository roles;
    private final RadarCommitmentRepository commitments;
    private final RadarEvidenceLinkRepository links;
    private final RadarCorrectionRepository corrections;
    private final RadarCorrectionJournal journal;

    public RadarStructureService(RadarRegistry registry, RadarSubjectRepository subjects,
            RadarSubjectAliasRepository aliases, RadarSubjectFactRepository facts,
            RadarSubjectRoleRepository roles, RadarCommitmentRepository commitments,
            RadarEvidenceLinkRepository links, RadarCorrectionRepository corrections,
            RadarCorrectionJournal journal) {
        this.registry = registry;
        this.subjects = subjects;
        this.aliases = aliases;
        this.facts = facts;
        this.roles = roles;
        this.commitments = commitments;
        this.links = links;
        this.corrections = corrections;
        this.journal = journal;
    }

    // ---------------------------------------------------------------------------------- fusion

    /** Fusionne le sujet {@code sourceId} dans {@code intoId}. */
    public CorrectionView merge(RadarScope scope, UUID sourceId, UUID intoId) {
        if (intoId == null) {
            throw new InvalidRadarInputException("La cible de la fusion est requise.");
        }
        if (intoId.equals(sourceId)) {
            throw new InvalidRadarInputException("Un sujet ne se fusionne pas avec lui-même.");
        }
        RadarSubject source = requireUnmerged(scope, sourceId);
        RadarSubject target = requireUnmerged(scope, intoId);

        // Rôles : une personne déjà présente sur la cible y garde son rôle.
        Set<UUID> targetPeople = roles.findByUserIdAndHostIdAndSubjectId(scope.userId(), scope.hostId(), target.getId())
                .stream().map(RadarSubjectRole::getPersonId).collect(Collectors.toSet());
        List<String> movedRoles = new ArrayList<>();
        for (RadarSubjectRole role : roles.findByUserIdAndHostIdAndSubjectId(scope.userId(), scope.hostId(), source.getId())) {
            if (!targetPeople.contains(role.getPersonId())) {
                role.setSubjectId(target.getId());
                roles.save(role);
                movedRoles.add(role.getId().toString());
            }
        }

        List<String> movedLinks = new ArrayList<>();
        for (RadarEvidenceLink link : links.findByUserIdAndHostIdAndSubjectId(scope.userId(), scope.hostId(), source.getId())) {
            boolean stays = VALUE_KINDS.contains(link.getTargetKind())
                    || (link.getTargetKind() == RadarLinkKind.ROLE
                            && !movedRoles.contains(String.valueOf(link.getTargetId())));
            if (!stays) {
                link.setSubjectId(target.getId());
                links.save(link);
                movedLinks.add(link.getId().toString());
            }
        }

        int offset = facts.findByUserIdAndHostIdAndSubjectIdOrderByPositionAsc(scope.userId(), scope.hostId(), target.getId())
                .stream().mapToInt(RadarSubjectFact::getPosition).max().orElse(-1) + 1;
        List<Map<String, Object>> movedFacts = new ArrayList<>();
        for (RadarSubjectFact fact : facts.findByUserIdAndHostIdAndSubjectIdOrderByPositionAsc(
                scope.userId(), scope.hostId(), source.getId())) {
            movedFacts.add(Map.of("id", fact.getId().toString(), "position", fact.getPosition()));
            fact.setSubjectId(target.getId());
            fact.setPosition(offset++);
            facts.save(fact);
        }

        List<String> movedCommitments = new ArrayList<>();
        for (RadarCommitment commitment : commitments.findByUserIdAndHostIdAndSubjectId(
                scope.userId(), scope.hostId(), source.getId())) {
            commitment.setSubjectId(target.getId());
            commitments.save(commitment);
            movedCommitments.add(commitment.getId().toString());
        }

        Set<String> targetKeys = aliasKeys(scope, target);
        List<String> movedAliases = new ArrayList<>();
        for (RadarSubjectAlias alias : aliases.findByUserIdAndHostIdAndSubjectIdOrderByCreatedAtAsc(
                scope.userId(), scope.hostId(), source.getId())) {
            if (targetKeys.add(alias.getNormalized())) {
                alias.setSubjectId(target.getId());
                aliases.save(alias);
                movedAliases.add(alias.getId().toString());
            }
        }
        List<String> created = new ArrayList<>();
        String sourceKey = RadarText.key(source.getName());
        if (targetKeys.add(sourceKey)) {
            created.add(aliases.save(RadarSubjectAlias.builder()
                    .userId(scope.userId()).hostId(scope.hostId()).subjectId(target.getId())
                    .alias(source.getName()).normalized(sourceKey).origin(RadarAliasOrigin.MERGE)
                    .build()).getId().toString());
        }

        source.setMergedIntoId(target.getId());
        subjects.save(source);
        registry.recomputeActivity(scope, source);
        registry.recomputeActivity(scope, target);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("mergedIntoId", null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put(SOURCE, source.getId().toString());
        after.put(INTO, target.getId().toString());
        after.put(LINKS, movedLinks);
        after.put(FACTS, movedFacts);
        after.put(COMMITMENTS, movedCommitments);
        after.put(ROLES, movedRoles);
        after.put(ALIASES, movedAliases);
        after.put(CREATED_ALIASES, created);
        return journal.view(journal.record(scope, target.getId(), RadarCorrectionAction.Target.SUBJECT,
                source.getId(), RadarCorrectionAction.MERGE, before, after));
    }

    // ------------------------------------------------------------------------------ séparation

    /** Sépare d'un sujet les preuves et engagements désignés, dans un nouveau sujet. */
    public CorrectionView split(RadarScope scope, UUID sourceId, String name, Collection<UUID> evidenceIds,
            Collection<UUID> commitmentIds) {
        RadarSubject source = requireUnmerged(scope, sourceId);
        String cleanName = RadarText.required(name, RadarSubject.MAX_NAME_LENGTH, "name");
        Set<UUID> moving = new LinkedHashSet<>();
        if (evidenceIds != null) {
            evidenceIds.stream().filter(Objects::nonNull).forEach(moving::add);
        }
        if (moving.isEmpty()) {
            throw new InvalidRadarInputException("Désignez au moins une preuve à séparer.");
        }
        List<RadarEvidenceLink> sourceLinks =
                links.findByUserIdAndHostIdAndSubjectId(scope.userId(), scope.hostId(), source.getId());
        Set<UUID> chronology = sourceLinks.stream()
                .filter(l -> l.getTargetKind() == RadarLinkKind.CHRONOLOGY)
                .map(RadarEvidenceLink::getEvidenceId).collect(Collectors.toSet());
        if (!chronology.containsAll(moving)) {
            throw new InvalidRadarInputException("Une preuve désignée n'est pas dans la chronologie de ce sujet.");
        }
        if (moving.containsAll(chronology)) {
            throw new InvalidRadarInputException(
                    "Séparer toutes les preuves d'un sujet revient à le renommer : renommez-le.");
        }
        List<RadarCommitment> movingCommitments = new ArrayList<>();
        Set<UUID> commitmentSet = new LinkedHashSet<>();
        if (commitmentIds != null) {
            commitmentIds.stream().filter(Objects::nonNull).forEach(commitmentSet::add);
        }
        for (UUID id : commitmentSet) {
            RadarCommitment commitment = commitments.findByIdAndUserIdAndHostId(id, scope.userId(), scope.hostId())
                    .filter(c -> c.getSubjectId().equals(source.getId()))
                    .orElseThrow(() -> new InvalidRadarInputException("Un engagement désigné n'est pas de ce sujet."));
            movingCommitments.add(commitment);
        }

        RadarSubject created = subjects.save(RadarSubject.builder()
                .userId(scope.userId()).hostId(scope.hostId()).name(cleanName)
                .state(RadarSubjectState.NEW).nameSovereign(true).build());

        List<String> movedLinks = new ArrayList<>();
        Set<UUID> movingCommitmentIds = movingCommitments.stream().map(RadarCommitment::getId).collect(Collectors.toSet());
        Map<UUID, List<RadarEvidenceLink>> summaryLinks = sourceLinks.stream()
                .filter(l -> l.getTargetKind() == RadarLinkKind.SUMMARY)
                .collect(Collectors.groupingBy(RadarEvidenceLink::getTargetId));
        Set<UUID> movingFacts = summaryLinks.entrySet().stream()
                .filter(e -> e.getValue().stream().allMatch(l -> moving.contains(l.getEvidenceId())))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        for (RadarEvidenceLink link : sourceLinks) {
            boolean moves = switch (link.getTargetKind()) {
                case CHRONOLOGY -> moving.contains(link.getEvidenceId());
                case COMMITMENT -> movingCommitmentIds.contains(link.getTargetId());
                case SUMMARY -> movingFacts.contains(link.getTargetId());
                default -> false;
            };
            if (moves) {
                link.setSubjectId(created.getId());
                links.save(link);
                movedLinks.add(link.getId().toString());
            }
        }
        List<String> movedCommitments = new ArrayList<>();
        for (RadarCommitment commitment : movingCommitments) {
            commitment.setSubjectId(created.getId());
            commitments.save(commitment);
            movedCommitments.add(commitment.getId().toString());
        }
        List<Map<String, Object>> movedFacts = new ArrayList<>();
        int position = 0;
        for (RadarSubjectFact fact : facts.findByUserIdAndHostIdAndSubjectIdOrderByPositionAsc(
                scope.userId(), scope.hostId(), source.getId())) {
            if (movingFacts.contains(fact.getId())) {
                movedFacts.add(Map.of("id", fact.getId().toString(), "position", fact.getPosition()));
                fact.setSubjectId(created.getId());
                fact.setPosition(position++);
                facts.save(fact);
            }
        }

        // Les consignes : « N n'est pas S » et « S n'est pas N ».
        List<String> hints = new ArrayList<>();
        rejectedHint(scope, source, created.getName()).ifPresent(hints::add);
        rejectedHint(scope, created, source.getName()).ifPresent(hints::add);

        registry.recomputeActivity(scope, source);
        registry.recomputeActivity(scope, created);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("split", null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put(SOURCE, source.getId().toString());
        after.put(CREATED, created.getId().toString());
        after.put(LINKS, movedLinks);
        after.put(FACTS, movedFacts);
        after.put(COMMITMENTS, movedCommitments);
        after.put(CREATED_ALIASES, hints);
        return journal.view(journal.record(scope, source.getId(), RadarCorrectionAction.Target.SUBJECT,
                source.getId(), RadarCorrectionAction.SPLIT, before, after));
    }

    // ---------------------------------------------------------------------------------- alias

    /** Un alias dit par l'utilisateur. */
    public AliasView addUserAlias(RadarScope scope, UUID subjectId, String alias) {
        RadarSubject subject = requireUnmerged(scope, subjectId);
        String clean = RadarText.required(alias, RadarSubjectAlias.MAX_ALIAS_LENGTH, "alias");
        String key = RadarText.key(clean);
        if (!aliasKeys(scope, subject).add(key)) {
            throw new InvalidRadarInputException("Ce nom est déjà connu de ce sujet.");
        }
        RadarSubjectAlias saved = aliases.save(RadarSubjectAlias.builder()
                .userId(scope.userId()).hostId(scope.hostId()).subjectId(subject.getId())
                .alias(clean).normalized(key).origin(RadarAliasOrigin.USER).build());
        return new AliasView(saved.getId(), saved.getAlias(), saved.getOrigin(), saved.isRejected());
    }

    /** Retire un alias — ou une consigne — d'un sujet. */
    public void removeAlias(RadarScope scope, UUID subjectId, UUID aliasId) {
        RadarSubject subject = registry.requireSubject(scope, subjectId);
        RadarSubjectAlias alias = aliases.findById(aliasId)
                .filter(a -> a.getUserId().equals(scope.userId()) && a.getHostId().equals(scope.hostId())
                        && a.getSubjectId().equals(subject.getId()))
                .orElseThrow(() -> new RadarNotFoundException("Alias introuvable."));
        aliases.delete(alias);
    }

    // ------------------------------------------------------------------------------ annulation

    /** Annule une fusion ou une séparation (appelé par {@link RadarCorrectionService#undo}). */
    void undo(RadarScope scope, RadarCorrection correction) {
        Map<String, Object> after = journal.parse(correction.getAfterValues());
        refuseIfCoveredByLaterStructure(scope, correction, involved(after));
        if (correction.getAction() == RadarCorrectionAction.MERGE) {
            undoMerge(scope, after);
        } else {
            undoSplit(scope, after);
        }
    }

    private void undoMerge(RadarScope scope, Map<String, Object> after) {
        RadarSubject source = registry.requireSubject(scope, uuid(after.get(SOURCE)));
        RadarSubject target = registry.requireSubject(scope, uuid(after.get(INTO)));
        if (target.getMergedIntoId() != null) {
            throw new RadarCorrectionConflictException("La cible a été fusionnée depuis : annulez d'abord cette fusion.");
        }
        moveBack(scope, after, target.getId(), source.getId());
        for (String id : strings(after.get(ROLES))) {
            roles.findByIdAndUserIdAndHostId(UUID.fromString(id), scope.userId(), scope.hostId())
                    .filter(r -> r.getSubjectId().equals(target.getId()))
                    .ifPresent(r -> {
                        r.setSubjectId(source.getId());
                        roles.save(r);
                    });
        }
        for (String id : strings(after.get(ALIASES))) {
            aliases.findById(UUID.fromString(id))
                    .filter(a -> inScope(scope, a) && a.getSubjectId().equals(target.getId()))
                    .ifPresent(a -> {
                        a.setSubjectId(source.getId());
                        aliases.save(a);
                    });
        }
        deleteAliases(scope, after);
        source.setMergedIntoId(null);
        subjects.save(source);
        registry.recomputeActivity(scope, source);
        registry.recomputeActivity(scope, target);
    }

    private void undoSplit(RadarScope scope, Map<String, Object> after) {
        RadarSubject source = registry.requireSubject(scope, uuid(after.get(SOURCE)));
        RadarSubject created = registry.requireSubject(scope, uuid(after.get(CREATED)));
        Set<String> recordedLinks = new HashSet<>(strings(after.get(LINKS)));
        Set<String> recordedCommitments = new HashSet<>(strings(after.get(COMMITMENTS)));
        Set<String> recordedFacts = factIds(after).stream().collect(Collectors.toSet());
        boolean lived = created.getMergedIntoId() != null
                || links.findByUserIdAndHostIdAndSubjectId(scope.userId(), scope.hostId(), created.getId()).stream()
                        .anyMatch(l -> !recordedLinks.contains(l.getId().toString()))
                || commitments.findByUserIdAndHostIdAndSubjectId(scope.userId(), scope.hostId(), created.getId()).stream()
                        .anyMatch(c -> !recordedCommitments.contains(c.getId().toString()))
                || facts.findByUserIdAndHostIdAndSubjectIdOrderByPositionAsc(scope.userId(), scope.hostId(), created.getId())
                        .stream().anyMatch(f -> !recordedFacts.contains(f.getId().toString()))
                || !roles.findByUserIdAndHostIdAndSubjectId(scope.userId(), scope.hostId(), created.getId()).isEmpty()
                || !corrections.findByUserIdAndHostIdAndTargetIdAndUndoneAtIsNull(
                        scope.userId(), scope.hostId(), created.getId()).isEmpty();
        if (lived) {
            throw new RadarCorrectionConflictException(
                    "Le sujet séparé a reçu d'autres éléments depuis : fusionnez-le plutôt.");
        }
        moveBack(scope, after, created.getId(), source.getId());
        deleteAliases(scope, after);
        aliases.deleteAll(aliases.findByUserIdAndHostIdAndSubjectIdOrderByCreatedAtAsc(
                scope.userId(), scope.hostId(), created.getId()));
        subjects.delete(created);
        registry.recomputeActivity(scope, source);
    }

    /** Remet liens, phrases et engagements enregistrés sur {@code to}, s'ils sont encore sur {@code from}. */
    private void moveBack(RadarScope scope, Map<String, Object> after, UUID from, UUID to) {
        Set<String> linkIds = new HashSet<>(strings(after.get(LINKS)));
        for (RadarEvidenceLink link : links.findByUserIdAndHostIdAndSubjectId(scope.userId(), scope.hostId(), from)) {
            if (linkIds.contains(link.getId().toString())) {
                link.setSubjectId(to);
                links.save(link);
            }
        }
        for (Object raw : list(after.get(FACTS))) {
            if (raw instanceof Map<?, ?> fact) {
                facts.findById(UUID.fromString(String.valueOf(fact.get("id"))))
                        .filter(f -> f.getUserId().equals(scope.userId()) && f.getHostId().equals(scope.hostId())
                                && f.getSubjectId().equals(from))
                        .ifPresent(f -> {
                            f.setSubjectId(to);
                            f.setPosition(((Number) fact.get("position")).intValue());
                            facts.save(f);
                        });
            }
        }
        for (String id : strings(after.get(COMMITMENTS))) {
            commitments.findByIdAndUserIdAndHostId(UUID.fromString(id), scope.userId(), scope.hostId())
                    .filter(c -> c.getSubjectId().equals(from))
                    .ifPresent(c -> {
                        c.setSubjectId(to);
                        commitments.save(c);
                    });
        }
    }

    private void deleteAliases(RadarScope scope, Map<String, Object> after) {
        for (String id : strings(after.get(CREATED_ALIASES))) {
            aliases.findById(UUID.fromString(id)).filter(a -> inScope(scope, a)).ifPresent(aliases::delete);
        }
    }

    /** Une fusion ou séparation plus récente, encore active, qui implique l'un des mêmes sujets. */
    private void refuseIfCoveredByLaterStructure(RadarScope scope, RadarCorrection correction, Set<String> involved) {
        boolean covered = corrections.findByUserIdAndHostIdOrderByCreatedAtDesc(scope.userId(), scope.hostId()).stream()
                .filter(c -> c.getUndoneAt() == null && !c.getId().equals(correction.getId()))
                .filter(c -> c.getAction() == RadarCorrectionAction.MERGE || c.getAction() == RadarCorrectionAction.SPLIT)
                .filter(c -> !c.getCreatedAt().isBefore(correction.getCreatedAt()))
                .anyMatch(c -> involved(journal.parse(c.getAfterValues())).stream().anyMatch(involved::contains));
        if (covered) {
            throw new RadarCorrectionConflictException(
                    "Une fusion ou une séparation plus récente implique ces sujets : annulez-la d'abord.");
        }
    }

    // ---------------------------------------------------------------------------------- aides

    private RadarSubject requireUnmerged(RadarScope scope, UUID subjectId) {
        RadarSubject subject = registry.requireSubject(scope, subjectId);
        if (subject.getMergedIntoId() != null) {
            throw new RadarSubjectMergedException("Ce sujet a été fusionné dans un autre.");
        }
        return subject;
    }

    private Set<String> aliasKeys(RadarScope scope, RadarSubject subject) {
        Set<String> keys = aliases.findByUserIdAndHostIdAndSubjectIdOrderByCreatedAtAsc(
                        scope.userId(), scope.hostId(), subject.getId()).stream()
                .map(RadarSubjectAlias::getNormalized).collect(Collectors.toCollection(HashSet::new));
        keys.add(RadarText.key(subject.getName()));
        return keys;
    }

    private Optional<String> rejectedHint(RadarScope scope, RadarSubject on, String name) {
        String key = RadarText.key(name);
        if (aliasKeys(scope, on).contains(key)) {
            return Optional.empty();
        }
        String alias = name.length() > RadarSubjectAlias.MAX_ALIAS_LENGTH
                ? name.substring(0, RadarSubjectAlias.MAX_ALIAS_LENGTH) : name;
        return Optional.of(aliases.save(RadarSubjectAlias.builder()
                .userId(scope.userId()).hostId(scope.hostId()).subjectId(on.getId())
                .alias(alias).normalized(RadarText.key(alias)).origin(RadarAliasOrigin.SPLIT).rejected(true)
                .build()).getId().toString());
    }

    private static boolean inScope(RadarScope scope, RadarSubjectAlias alias) {
        return alias.getUserId().equals(scope.userId()) && alias.getHostId().equals(scope.hostId());
    }

    private static Set<String> involved(Map<String, Object> after) {
        Set<String> ids = new HashSet<>();
        for (String key : List.of(SOURCE, INTO, CREATED)) {
            if (after.get(key) != null) {
                ids.add(after.get(key).toString());
            }
        }
        return ids;
    }

    private static List<String> factIds(Map<String, Object> after) {
        return list(after.get(FACTS)).stream()
                .filter(Map.class::isInstance)
                .map(m -> String.valueOf(((Map<?, ?>) m).get("id")))
                .toList();
    }

    private static UUID uuid(Object value) {
        return UUID.fromString(String.valueOf(value));
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    private static List<String> strings(Object value) {
        return list(value).stream().map(String::valueOf).toList();
    }
}
