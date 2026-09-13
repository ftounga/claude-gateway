package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.radar.RadarRegistry.CommitmentInput;
import fr.claudegateway.radar.dto.RadarCorrectionRequests.CommitmentCorrectionRequest;
import fr.claudegateway.radar.dto.RadarCorrectionRequests.SubjectCorrectionRequest;
import fr.claudegateway.radar.dto.RadarViews.CorrectionView;

/**
 * F-99 / SF-99-02 — <b>l'utilisateur a le dernier mot</b> : ce qu'il corrige, une synchro ne le
 * réécrit pas ; tout se journalise ; tout s'annule.
 */
class RadarCorrectionServiceTest extends RadarIntegrationTestBase {

    private RadarEvidence proof;
    private RadarSubject subject;
    private RadarCommitment commitment;

    @BeforeEach
    void seed() {
        proof = proof(aliceA, "On lance le pilote MFA.");
        subject = registry.createSubject(aliceA, "MFA", RadarSubjectState.ADVANCING, ids(proof));
        commitment = registry.recordCommitment(aliceA, new CommitmentInput(subject.getId(),
                RadarCommitmentDirection.ME_TO_OTHER, "Envoyer le plan", null, null, null, null, false,
                RadarCertainty.PROBABLE, null, ids(proof)));
    }

    @Test
    @DisplayName("SOUVERAINETÉ : l'état dit par l'utilisateur résiste à la synchro ; la preuve entre quand même")
    void userStateResistsSync() {
        correctionService.correctSubject(aliceA, subject.getId(),
                new SubjectCorrectionRequest(RadarCorrectionAction.SET_STATE, null, RadarSubjectState.BLOCKED, null, null));
        RadarEvidence later = proof(aliceA, "Ça avance bien !");

        registry.setState(aliceA, subject.getId(), RadarSubjectState.ADVANCING, ids(later));

        RadarSubject reloaded = subjects.findById(subject.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(RadarSubjectState.BLOCKED);
        assertThat(reloaded.isStateSovereign()).isTrue();
        assertThat(links.findByUserIdAndHostIdAndSubjectIdAndTargetKind(aliceA.userId(), aliceA.hostId(),
                subject.getId(), RadarLinkKind.CHRONOLOGY)).extracting(RadarEvidenceLink::getEvidenceId)
                .contains(later.getId());
    }

    @Test
    @DisplayName("SOUVERAINETÉ : prochaine étape et échéance dites par l'utilisateur résistent à la synchro")
    void userNextStepAndDueDateResistSync() {
        LocalDate userDate = LocalDate.of(2026, 10, 15);
        correctionService.correctSubject(aliceA, subject.getId(),
                new SubjectCorrectionRequest(RadarCorrectionAction.SET_NEXT_STEP, null, null, "Voir Paul", null));
        correctionService.correctSubject(aliceA, subject.getId(),
                new SubjectCorrectionRequest(RadarCorrectionAction.SET_DUE_DATE, null, null, null, userDate));

        registry.setNextStep(aliceA, subject.getId(), "Autre chose", ids(proof));
        registry.setDueDate(aliceA, subject.getId(), LocalDate.of(2027, 1, 1), ids(proof));

        RadarSubject reloaded = subjects.findById(subject.getId()).orElseThrow();
        assertThat(reloaded.getNextStep()).isEqualTo("Voir Paul");
        assertThat(reloaded.getDueDate()).isEqualTo(userDate);
    }

    @Test
    @DisplayName("SOUVERAINETÉ : « fait » résiste à une synchro qui rouvrirait l'engagement")
    void doneResistsSync() {
        correctionService.correctCommitment(aliceA, commitment.getId(),
                new CommitmentCorrectionRequest(RadarCorrectionAction.DONE, null));

        registry.markCommitment(aliceA, commitment.getId(), RadarCommitmentStatus.OPEN, ids(proof(aliceA, "Relance")));

        RadarCommitment reloaded = commitments.findById(commitment.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RadarCommitmentStatus.KEPT);
        assertThat(reloaded.isSovereign()).isTrue();
    }

    @Test
    @DisplayName("renommer : l'ancien nom devient un alias ; annuler rétablit nom, marque, et retire l'alias")
    void renameLearnsAnAliasAndUndoRestoresEverything() {
        CorrectionView rename = correctionService.correctSubject(aliceA, subject.getId(),
                new SubjectCorrectionRequest(RadarCorrectionAction.RENAME, "Chantier Okta", null, null, null));

        assertThat(subjects.findById(subject.getId()).orElseThrow().getName()).isEqualTo("Chantier Okta");
        assertThat(aliases.findAll()).extracting(RadarSubjectAlias::getAlias).containsExactly("MFA");

        correctionService.undo(aliceA, rename.id());

        RadarSubject reloaded = subjects.findById(subject.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("MFA");
        assertThat(reloaded.isNameSovereign()).isFalse();
        assertThat(aliases.findAll()).isEmpty();
        assertThat(corrections.findById(rename.id()).orElseThrow().getUndoneAt()).isNotNull();
    }

    @Test
    @DisplayName("reporter : statut, échéance, et le journal garde l'avant et l'après")
    void postponeIsJournaled() {
        LocalDate date = LocalDate.of(2026, 9, 30);
        CorrectionView view = correctionService.correctCommitment(aliceA, commitment.getId(),
                new CommitmentCorrectionRequest(RadarCorrectionAction.POSTPONE, date));

        RadarCommitment reloaded = commitments.findById(commitment.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RadarCommitmentStatus.POSTPONED);
        assertThat(reloaded.getDueDate()).isEqualTo(date);
        assertThat(view.before().get("status").asText()).isEqualTo("OPEN");
        assertThat(view.after().get("dueDate").asText()).isEqualTo("2026-09-30");
        assertThat(view.subjectId()).isEqualTo(subject.getId());

        correctionService.undo(aliceA, view.id());
        RadarCommitment undone = commitments.findById(commitment.getId()).orElseThrow();
        assertThat(undone.getStatus()).isEqualTo(RadarCommitmentStatus.OPEN);
        assertThat(undone.getDueDate()).isNull();
        assertThat(undone.isSovereign()).isFalse();
    }

    @Test
    @DisplayName("« pas moi » puis « c'est moi » : désaveu levé, certitude posée")
    void notMineThenConfirm() {
        correctionService.correctCommitment(aliceA, commitment.getId(),
                new CommitmentCorrectionRequest(RadarCorrectionAction.NOT_MINE, null));
        assertThat(commitments.findById(commitment.getId()).orElseThrow().isDisowned()).isTrue();

        correctionService.correctCommitment(aliceA, commitment.getId(),
                new CommitmentCorrectionRequest(RadarCorrectionAction.CONFIRM, null));
        RadarCommitment reloaded = commitments.findById(commitment.getId()).orElseThrow();
        assertThat(reloaded.isDisowned()).isFalse();
        assertThat(reloaded.getCertainty()).isEqualTo(RadarCertainty.CERTAIN);
    }

    @Test
    @DisplayName("ANNULER : deux fois → 409 ; recouverte par une correction plus récente du même champ → 409")
    void undoConflicts() {
        CorrectionView blocked = correctionService.correctSubject(aliceA, subject.getId(),
                new SubjectCorrectionRequest(RadarCorrectionAction.SET_STATE, null, RadarSubjectState.BLOCKED, null, null));
        CorrectionView waiting = correctionService.correctSubject(aliceA, subject.getId(),
                new SubjectCorrectionRequest(RadarCorrectionAction.SET_STATE, null, RadarSubjectState.WAITING, null, null));

        assertThatThrownBy(() -> correctionService.undo(aliceA, blocked.id()))
                .isInstanceOf(RadarCorrectionConflictException.class);

        correctionService.undo(aliceA, waiting.id());
        RadarSubject afterFirstUndo = subjects.findById(subject.getId()).orElseThrow();
        assertThat(afterFirstUndo.getState()).isEqualTo(RadarSubjectState.BLOCKED);
        // La correction plus ancienne tient toujours la marque.
        assertThat(afterFirstUndo.isStateSovereign()).isTrue();
        assertThatThrownBy(() -> correctionService.undo(aliceA, waiting.id()))
                .isInstanceOf(RadarCorrectionConflictException.class);

        correctionService.undo(aliceA, blocked.id());
        RadarSubject restored = subjects.findById(subject.getId()).orElseThrow();
        assertThat(restored.getState()).isEqualTo(RadarSubjectState.ADVANCING);
        assertThat(restored.isStateSovereign()).isFalse();
    }

    @Test
    @DisplayName("ANNULER un renommage sous un changement d'état plus récent : permis, champs distincts")
    void undoOfDisjointFieldsIsAllowed() {
        CorrectionView rename = correctionService.correctSubject(aliceA, subject.getId(),
                new SubjectCorrectionRequest(RadarCorrectionAction.RENAME, "Okta", null, null, null));
        correctionService.correctSubject(aliceA, subject.getId(),
                new SubjectCorrectionRequest(RadarCorrectionAction.SET_STATE, null, RadarSubjectState.WAITING, null, null));

        correctionService.undo(aliceA, rename.id());

        RadarSubject reloaded = subjects.findById(subject.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("MFA");
        assertThat(reloaded.getState()).isEqualTo(RadarSubjectState.WAITING);
    }

    @Test
    void invalidCorrectionsAreRefused() {
        assertThatThrownBy(() -> correctionService.correctSubject(aliceA, subject.getId(),
                new SubjectCorrectionRequest(RadarCorrectionAction.SET_STATE, null, RadarSubjectState.CLOSED, null, null)))
                .isInstanceOf(InvalidRadarInputException.class);
        assertThatThrownBy(() -> correctionService.correctSubject(aliceA, subject.getId(),
                new SubjectCorrectionRequest(RadarCorrectionAction.RENAME, "  ", null, null, null)))
                .isInstanceOf(InvalidRadarInputException.class);
        assertThatThrownBy(() -> correctionService.correctCommitment(aliceA, commitment.getId(),
                new CommitmentCorrectionRequest(RadarCorrectionAction.POSTPONE, null)))
                .isInstanceOf(InvalidRadarInputException.class);
        assertThatThrownBy(() -> correctionService.correctCommitment(aliceA, commitment.getId(),
                new CommitmentCorrectionRequest(RadarCorrectionAction.RENAME, null)))
                .isInstanceOf(InvalidRadarInputException.class);
        assertThat(corrections.findAll()).isEmpty();
    }

    @Test
    @DisplayName("ISOLATION : corriger ou annuler depuis un autre poste → introuvable, rien n'est écrit")
    void anotherHostCannotCorrectOrUndo() {
        assertThatThrownBy(() -> correctionService.correctSubject(aliceB, subject.getId(),
                new SubjectCorrectionRequest(RadarCorrectionAction.RENAME, "Piraté", null, null, null)))
                .isInstanceOf(RadarNotFoundException.class);
        CorrectionView mine = correctionService.correctCommitment(aliceA, commitment.getId(),
                new CommitmentCorrectionRequest(RadarCorrectionAction.DONE, null));

        assertThatThrownBy(() -> correctionService.undo(bobScope, mine.id()))
                .isInstanceOf(RadarNotFoundException.class);
        assertThat(correctionService.journal(aliceB, null)).isEmpty();
        assertThat(correctionService.journal(aliceA, subject.getId())).hasSize(1);
        assertThat(subjects.findById(subject.getId()).orElseThrow().getName()).isEqualTo("MFA");
        assertThat(List.of(commitments.findById(commitment.getId()).orElseThrow().getStatus()))
                .containsExactly(RadarCommitmentStatus.KEPT);
    }
}
