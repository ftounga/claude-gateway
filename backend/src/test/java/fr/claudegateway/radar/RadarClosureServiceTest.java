package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fr.claudegateway.radar.RadarRegistry.CommitmentInput;
import fr.claudegateway.radar.dto.RadarCorrectionRequests.CommitmentCorrectionRequest;
import fr.claudegateway.radar.dto.RadarCorrectionRequests.SubjectCorrectionRequest;
import fr.claudegateway.radar.dto.RadarViews.ClosureView;
import fr.claudegateway.radar.dto.RadarViews.CorrectionView;
import fr.claudegateway.radar.dto.RadarViews.SubjectDetail;

/**
 * F-99 / SF-99-04 — quand un sujet se termine : proposé sur signal, clos par la parole de
 * l'utilisateur, jamais par le silence, et jamais rouvert en silence.
 */
class RadarClosureServiceTest extends RadarIntegrationTestBase {

    @Autowired private RadarClosureService closure;
    @Autowired private RadarReadService read;

    private RadarSubject ldap;
    private RadarEvidence start;

    @BeforeEach
    void seed() {
        start = proof(aliceA, "On lance la migration LDAP.", OffsetDateTime.now().minusDays(5));
        ldap = registry.createSubject(aliceA, "LDAP", RadarSubjectState.BLOCKED, ids(start));
    }

    @Test
    @DisplayName("SIGNAL : « on peut fermer » propose la clôture avec la phrase qui la justifie")
    void explicitSignalProposesClosure() {
        RadarEvidence signal = proof(aliceA, "C'est bon pour moi, on peut fermer le sujet LDAP.",
                OffsetDateTime.now().minusHours(2));

        registry.proposeClosure(aliceA, ldap.getId(), ids(signal));

        SubjectDetail detail = read.subject(aliceA, ldap.getId());
        assertThat(detail.state()).isEqualTo(RadarSubjectState.CLOSE_PROPOSED);
        assertThat(detail.previousState()).isEqualTo(RadarSubjectState.BLOCKED);
        assertThat(detail.closeSignalEvidenceIds()).containsExactly(signal.getId());
        assertThat(detail.closeProposedAt()).isNotNull();
        assertThat(detail.closedAt()).isNull();
        assertThatThrownBy(() -> registry.proposeClosure(aliceA, ldap.getId(), List.of()))
                .isInstanceOf(RadarEvidenceRequiredException.class);
    }

    @Test
    @DisplayName("REFUS : retour à l'état d'avant ; le même signal ne repropose pas, un signal plus récent si")
    void rejectionIsRemembered() {
        RadarEvidence signal = proof(aliceA, "On peut fermer.", OffsetDateTime.now().minusHours(2));
        registry.proposeClosure(aliceA, ldap.getId(), ids(signal));

        closure.rejectProposal(aliceA, ldap.getId());
        assertThat(subjects.findById(ldap.getId()).orElseThrow().getState()).isEqualTo(RadarSubjectState.BLOCKED);

        registry.proposeClosure(aliceA, ldap.getId(), ids(signal));
        assertThat(subjects.findById(ldap.getId()).orElseThrow().getState()).isEqualTo(RadarSubjectState.BLOCKED);

        RadarEvidence later = proof(aliceA, "Cette fois c'est vraiment clos.", OffsetDateTime.now().plusMinutes(1));
        registry.proposeClosure(aliceA, ldap.getId(), ids(later));
        assertThat(subjects.findById(ldap.getId()).orElseThrow().getState()).isEqualTo(RadarSubjectState.CLOSE_PROPOSED);
    }

    @Test
    @DisplayName("CONFIRMER la proposition : clos")
    void confirmingClosesIt() {
        registry.proposeClosure(aliceA, ldap.getId(), ids(proof(aliceA, "On peut fermer.")));

        ClosureView view = closure.confirmProposal(aliceA, ldap.getId());

        assertThat(view.correction().action()).isEqualTo(RadarCorrectionAction.CONFIRM_CLOSE);
        RadarSubject closed = subjects.findById(ldap.getId()).orElseThrow();
        assertThat(closed.getState()).isEqualTo(RadarSubjectState.CLOSED);
        assertThat(closed.getClosedAt()).isNotNull();
        assertThat(closed.getCloseProposedAt()).isNull();
    }

    @Test
    @DisplayName("PAROLE SOUVERAINE : clos immédiatement, et les engagements encore ouverts sont signalés")
    void userClosesAndOpenCommitmentsAreSignalled() {
        RadarCommitment open = registry.recordCommitment(aliceA, commitment("Rendre l'accès admin", start));
        RadarCommitment kept = registry.recordCommitment(aliceA, commitment("Documenter", start));
        RadarCommitment disowned = registry.recordCommitment(aliceA, commitment("Former l'équipe", start));
        correctionService.correctCommitment(aliceA, kept.getId(), new CommitmentCorrectionRequest(RadarCorrectionAction.DONE, null));
        correctionService.correctCommitment(aliceA, disowned.getId(),
                new CommitmentCorrectionRequest(RadarCorrectionAction.NOT_MINE, null));

        ClosureView view = closure.close(aliceA, ldap.getId());

        assertThat(view.openCommitments()).extracting(c -> c.id()).containsExactly(open.getId());
        RadarSubject closed = subjects.findById(ldap.getId()).orElseThrow();
        assertThat(closed.getState()).isEqualTo(RadarSubjectState.CLOSED);
        assertThat(closed.isStateSovereign()).isTrue();
        assertThatThrownBy(() -> closure.close(aliceA, ldap.getId())).isInstanceOf(RadarStateConflictException.class);
        assertThatThrownBy(() -> closure.confirmProposal(aliceA, ldap.getId()))
                .isInstanceOf(RadarStateConflictException.class);
    }

    @Test
    @DisplayName("RÉVEIL : une activité après la clôture n'ouvre pas le sujet, elle l'annonce")
    void activityAfterClosureWakesButDoesNotReopen() {
        closure.close(aliceA, ldap.getId());
        RadarEvidence before = proof(aliceA, "Vieux message retrouvé.", OffsetDateTime.now().minusDays(2));
        registry.attachEvidence(aliceA, ldap.getId(), ids(before));
        assertThat(subjects.findById(ldap.getId()).orElseThrow().getWokeAt()).as("message antérieur").isNull();

        RadarEvidence after = proof(aliceA, "Le LDAP retombe en panne.", OffsetDateTime.now().plusSeconds(1));
        registry.setState(aliceA, ldap.getId(), RadarSubjectState.ADVANCING, ids(after));

        SubjectDetail detail = read.subject(aliceA, ldap.getId());
        assertThat(detail.state()).isEqualTo(RadarSubjectState.CLOSED);
        assertThat(detail.wokeAt()).isNotNull();
        assertThat(detail.wakeEvidenceIds()).containsExactly(after.getId());
        assertThat(read.subjects(aliceA, null, false)).singleElement().satisfies(s -> assertThat(s.awake()).isTrue());
    }

    @Test
    @DisplayName("RÉVEIL écarté : le sujet reste clos et sort de la liste ; rouvrir efface la clôture")
    void dismissOrReopen() {
        closure.close(aliceA, ldap.getId());
        registry.attachEvidence(aliceA, ldap.getId(), ids(proof(aliceA, "Relance", OffsetDateTime.now().plusSeconds(1))));

        closure.dismissWake(aliceA, ldap.getId());
        assertThat(subjects.findById(ldap.getId()).orElseThrow().getWokeAt()).isNull();
        assertThat(read.subjects(aliceA, null, false)).isEmpty();
        assertThatThrownBy(() -> closure.dismissWake(aliceA, ldap.getId())).isInstanceOf(RadarStateConflictException.class);

        correctionService.correctSubject(aliceA, ldap.getId(),
                new SubjectCorrectionRequest(RadarCorrectionAction.SET_STATE, null, RadarSubjectState.ADVANCING, null, null));
        RadarSubject reopened = subjects.findById(ldap.getId()).orElseThrow();
        assertThat(reopened.getState()).isEqualTo(RadarSubjectState.ADVANCING);
        assertThat(reopened.getClosedAt()).isNull();
        assertThat(reopened.getWakeDismissedAt()).isNull();
    }

    @Test
    @DisplayName("une synchro sur un sujet proposé garde la proposition et met à jour l'état d'avant")
    void syncOnProposedSubjectKeepsTheProposal() {
        registry.proposeClosure(aliceA, ldap.getId(), ids(proof(aliceA, "On peut fermer.")));

        registry.setState(aliceA, ldap.getId(), RadarSubjectState.WAITING, ids(proof(aliceA, "On attend Paul.")));

        RadarSubject subject = subjects.findById(ldap.getId()).orElseThrow();
        assertThat(subject.getState()).isEqualTo(RadarSubjectState.CLOSE_PROPOSED);
        assertThat(subject.getPreviousState()).isEqualTo(RadarSubjectState.WAITING);
        closure.rejectProposal(aliceA, ldap.getId());
        assertThat(subjects.findById(ldap.getId()).orElseThrow().getState()).isEqualTo(RadarSubjectState.WAITING);
    }

    @Test
    @DisplayName("ANNULER la clôture rétablit l'état d'avant")
    void undoClose() {
        ClosureView view = closure.close(aliceA, ldap.getId());

        CorrectionView undone = correctionService.undo(aliceA, view.correction().id());

        assertThat(undone.undoneAt()).isNotNull();
        RadarSubject subject = subjects.findById(ldap.getId()).orElseThrow();
        assertThat(subject.getState()).isEqualTo(RadarSubjectState.BLOCKED);
        assertThat(subject.getClosedAt()).isNull();
        assertThat(subject.isStateSovereign()).isFalse();
    }

    @Test
    @DisplayName("la recherche trouve un sujet clos par son alias")
    void searchFindsClosedSubjectsByAlias() {
        registry.addAlias(aliceA, ldap.getId(), "annuaire d'entreprise");
        closure.close(aliceA, ldap.getId());

        assertThat(read.subjects(aliceA, null, false)).isEmpty();
        assertThat(read.subjects(aliceA, null, false, "Annuaire")).extracting(s -> s.id()).containsExactly(ldap.getId());
        assertThat(read.subjects(aliceA, null, false, "rien")).isEmpty();
    }

    @Test
    @DisplayName("ISOLATION : clore le sujet d'un autre poste → introuvable")
    void closingAnotherHostsSubjectIsNotFound() {
        assertThatThrownBy(() -> closure.close(aliceB, ldap.getId())).isInstanceOf(RadarNotFoundException.class);
        assertThat(subjects.findById(ldap.getId()).orElseThrow().getState()).isEqualTo(RadarSubjectState.BLOCKED);
    }

    private CommitmentInput commitment(String description, RadarEvidence proof) {
        return new CommitmentInput(ldap.getId(), RadarCommitmentDirection.ME_TO_OTHER, description, null, null, null,
                null, false, RadarCertainty.CERTAIN, null, List.of(proof.getId()));
    }
}
