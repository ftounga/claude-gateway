package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.radar.RadarRegistry.CommitmentInput;
import fr.claudegateway.radar.RadarRegistry.EvidenceInput;
import fr.claudegateway.radar.RadarRegistry.SummarySentence;

/**
 * F-99 / SF-99-01 — le registre, écrit pour de bon : <b>pas de fait sans preuve</b>, idempotence par
 * identifiant de source, et un poste qui ne prête jamais ses preuves à un autre.
 */
class RadarRegistryTest extends RadarIntegrationTestBase {

    @Test
    @DisplayName("IDEMPOTENCE : la même preuve deux fois, une seule ligne")
    void sameSourceRefTwiceGivesOneRow() {
        OffsetDateTime at = OffsetDateTime.now().minusDays(1);
        EvidenceInput input = new EvidenceInput(RadarEvidenceSource.TEAMS_MESSAGE, "thread-1/msg-9", at,
                "Je m'en charge jeudi.", null, null);

        RadarEvidence first = registry.recordEvidence(aliceA, input);
        RadarEvidence second = registry.recordEvidence(aliceA, input);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(evidence.findByUserIdAndHostId(aliceA.userId(), aliceA.hostId())).hasSize(1);
    }

    @Test
    @DisplayName("le même identifiant de source sur un AUTRE poste est une autre preuve")
    void sameSourceRefOnAnotherHostIsAnotherRow() {
        EvidenceInput input = new EvidenceInput(RadarEvidenceSource.TEAMS_MESSAGE, "msg-1",
                OffsetDateTime.now(), "Bonjour", null, null);

        registry.recordEvidence(aliceA, input);
        registry.recordEvidence(aliceB, input);

        assertThat(evidence.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("PAS DE FAIT SANS PREUVE : un sujet sans preuve n'est pas créé")
    void subjectWithoutEvidenceIsRefused() {
        assertThatThrownBy(() -> registry.createSubject(aliceA, "MFA", null, List.of()))
                .isInstanceOf(RadarEvidenceRequiredException.class);
        assertThatThrownBy(() -> registry.createSubject(aliceA, "MFA", null, null))
                .isInstanceOf(RadarEvidenceRequiredException.class);

        assertThat(subjects.findAll()).isEmpty();
    }

    @Test
    @DisplayName("PAS DE FAIT SANS PREUVE : aucune écriture de valeur n'accepte une liste vide")
    void everyWriteRequiresEvidence() {
        RadarEvidence proof = proof(aliceA, "On lance le pilote MFA.");
        RadarSubject subject = registry.createSubject(aliceA, "MFA", null, ids(proof));
        RadarPerson person = registry.upsertPerson(aliceA, "jean@client.fr", "Jean", null);
        RadarCommitment commitment = registry.recordCommitment(aliceA, commitment(subject.getId(), ids(proof)));
        UUID id = subject.getId();

        assertThatThrownBy(() -> registry.setState(aliceA, id, RadarSubjectState.BLOCKED, List.of()))
                .isInstanceOf(RadarEvidenceRequiredException.class);
        assertThatThrownBy(() -> registry.setNextStep(aliceA, id, "Pilote", List.of()))
                .isInstanceOf(RadarEvidenceRequiredException.class);
        assertThatThrownBy(() -> registry.setDueDate(aliceA, id, LocalDate.now(), List.of()))
                .isInstanceOf(RadarEvidenceRequiredException.class);
        assertThatThrownBy(() -> registry.replaceSummary(aliceA, id,
                List.of(new SummarySentence("Le pilote démarre.", List.of()))))
                .isInstanceOf(RadarEvidenceRequiredException.class);
        assertThatThrownBy(() -> registry.assignRole(aliceA, id, person.getId(), RadarRole.DRIVES, List.of()))
                .isInstanceOf(RadarEvidenceRequiredException.class);
        assertThatThrownBy(() -> registry.recordCommitment(aliceA, commitment(id, List.of())))
                .isInstanceOf(RadarEvidenceRequiredException.class);
        assertThatThrownBy(() -> registry.markCommitment(aliceA, commitment.getId(),
                RadarCommitmentStatus.KEPT, List.of()))
                .isInstanceOf(RadarEvidenceRequiredException.class);

        RadarSubject reloaded = subjects.findById(id).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(RadarSubjectState.NEW);
        assertThat(reloaded.getNextStep()).isNull();
        assertThat(reloaded.getDueDate()).isNull();
        assertThat(facts.findAll()).isEmpty();
        assertThat(roles.findAll()).isEmpty();
        assertThat(commitments.findAll()).hasSize(1);
        assertThat(commitments.findById(commitment.getId()).orElseThrow().getStatus())
                .isEqualTo(RadarCommitmentStatus.OPEN);
    }

    @Test
    @DisplayName("ISOLATION : une preuve d'un autre poste du même utilisateur ne justifie rien ici")
    void evidenceFromAnotherHostIsNotEvidence() {
        RadarEvidence elsewhere = proof(aliceB, "Sur CAGIP.");
        RadarEvidence bobs = proof(bobScope, "Chez Bob.");

        assertThatThrownBy(() -> registry.createSubject(aliceA, "MFA", null, ids(elsewhere)))
                .isInstanceOf(RadarEvidenceRequiredException.class);
        assertThatThrownBy(() -> registry.createSubject(aliceA, "MFA", null, ids(bobs)))
                .isInstanceOf(RadarEvidenceRequiredException.class);
        assertThat(subjects.findAll()).isEmpty();
    }

    @Test
    @DisplayName("ISOLATION : un sujet d'un autre poste est introuvable")
    void subjectOfAnotherHostIsNotFound() {
        RadarSubject subject = registry.createSubject(aliceA, "MFA", null, ids(proof(aliceA, "MFA")));
        RadarEvidence other = proof(aliceB, "Ailleurs.");

        assertThatThrownBy(() -> registry.attachEvidence(aliceB, subject.getId(), ids(other)))
                .isInstanceOf(RadarNotFoundException.class);
    }

    @Test
    @DisplayName("la dernière activité suit la preuve la plus récente, jamais la plus récemment écrite")
    void lastActivityFollowsTheLatestEvidence() {
        OffsetDateTime recent = OffsetDateTime.now().minusHours(2);
        OffsetDateTime old = OffsetDateTime.now().minusDays(10);
        RadarSubject subject = registry.createSubject(aliceA, "LDAP", null, ids(proof(aliceA, "LDAP", recent)));

        registry.attachEvidence(aliceA, subject.getId(), ids(proof(aliceA, "Vieux message", old)));

        assertThat(subjects.findById(subject.getId()).orElseThrow().getLastActivityAt())
                .isEqualToIgnoringNanos(recent);
        assertThat(links.findByUserIdAndHostIdAndSubjectIdAndTargetKind(aliceA.userId(), aliceA.hostId(),
                subject.getId(), RadarLinkKind.CHRONOLOGY)).hasSize(2);
    }

    @Test
    @DisplayName("le résumé est remplacé phrase par phrase, chacune avec ses renvois")
    void summaryIsReplacedSentenceBySentence() {
        RadarEvidence p1 = proof(aliceA, "Pilote en octobre.");
        RadarEvidence p2 = proof(aliceA, "Okta retenu.");
        RadarSubject subject = registry.createSubject(aliceA, "MFA", null, ids(p1));

        registry.replaceSummary(aliceA, subject.getId(), List.of(new SummarySentence("Ancien.", ids(p1))));
        List<RadarSubjectFact> written = registry.replaceSummary(aliceA, subject.getId(), List.of(
                new SummarySentence("Le pilote démarre en octobre.", ids(p1)),
                new SummarySentence("Okta est retenu.", ids(p1, p2))));

        assertThat(facts.findAll()).extracting(RadarSubjectFact::getText)
                .containsExactlyInAnyOrder("Le pilote démarre en octobre.", "Okta est retenu.");
        assertThat(links.findByUserIdAndHostIdAndTargetKindAndTargetId(aliceA.userId(), aliceA.hostId(),
                RadarLinkKind.SUMMARY, written.get(1).getId())).hasSize(2);
        assertThat(links.findByUserIdAndHostIdAndSubjectIdAndTargetKind(aliceA.userId(), aliceA.hostId(),
                subject.getId(), RadarLinkKind.SUMMARY)).hasSize(3);
    }

    @Test
    @DisplayName("un résumé dont UNE phrase n'a pas de preuve laisse l'ancien résumé en place")
    void summaryWithOneUnsourcedSentenceWritesNothing() {
        RadarEvidence p1 = proof(aliceA, "Pilote.");
        RadarSubject subject = registry.createSubject(aliceA, "MFA", null, ids(p1));
        registry.replaceSummary(aliceA, subject.getId(), List.of(new SummarySentence("En place.", ids(p1))));

        assertThatThrownBy(() -> registry.replaceSummary(aliceA, subject.getId(), List.of(
                new SummarySentence("Sourcée.", ids(p1)),
                new SummarySentence("Inventée.", List.of()))))
                .isInstanceOf(RadarEvidenceRequiredException.class);

        assertThat(facts.findAll()).extracting(RadarSubjectFact::getText).containsExactly("En place.");
    }

    @Test
    @DisplayName("IDEMPOTENCE : un engagement de même clé d'extraction n'est pas dupliqué")
    void commitmentWithSameExtractionKeyIsNotDuplicated() {
        RadarEvidence p1 = proof(aliceA, "Je t'envoie le plan jeudi.");
        RadarEvidence p2 = proof(aliceA, "Rappel : le plan jeudi.");
        RadarSubject subject = registry.createSubject(aliceA, "MFA", null, ids(p1));

        RadarCommitment first = registry.recordCommitment(aliceA, commitmentWithKey(subject.getId(), ids(p1)));
        RadarCommitment again = registry.recordCommitment(aliceA, commitmentWithKey(subject.getId(), ids(p2)));

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(commitments.findAll()).hasSize(1);
        assertThat(links.findByUserIdAndHostIdAndTargetKindAndTargetId(aliceA.userId(), aliceA.hostId(),
                RadarLinkKind.COMMITMENT, first.getId())).hasSize(2);
    }

    @Test
    @DisplayName("une mise en relation désigne deux personnes distinctes du poste")
    void introductionNeedsTwoPeople() {
        RadarEvidence p = proof(aliceA, "Tu peux me présenter Paul ?");
        RadarSubject subject = registry.createSubject(aliceA, "MFA", null, ids(p));
        RadarPerson paul = registry.upsertPerson(aliceA, "paul@client.fr", "Paul", "RSSI");
        RadarPerson lea = registry.upsertPerson(aliceA, "lea@client.fr", "Léa", null);
        RadarPerson bobPerson = registry.upsertPerson(bobScope, "zoe@bob.fr", "Zoé", null);

        assertThatThrownBy(() -> registry.recordCommitment(aliceA, new CommitmentInput(subject.getId(),
                RadarCommitmentDirection.INTRODUCTION, "Présenter Paul à Léa", null, paul.getId(), paul.getId(),
                null, false, RadarCertainty.CERTAIN, null, ids(p))))
                .isInstanceOf(InvalidRadarInputException.class);
        assertThatThrownBy(() -> registry.recordCommitment(aliceA, new CommitmentInput(subject.getId(),
                RadarCommitmentDirection.INTRODUCTION, "Présenter Paul à Zoé", null, paul.getId(),
                bobPerson.getId(), null, false, RadarCertainty.CERTAIN, null, ids(p))))
                .isInstanceOf(InvalidRadarInputException.class);

        RadarCommitment intro = registry.recordCommitment(aliceA, new CommitmentInput(subject.getId(),
                RadarCommitmentDirection.INTRODUCTION, "Présenter Paul à Léa", null, paul.getId(), lea.getId(),
                LocalDate.now().plusDays(2), true, RadarCertainty.PROBABLE, null, ids(p)));

        assertThat(intro.getStatus()).isEqualTo(RadarCommitmentStatus.OPEN);
        assertThat(intro.isDueDeduced()).isTrue();
    }

    @Test
    @DisplayName("les états de clôture et de sommeil ne s'écrivent pas par la porte ordinaire")
    void closureStatesAreNotOrdinaryStates() {
        RadarEvidence p = proof(aliceA, "On peut fermer.");
        RadarSubject subject = registry.createSubject(aliceA, "LDAP", null, ids(p));

        assertThatThrownBy(() -> registry.setState(aliceA, subject.getId(), RadarSubjectState.CLOSED, ids(p)))
                .isInstanceOf(InvalidRadarInputException.class);
        assertThatThrownBy(() -> registry.createSubject(aliceA, "X", RadarSubjectState.DORMANT, ids(p)))
                .isInstanceOf(InvalidRadarInputException.class);
    }

    @Test
    @DisplayName("l'auteur d'une preuve voit sa dernière interaction avancer")
    void authorLastInteractionMoves() {
        RadarPerson jean = registry.upsertPerson(aliceA, "Jean@Client.fr", "Jean", null);
        OffsetDateTime at = OffsetDateTime.now().minusHours(3);

        registry.recordEvidence(aliceA, new EvidenceInput(RadarEvidenceSource.TEAMS_MESSAGE, "m-jean", at,
                "Je regarde.", null, jean.getId()));

        assertThat(people.findById(jean.getId()).orElseThrow().getLastInteractionAt())
                .isEqualToIgnoringNanos(at);
        assertThat(registry.upsertPerson(aliceA, "jean@client.fr", "Jean Dupont", "RSSI").getId())
                .isEqualTo(jean.getId());
    }

    @Test
    void syncStartsAndFinishes() {
        RadarSync sync = registry.startSync(aliceA);
        registry.finishSync(aliceA, sync.getId(), RadarSyncStatus.PARTIAL, "{\"teams\":{\"read\":12}}", 1500);

        RadarSync done = syncs.findById(sync.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(RadarSyncStatus.PARTIAL);
        assertThat(done.getFinishedAt()).isNotNull();
        assertThatThrownBy(() -> registry.finishSync(aliceB, sync.getId(), RadarSyncStatus.SUCCEEDED, null, 0))
                .isInstanceOf(RadarNotFoundException.class);
    }

    private static CommitmentInput commitment(UUID subjectId, List<UUID> evidenceIds) {
        return new CommitmentInput(subjectId, RadarCommitmentDirection.ME_TO_OTHER, "Envoyer le plan",
                null, null, null, null, false, RadarCertainty.CERTAIN, null, evidenceIds);
    }

    private static CommitmentInput commitmentWithKey(UUID subjectId, List<UUID> evidenceIds) {
        return new CommitmentInput(subjectId, RadarCommitmentDirection.ME_TO_OTHER, "Envoyer le plan",
                null, null, null, null, false, RadarCertainty.CERTAIN, "plan-jeudi", evidenceIds);
    }
}
