package fr.claudegateway.radar;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Le <b>registre</b> du Radar (F-99 / SF-99-01) : la seule porte d'écriture des faits.
 *
 * <p>Ceux qui l'appellent — l'analyse des échanges (F-101), les outils Radar (F-104) — n'écrivent
 * jamais dans les dépôts eux-mêmes. Le registre garantit deux règles du cadrage §4 que rien d'autre
 * ne pourrait garantir :</p>
 * <ul>
 *   <li><b>Pas de fait sans preuve.</b> Un état, une prochaine étape, une échéance, une phrase de
 *       résumé, un rôle, un engagement : chacun exige au moins une preuve <b>du même périmètre</b>,
 *       sinon {@link RadarEvidenceRequiredException} et rien n'est écrit. Toute preuve qui justifie
 *       quelque chose entre aussi dans la chronologie du sujet.</li>
 *   <li><b>Un poste, un Radar.</b> Chaque méthode prend un {@link RadarScope} ; une preuve, une
 *       personne ou un sujet d'un autre poste sont introuvables, même pour le même utilisateur.</li>
 * </ul>
 *
 * <p><b>Souveraineté</b> (SF-99-02). Une valeur corrigée par l'utilisateur n'est jamais réécrite
 * ici : la preuve entre dans la chronologie, la valeur reste. Les corrections passent par
 * {@code RadarCorrectionService}.</p>
 *
 * <p><b>Idempotence.</b> Une preuve est unique par identifiant de source, un engagement par clé
 * d'extraction : une synchro reprise ne duplique rien.</p>
 */
@Service
@Transactional
public class RadarRegistry {

    /** Nombre maximal de phrases d'un résumé. */
    public static final int MAX_SUMMARY_SENTENCES = 20;

    /** Longueur maximale d'une chaîne de fusions suivie pour rediriger une écriture. */
    static final int MAX_MERGE_HOPS = 16;

    private final RadarSubjectRepository subjects;
    private final RadarSubjectFactRepository facts;
    private final RadarSubjectAliasRepository aliases;
    private final RadarPersonRepository people;
    private final RadarSubjectRoleRepository roles;
    private final RadarCommitmentRepository commitments;
    private final RadarEvidenceRepository evidence;
    private final RadarEvidenceLinkRepository links;
    private final RadarSyncRepository syncs;

    public RadarRegistry(RadarSubjectRepository subjects, RadarSubjectFactRepository facts,
            RadarSubjectAliasRepository aliases, RadarPersonRepository people, RadarSubjectRoleRepository roles,
            RadarCommitmentRepository commitments, RadarEvidenceRepository evidence,
            RadarEvidenceLinkRepository links, RadarSyncRepository syncs) {
        this.subjects = subjects;
        this.facts = facts;
        this.aliases = aliases;
        this.people = people;
        this.roles = roles;
        this.commitments = commitments;
        this.evidence = evidence;
        this.links = links;
        this.syncs = syncs;
    }

    /** Ce que la source dit d'un extrait. */
    public record EvidenceInput(RadarEvidenceSource source, String sourceRef, OffsetDateTime occurredAt,
            String quote, String deepLink, UUID authorPersonId) {
    }

    /** Une phrase de résumé et ses renvois. */
    public record SummarySentence(String text, List<UUID> evidenceIds) {
    }

    /** Un engagement lu dans une source. */
    public record CommitmentInput(UUID subjectId, RadarCommitmentDirection direction,
            String description, UUID fromPersonId, UUID toPersonId, UUID otherPersonId,
            LocalDate dueDate, boolean dueDeduced, RadarCertainty certainty, String extractionKey,
            List<UUID> evidenceIds) {
    }

    // ------------------------------------------------------------------------------------ preuves

    /**
     * Enregistre une preuve, ou rend celle qui existe déjà sous le même identifiant de source.
     *
     * @throws InvalidRadarInputException si la source, l'identifiant, l'instant ou la citation manquent,
     *                                    ou si l'auteur n'est pas une personne du périmètre
     */
    public RadarEvidence recordEvidence(RadarScope scope, EvidenceInput input) {
        if (input == null || input.source() == null) {
            throw new InvalidRadarInputException("La source de la preuve est requise.");
        }
        String sourceRef = RadarText.required(input.sourceRef(), RadarEvidence.MAX_SOURCE_REF_LENGTH,
                "source_ref");
        if (input.occurredAt() == null) {
            throw new InvalidRadarInputException("L'instant de la preuve est requis.");
        }
        String quote = RadarText.quote(input.quote());
        var existing = evidence.findByUserIdAndHostIdAndSourceAndSourceRef(
                scope.userId(), scope.hostId(), input.source(), sourceRef);
        if (existing.isPresent()) {
            return existing.get();
        }
        RadarPerson author = null;
        if (input.authorPersonId() != null) {
            author = requirePerson(scope, input.authorPersonId());
        }
        RadarEvidence saved = evidence.save(RadarEvidence.builder()
                .userId(scope.userId()).hostId(scope.hostId())
                .source(input.source()).sourceRef(sourceRef)
                .occurredAt(input.occurredAt()).quote(quote)
                .deepLink(RadarText.deepLink(input.deepLink()))
                .authorPersonId(author == null ? null : author.getId())
                .build());
        if (author != null && (author.getLastInteractionAt() == null
                || author.getLastInteractionAt().isBefore(input.occurredAt()))) {
            author.setLastInteractionAt(input.occurredAt());
        }
        return saved;
    }

    // ------------------------------------------------------------------------------------- sujets

    /**
     * Crée un sujet <b>à partir de ses preuves</b>.
     *
     * @param state {@code null} = {@link RadarSubjectState#NEW} ; seuls les états d'un sujet ouvert
     *              sont admis ici (la clôture et le sommeil ont leurs propres règles)
     */
    public RadarSubject createSubject(RadarScope scope, String name, RadarSubjectState state,
            Collection<UUID> evidenceIds) {
        String cleanName = RadarText.required(name, RadarSubject.MAX_NAME_LENGTH, "name");
        RadarSubjectState initial = state == null ? RadarSubjectState.NEW : requireOpenWork(state);
        List<RadarEvidence> proofs = requireEvidence(scope, evidenceIds);
        RadarSubject subject = subjects.save(RadarSubject.builder()
                .userId(scope.userId()).hostId(scope.hostId())
                .name(cleanName).state(initial).build());
        addToChronology(scope, subject, proofs);
        return subject;
    }

    /** Range des preuves dans la chronologie d'un sujet, sans doublon de lien. */
    public RadarSubject attachEvidence(RadarScope scope, UUID subjectId, Collection<UUID> evidenceIds) {
        RadarSubject subject = requireLiveSubject(scope, subjectId);
        addToChronology(scope, subject, requireEvidence(scope, evidenceIds));
        return subject;
    }

    /** Change l'état d'un sujet ouvert, avec ses preuves. */
    public RadarSubject setState(RadarScope scope, UUID subjectId, RadarSubjectState state,
            Collection<UUID> evidenceIds) {
        RadarSubject subject = requireLiveSubject(scope, subjectId);
        RadarSubjectState next = requireOpenWork(Objects.requireNonNull(state, "state"));
        List<RadarEvidence> proofs = requireEvidence(scope, evidenceIds);
        if (subject.getState() == RadarSubjectState.CLOSED) {
            // Un sujet clos ne se rouvre jamais en silence : la preuve est de l'activité, qui peut
            // le réveiller (SF-99-04).
            addToChronology(scope, subject, proofs);
            return subject;
        }
        if (subject.isStateSovereign()) {
            // L'utilisateur a dit l'état : la preuve est de l'activité, pas une réécriture (SF-99-02).
            addToChronology(scope, subject, proofs);
            return subject;
        }
        if (subject.getState() == RadarSubjectState.CLOSE_PROPOSED) {
            // La proposition attend l'utilisateur ; un refus reviendra à l'état le plus récent.
            subject.setPreviousState(next);
            justify(scope, subject, RadarLinkKind.STATE, null, proofs);
            return subject;
        }
        justify(scope, subject, RadarLinkKind.STATE, null, proofs);
        subject.setState(next);
        subject.setPreviousState(null);
        subject.setDormantSince(null);
        subjects.save(subject);
        return subject;
    }

    /**
     * Un signal explicite de clôture lu dans une source (SF-99-04) : le sujet passe en « clos ? ».
     *
     * <p>Une <b>proposition</b>, jamais une clôture. Ignorée si toutes ses preuves sont antérieures au
     * dernier refus de l'utilisateur ; sur un sujet déjà clos, la preuve est seulement reliée au signal,
     * sans réveiller le sujet.</p>
     */
    public RadarSubject proposeClosure(RadarScope scope, UUID subjectId, Collection<UUID> evidenceIds) {
        RadarSubject subject = requireLiveSubject(scope, subjectId);
        List<RadarEvidence> proofs = requireEvidence(scope, evidenceIds);
        if (subject.getState() == RadarSubjectState.CLOSED) {
            addLinks(scope, subject.getId(), RadarLinkKind.CLOSE_SIGNAL, null, proofs);
            return subject;
        }
        OffsetDateTime rejected = subject.getCloseRejectedAt();
        if (rejected != null && proofs.stream().noneMatch(p -> p.getOccurredAt().isAfter(rejected))) {
            addToChronology(scope, subject, proofs);
            return subject;
        }
        if (subject.getState() != RadarSubjectState.CLOSE_PROPOSED) {
            RadarSubjectState before = subject.getState() == RadarSubjectState.DORMANT
                    ? subject.getPreviousState() : subject.getState();
            subject.setPreviousState(before);
            subject.setState(RadarSubjectState.CLOSE_PROPOSED);
            subject.setDormantSince(null);
            subject.setCloseProposedAt(OffsetDateTime.now());
            justify(scope, subject, RadarLinkKind.CLOSE_SIGNAL, null, proofs);
        } else {
            justify(scope, subject, RadarLinkKind.CLOSE_SIGNAL, null, proofs, false);
        }
        return subject;
    }

    /** Fixe (ou efface, {@code null}) la prochaine étape, avec ses preuves. */
    public RadarSubject setNextStep(RadarScope scope, UUID subjectId, String nextStep,
            Collection<UUID> evidenceIds) {
        RadarSubject subject = requireLiveSubject(scope, subjectId);
        String text = RadarText.optional(nextStep, RadarSubject.MAX_NEXT_STEP_LENGTH, "next_step");
        List<RadarEvidence> proofs = requireEvidence(scope, evidenceIds);
        if (subject.isNextStepSovereign()) {
            addToChronology(scope, subject, proofs);
            return subject;
        }
        subject.setNextStep(text);
        justify(scope, subject, RadarLinkKind.NEXT_STEP, null, proofs);
        return subject;
    }

    /** Fixe (ou efface, {@code null}) l'échéance connue, avec ses preuves. */
    public RadarSubject setDueDate(RadarScope scope, UUID subjectId, LocalDate dueDate,
            Collection<UUID> evidenceIds) {
        RadarSubject subject = requireLiveSubject(scope, subjectId);
        List<RadarEvidence> proofs = requireEvidence(scope, evidenceIds);
        if (subject.isDueDateSovereign()) {
            addToChronology(scope, subject, proofs);
            return subject;
        }
        subject.setDueDate(dueDate);
        justify(scope, subject, RadarLinkKind.DUE_DATE, null, proofs);
        return subject;
    }

    /**
     * Remplace le résumé, <b>phrase par phrase</b>. Toutes les phrases sont vérifiées avant la
     * première écriture : une seule phrase sans preuve, et l'ancien résumé reste en place.
     */
    public List<RadarSubjectFact> replaceSummary(RadarScope scope, UUID subjectId,
            List<SummarySentence> sentences) {
        RadarSubject subject = requireLiveSubject(scope, subjectId);
        List<SummarySentence> input = sentences == null ? List.of() : sentences;
        if (input.size() > MAX_SUMMARY_SENTENCES) {
            throw new InvalidRadarInputException(
                    "Un résumé compte au plus " + MAX_SUMMARY_SENTENCES + " phrases.");
        }
        List<String> texts = new ArrayList<>();
        List<List<RadarEvidence>> proofs = new ArrayList<>();
        for (SummarySentence sentence : input) {
            texts.add(RadarText.required(sentence == null ? null : sentence.text(),
                    RadarSubjectFact.MAX_TEXT_LENGTH, "summary"));
            proofs.add(requireEvidence(scope, sentence.evidenceIds()));
        }
        links.deleteAll(links.findByUserIdAndHostIdAndSubjectIdAndTargetKind(
                scope.userId(), scope.hostId(), subject.getId(), RadarLinkKind.SUMMARY));
        facts.deleteAll(facts.findByUserIdAndHostIdAndSubjectIdOrderByPositionAsc(
                scope.userId(), scope.hostId(), subject.getId()));
        List<RadarSubjectFact> written = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            RadarSubjectFact fact = facts.save(RadarSubjectFact.builder()
                    .userId(scope.userId()).hostId(scope.hostId()).subjectId(subject.getId())
                    .position(i).text(texts.get(i)).build());
            addLinks(scope, subject.getId(), RadarLinkKind.SUMMARY, fact.getId(), proofs.get(i));
            addToChronology(scope, subject, proofs.get(i));
            written.add(fact);
        }
        return written;
    }

    // ---------------------------------------------------------------------------------- personnes

    /** Une personne par identité de source : créée, ou mise à jour (nom, fonction). */
    public RadarPerson upsertPerson(RadarScope scope, String sourceKey, String displayName,
            String jobTitle) {
        String key = RadarText.key(RadarText.required(sourceKey, RadarPerson.MAX_SOURCE_KEY_LENGTH,
                "source_key"));
        String name = RadarText.required(displayName, RadarPerson.MAX_NAME_LENGTH, "display_name");
        String title = RadarText.optional(jobTitle, RadarPerson.MAX_NAME_LENGTH, "job_title");
        RadarPerson person = people.findByUserIdAndHostIdAndSourceKey(scope.userId(), scope.hostId(), key)
                .orElseGet(() -> RadarPerson.builder()
                        .userId(scope.userId()).hostId(scope.hostId()).sourceKey(key).build());
        person.setDisplayName(name);
        if (title != null) {
            person.setJobTitle(title);
        }
        return people.save(person);
    }

    /** Le rôle d'une personne sur un sujet ; le dernier rôle sourcé remplace le précédent. */
    public RadarSubjectRole assignRole(RadarScope scope, UUID subjectId, UUID personId, RadarRole role,
            Collection<UUID> evidenceIds) {
        RadarSubject subject = requireLiveSubject(scope, subjectId);
        RadarPerson person = requirePerson(scope, personId);
        if (role == null) {
            throw new InvalidRadarInputException("Le rôle est requis.");
        }
        List<RadarEvidence> proofs = requireEvidence(scope, evidenceIds);
        RadarSubjectRole assigned = roles.findByUserIdAndHostIdAndSubjectIdAndPersonId(
                        scope.userId(), scope.hostId(), subject.getId(), person.getId())
                .orElseGet(() -> RadarSubjectRole.builder()
                        .userId(scope.userId()).hostId(scope.hostId())
                        .subjectId(subject.getId()).personId(person.getId()).build());
        assigned.setRole(role);
        assigned = roles.save(assigned);
        justify(scope, subject, RadarLinkKind.ROLE, assigned.getId(), proofs);
        return assigned;
    }

    // -------------------------------------------------------------------------------- engagements

    /**
     * Enregistre un engagement ouvert, avec ses preuves. Avec une clé d'extraction déjà connue,
     * rend l'engagement existant en y ajoutant les nouvelles preuves.
     */
    public RadarCommitment recordCommitment(RadarScope scope, CommitmentInput input) {
        if (input == null || input.direction() == null) {
            throw new InvalidRadarInputException("Le sens de l'engagement est requis.");
        }
        if (input.certainty() == null) {
            throw new InvalidRadarInputException("La certitude de l'engagement est requise.");
        }
        RadarSubject subject = requireLiveSubject(scope, input.subjectId());
        String description = RadarText.required(input.description(),
                RadarCommitment.MAX_DESCRIPTION_LENGTH, "description");
        String key = RadarText.optional(input.extractionKey(),
                RadarCommitment.MAX_EXTRACTION_KEY_LENGTH, "extraction_key");
        List<RadarEvidence> proofs = requireEvidence(scope, input.evidenceIds());
        if (key != null) {
            var known = commitments.findByUserIdAndHostIdAndExtractionKey(scope.userId(), scope.hostId(), key);
            if (known.isPresent()) {
                RadarSubject owner = requireSubject(scope, known.get().getSubjectId());
                justify(scope, owner, RadarLinkKind.COMMITMENT, known.get().getId(), proofs, false);
                noteEvidence(known.get(), proofs);
                return known.get();
            }
        }
        validateParties(scope, input);
        RadarCommitment built = RadarCommitment.builder()
                .userId(scope.userId()).hostId(scope.hostId()).subjectId(subject.getId())
                .direction(input.direction()).description(description)
                .fromPersonId(input.fromPersonId()).toPersonId(input.toPersonId())
                .otherPersonId(input.otherPersonId())
                .dueDate(input.dueDate()).dueDeduced(input.dueDate() != null && input.dueDeduced())
                .status(RadarCommitmentStatus.OPEN).certainty(input.certainty())
                .extractionKey(key).build();
        noteEvidence(built, proofs);
        RadarCommitment commitment = commitments.save(built);
        justify(scope, subject, RadarLinkKind.COMMITMENT, commitment.getId(), proofs, false);
        return commitment;
    }

    /** Change le statut d'un engagement, avec ses preuves (ajoutées à celles qu'il avait). */
    public RadarCommitment markCommitment(RadarScope scope, UUID commitmentId,
            RadarCommitmentStatus status, Collection<UUID> evidenceIds) {
        RadarCommitment commitment = requireCommitment(scope, commitmentId);
        if (status == null) {
            throw new InvalidRadarInputException("Le statut est requis.");
        }
        List<RadarEvidence> proofs = requireEvidence(scope, evidenceIds);
        if (!commitment.isSovereign()) {
            // Un engagement corrigé par l'utilisateur garde son statut ; la preuve s'y ajoute (SF-99-02).
            commitment.setStatus(status);
        }
        noteEvidence(commitment, proofs);
        justify(scope, requireSubject(scope, commitment.getSubjectId()), RadarLinkKind.COMMITMENT,
                commitment.getId(), proofs, false);
        return commitment;
    }

    // ------------------------------------------------------------------------------------ synchro

    /** Ouvre une synchro. */
    public RadarSync startSync(RadarScope scope) {
        return syncs.save(RadarSync.builder()
                .userId(scope.userId()).hostId(scope.hostId())
                .status(RadarSyncStatus.RUNNING).startedAt(OffsetDateTime.now()).build());
    }

    /** Clôt une synchro avec son issue, sa couverture et sa consommation. */
    public RadarSync finishSync(RadarScope scope, UUID syncId, RadarSyncStatus status,
            String coverageJson, long consumedTokens) {
        RadarSync sync = syncs.findByIdAndUserIdAndHostId(syncId, scope.userId(), scope.hostId())
                .orElseThrow(() -> new RadarNotFoundException("Synchro introuvable."));
        if (status == null || status == RadarSyncStatus.RUNNING) {
            throw new InvalidRadarInputException("Une synchro se termine sur une issue.");
        }
        sync.setStatus(status);
        sync.setFinishedAt(OffsetDateTime.now());
        sync.setCoverage(coverageJson);
        // L'analyse (F-101) cumule sa consommation pendant et après la collecte : la fin de la collecte
        // ne la baisse jamais.
        sync.setConsumedTokens(Math.max(sync.getConsumedTokens(), Math.max(0, consumedTokens)));
        return sync;
    }

    // -------------------------------------------------------------------------------- vérifications

    /** Sujet du périmètre, ou 404. */
    public RadarSubject requireSubject(RadarScope scope, UUID subjectId) {
        if (subjectId == null) {
            throw new RadarNotFoundException("Sujet introuvable.");
        }
        return subjects.findByIdAndUserIdAndHostId(subjectId, scope.userId(), scope.hostId())
                .orElseThrow(() -> new RadarNotFoundException("Sujet introuvable."));
    }

    /**
     * Sujet du périmètre <b>vivant</b> : un sujet absorbé par une fusion renvoie à sa cible (SF-99-03).
     * C'est ce qui empêche une synchro tardive d'écrire sur une trace.
     */
    public RadarSubject requireLiveSubject(RadarScope scope, UUID subjectId) {
        RadarSubject subject = requireSubject(scope, subjectId);
        for (int hop = 0; subject.getMergedIntoId() != null && hop < MAX_MERGE_HOPS; hop++) {
            subject = requireSubject(scope, subject.getMergedIntoId());
        }
        if (subject.getMergedIntoId() != null) {
            throw new RadarNotFoundException("Sujet introuvable.");
        }
        return subject;
    }

    // ------------------------------------------------------------------------------------ alias

    /**
     * Un alias proposé par l'analyse. <b>Ignoré</b> si ce nom est déjà connu du sujet — en particulier
     * s'il a été <b>refusé</b> : une consigne de l'utilisateur est souveraine (SF-99-03).
     *
     * @return l'alias créé, ou vide s'il était déjà connu ou refusé
     */
    public Optional<RadarSubjectAlias> addAlias(RadarScope scope, UUID subjectId, String alias) {
        RadarSubject subject = requireLiveSubject(scope, subjectId);
        String clean = RadarText.required(alias, RadarSubjectAlias.MAX_ALIAS_LENGTH, "alias");
        String key = RadarText.key(clean);
        boolean known = aliases.findByUserIdAndHostIdAndSubjectIdOrderByCreatedAtAsc(
                        scope.userId(), scope.hostId(), subject.getId()).stream()
                .anyMatch(existing -> existing.getNormalized().equals(key));
        if (known || key.equals(RadarText.key(subject.getName()))) {
            return Optional.empty();
        }
        return Optional.of(aliases.save(RadarSubjectAlias.builder()
                .userId(scope.userId()).hostId(scope.hostId()).subjectId(subject.getId())
                .alias(clean).normalized(key).origin(RadarAliasOrigin.SYNC).build()));
    }

    /** Ce que l'invite de rattachement doit savoir d'un sujet (F-101). */
    public record SubjectContext(UUID id, String name, RadarSubjectState state, List<String> aliases,
            List<String> rejectedAliases, List<String> summary) {
    }

    /**
     * La matière du rattachement (SF-99-03) : pour chaque sujet non fusionné du poste, son nom, ses
     * alias, <b>les noms refusés</b> et son résumé.
     *
     * @param includeClosed les sujets clos, pour reconnaître un sujet qui se réveille (SF-99-04)
     */
    @Transactional(readOnly = true)
    public List<SubjectContext> attachmentContext(RadarScope scope, boolean includeClosed) {
        List<RadarSubject> live = subjects.findByUserIdAndHostId(scope.userId(), scope.hostId()).stream()
                .filter(s -> s.getMergedIntoId() == null)
                .filter(s -> includeClosed || s.getState() != RadarSubjectState.CLOSED)
                .toList();
        var aliasesBySubject = aliases.findByUserIdAndHostIdAndSubjectIdIn(scope.userId(), scope.hostId(),
                        live.stream().map(RadarSubject::getId).toList()).stream()
                .collect(Collectors.groupingBy(RadarSubjectAlias::getSubjectId));
        return live.stream().map(s -> {
            List<RadarSubjectAlias> own = aliasesBySubject.getOrDefault(s.getId(), List.of());
            return new SubjectContext(s.getId(), s.getName(), s.getState(),
                    own.stream().filter(a -> !a.isRejected()).map(RadarSubjectAlias::getAlias).toList(),
                    own.stream().filter(RadarSubjectAlias::isRejected).map(RadarSubjectAlias::getAlias).toList(),
                    facts.findByUserIdAndHostIdAndSubjectIdOrderByPositionAsc(scope.userId(), scope.hostId(), s.getId())
                            .stream().map(RadarSubjectFact::getText).toList());
        }).toList();
    }

    /** Recalcule la dernière activité d'un sujet depuis sa chronologie (après un déplacement de preuves). */
    public void recomputeActivity(RadarScope scope, RadarSubject subject) {
        List<UUID> ids = links.findByUserIdAndHostIdAndSubjectIdAndTargetKind(scope.userId(), scope.hostId(),
                        subject.getId(), RadarLinkKind.CHRONOLOGY).stream()
                .map(RadarEvidenceLink::getEvidenceId).distinct().toList();
        subject.setLastActivityAt(ids.isEmpty() ? null
                : evidence.findByUserIdAndHostIdAndIdIn(scope.userId(), scope.hostId(), ids).stream()
                        .map(RadarEvidence::getOccurredAt).max(Comparator.naturalOrder()).orElse(null));
        refreshWake(scope, subject);
        subjects.save(subject);
    }

    /** Engagement du périmètre, ou 404. */
    public RadarCommitment requireCommitment(RadarScope scope, UUID commitmentId) {
        if (commitmentId == null) {
            throw new RadarNotFoundException("Engagement introuvable.");
        }
        return commitments.findByIdAndUserIdAndHostId(commitmentId, scope.userId(), scope.hostId())
                .orElseThrow(() -> new RadarNotFoundException("Engagement introuvable."));
    }

    /**
     * Les preuves désignées, toutes du périmètre.
     *
     * @throws RadarEvidenceRequiredException si la liste est vide ou si une preuve est hors périmètre
     */
    public List<RadarEvidence> requireEvidence(RadarScope scope, Collection<UUID> evidenceIds) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (evidenceIds != null) {
            evidenceIds.stream().filter(Objects::nonNull).forEach(ids::add);
        }
        if (ids.isEmpty()) {
            throw new RadarEvidenceRequiredException("Pas de fait sans preuve : aucune preuve fournie.");
        }
        List<RadarEvidence> found = evidence.findByUserIdAndHostIdAndIdIn(scope.userId(), scope.hostId(), ids);
        if (found.size() != ids.size()) {
            throw new RadarEvidenceRequiredException(
                    "Pas de fait sans preuve : une preuve désignée est introuvable sur ce poste.");
        }
        return found;
    }

    private RadarPerson requirePerson(RadarScope scope, UUID personId) {
        return people.findByIdAndUserIdAndHostId(personId, scope.userId(), scope.hostId())
                .orElseThrow(() -> new InvalidRadarInputException(
                        "Personne introuvable sur ce poste : " + personId));
    }

    /** La preuve la plus récente d'un engagement, et sa relance due (F-101 / SF-101-04). */
    private static void noteEvidence(RadarCommitment commitment, List<RadarEvidence> proofs) {
        proofs.stream().map(RadarEvidence::getOccurredAt).max(Comparator.naturalOrder())
                .ifPresentOrElse(commitment::noteEvidence, () -> commitment.noteEvidence(null));
    }

    private void validateParties(RadarScope scope, CommitmentInput input) {
        for (UUID personId : new UUID[] {input.fromPersonId(), input.toPersonId(), input.otherPersonId()}) {
            if (personId != null) {
                requirePerson(scope, personId);
            }
        }
        switch (input.direction()) {
            case ME_TO_OTHER -> {
                if (input.fromPersonId() != null || input.otherPersonId() != null) {
                    throw new InvalidRadarInputException("« À faire par moi » : le débiteur, c'est moi.");
                }
            }
            case OTHER_TO_ME -> {
                if (input.fromPersonId() == null || input.otherPersonId() != null) {
                    throw new InvalidRadarInputException("« J'attends des autres » : qui doit est requis.");
                }
            }
            case INTRODUCTION -> {
                if (input.fromPersonId() != null || input.toPersonId() == null
                        || input.otherPersonId() == null
                        || input.toPersonId().equals(input.otherPersonId())) {
                    throw new InvalidRadarInputException(
                            "Une mise en relation désigne deux personnes distinctes.");
                }
            }
            default -> throw new InvalidRadarInputException("Sens d'engagement inconnu.");
        }
    }

    private static RadarSubjectState requireOpenWork(RadarSubjectState state) {
        if (!state.isOpenWork()) {
            throw new InvalidRadarInputException(
                    "L'état " + state + " obéit aux règles de clôture et de sommeil.");
        }
        return state;
    }

    // ----------------------------------------------------------------------------------- liens

    /** Remplace les preuves d'une valeur courante, et range ces preuves dans la chronologie. */
    void justify(RadarScope scope, RadarSubject subject, RadarLinkKind kind, UUID targetId,
            List<RadarEvidence> proofs) {
        justify(scope, subject, kind, targetId, proofs, true);
    }

    void justify(RadarScope scope, RadarSubject subject, RadarLinkKind kind, UUID targetId,
            List<RadarEvidence> proofs, boolean replace) {
        if (replace) {
            links.deleteAll(links.findByUserIdAndHostIdAndSubjectIdAndTargetKind(
                            scope.userId(), scope.hostId(), subject.getId(), kind).stream()
                    .filter(link -> Objects.equals(link.getTargetId(), targetId))
                    .toList());
        }
        addLinks(scope, subject.getId(), kind, targetId, proofs);
        addToChronology(scope, subject, proofs);
    }

    void addToChronology(RadarScope scope, RadarSubject subject, List<RadarEvidence> proofs) {
        OffsetDateTime known = subject.getLastActivityAt();
        addLinks(scope, subject.getId(), RadarLinkKind.CHRONOLOGY, null, proofs);
        proofs.stream().map(RadarEvidence::getOccurredAt).max(Comparator.naturalOrder())
                .ifPresent(latest -> {
                    if (known == null || known.isBefore(latest)) {
                        subject.setLastActivityAt(latest);
                        if (subject.getState() == RadarSubjectState.DORMANT && known != null) {
                            // Le silence est rompu : retour à l'état d'avant le sommeil (SF-99-04).
                            wakeFromDormancy(subject);
                        }
                    }
                });
        refreshWake(scope, subject);
        subjects.save(subject);
    }

    /** Sort un sujet du sommeil vers l'état qu'il avait. */
    static void wakeFromDormancy(RadarSubject subject) {
        RadarSubjectState before = subject.getPreviousState();
        subject.setState(before != null && before.isOpenWork() ? before : RadarSubjectState.ADVANCING);
        subject.setPreviousState(null);
        subject.setDormantSince(null);
    }

    /**
     * Recalcule le <b>réveil</b> d'un sujet clos (SF-99-04) : une preuve de sa chronologie datée après
     * la clôture, et rangée après le dernier écart de l'utilisateur. Sans effet sur un sujet non clos.
     */
    void refreshWake(RadarScope scope, RadarSubject subject) {
        if (subject.getState() != RadarSubjectState.CLOSED || subject.getClosedAt() == null) {
            return;
        }
        List<RadarEvidenceLink> chronology = links.findByUserIdAndHostIdAndSubjectIdAndTargetKind(
                scope.userId(), scope.hostId(), subject.getId(), RadarLinkKind.CHRONOLOGY);
        Set<UUID> after = wakeEvidenceIds(scope, subject, chronology);
        if (after.isEmpty()) {
            subject.setWokeAt(null);
        } else if (subject.getWokeAt() == null) {
            subject.setWokeAt(OffsetDateTime.now());
        }
    }

    /** Les preuves qui réveillent un sujet clos. */
    Set<UUID> wakeEvidenceIds(RadarScope scope, RadarSubject subject, List<RadarEvidenceLink> chronology) {
        Set<UUID> result = new LinkedHashSet<>();
        if (subject.getState() != RadarSubjectState.CLOSED || subject.getClosedAt() == null || chronology.isEmpty()) {
            return result;
        }
        OffsetDateTime dismissed = subject.getWakeDismissedAt();
        var fresh = chronology.stream()
                .filter(l -> dismissed == null || l.getCreatedAt() == null || l.getCreatedAt().isAfter(dismissed))
                .map(RadarEvidenceLink::getEvidenceId).collect(Collectors.toSet());
        if (fresh.isEmpty()) {
            return result;
        }
        evidence.findByUserIdAndHostIdAndIdIn(scope.userId(), scope.hostId(), fresh).stream()
                .filter(e -> e.getOccurredAt().isAfter(subject.getClosedAt()))
                .sorted(Comparator.comparing(RadarEvidence::getOccurredAt))
                .forEach(e -> result.add(e.getId()));
        return result;
    }

    private void addLinks(RadarScope scope, UUID subjectId, RadarLinkKind kind, UUID targetId,
            List<RadarEvidence> proofs) {
        Set<UUID> present = new LinkedHashSet<>();
        links.findByUserIdAndHostIdAndSubjectIdAndTargetKind(scope.userId(), scope.hostId(), subjectId, kind)
                .stream()
                .filter(link -> Objects.equals(link.getTargetId(), targetId))
                .forEach(link -> present.add(link.getEvidenceId()));
        for (RadarEvidence proof : proofs) {
            if (present.add(proof.getId())) {
                links.save(RadarEvidenceLink.builder()
                        .userId(scope.userId()).hostId(scope.hostId())
                        .evidenceId(proof.getId()).subjectId(subjectId)
                        .targetKind(kind).targetId(targetId).build());
            }
        }
    }
}
