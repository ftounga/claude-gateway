package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.radar.dto.RadarCorrectionRequests.CommitmentCorrectionRequest;

/** F-104 / SF-104-01 — les outils Radar sur le registre réel (H2), isolation comprise. */
class RadarToolExecutorIntegrationTest extends RadarIntegrationTestBase {

    @Autowired private RadarToolExecutor executor;
    @Autowired private ObjectMapper mapper;

    private final UUID messageId = UUID.randomUUID();

    private RadarNote note(String text) {
        return RadarNote.ofTerminalMessage(messageId, text, OffsetDateTime.now().minusMinutes(1));
    }

    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private RadarToolExecutor.Outcome run(RadarScope scope, String tool, String input, String said) {
        return executor.execute(scope, tool, json(input), note(said));
    }

    private RadarSubject mfa() {
        RadarSubject subject = registry.createSubject(aliceA, "MFA prestataires", RadarSubjectState.ADVANCING,
                ids(proof(aliceA, "On lance le MFA des prestataires.")));
        registry.addAlias(aliceA, subject.getId(), "double auth des presta");
        return subject;
    }

    @Test
    @DisplayName("trouver : par un mot du nom, par un alias ; jamais un sujet d'un autre poste ou d'un autre compte")
    void findByWordAndAliasWithinScope() {
        RadarSubject subject = mfa();
        registry.createSubject(aliceB, "MFA CAGIP", null, ids(proof(aliceB, "MFA chez CAGIP")));
        registry.createSubject(bobScope, "MFA de Bob", null, ids(proof(bobScope, "MFA chez Bob")));

        JsonNode byWord = json(run(aliceA, RadarToolCatalog.FIND_SUBJECT, "{\"query\":\"où en est le MFA ?\"}", "")
                .content());
        assertThat(byWord.path("subjects")).hasSize(1);
        assertThat(byWord.path("subjects").get(0).path("id").asText()).isEqualTo(subject.getId().toString());

        JsonNode byAlias = json(run(aliceA, RadarToolCatalog.FIND_SUBJECT, "{\"query\":\"presta\"}", "").content());
        assertThat(byAlias.path("subjects").get(0).path("name").asText()).isEqualTo("MFA prestataires");

        String all = run(aliceA, RadarToolCatalog.FIND_SUBJECT, "{}", "").content();
        assertThat(all).contains("MFA prestataires").doesNotContain("CAGIP", "Bob");
        assertThat(evidence.findAll()).noneMatch(e -> e.getSource() == RadarEvidenceSource.USER_NOTE);
    }

    @Test
    @DisplayName("mettre à jour : corrections souveraines marquées d'UNE preuve USER_NOTE, rangée dans la chronologie")
    void updateWritesSovereignCorrectionsWithOneProof() {
        RadarSubject subject = mfa();

        RadarToolExecutor.Outcome first = run(aliceA, RadarToolCatalog.UPDATE_SUBJECT,
                "{\"subject_id\":\"" + subject.getId() + "\",\"next_step\":\"Pilote en octobre\"}",
                "Paul m'a dit que le pilote MFA glisse à octobre");
        RadarToolExecutor.Outcome second = run(aliceA, RadarToolCatalog.UPDATE_SUBJECT,
                "{\"subject_id\":\"" + subject.getId() + "\",\"state\":\"WAITING\",\"due_date\":\"2026-10-15\"}",
                "Paul m'a dit que le pilote MFA glisse à octobre");

        assertThat(first.error()).isFalse();
        assertThat(second.changes()).hasSize(2);
        assertThat(first.evidenceId()).isEqualTo(second.evidenceId());
        RadarSubject reloaded = subjects.findById(subject.getId()).orElseThrow();
        assertThat(reloaded.getNextStep()).isEqualTo("Pilote en octobre");
        assertThat(reloaded.isNextStepSovereign()).isTrue();
        assertThat(reloaded.getState()).isEqualTo(RadarSubjectState.WAITING);
        assertThat(reloaded.getDueDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(corrections.findByUserIdAndHostIdAndEvidenceIdOrderByCreatedAtDesc(
                aliceA.userId(), aliceA.hostId(), first.evidenceId())).hasSize(3);
        RadarEvidence note = evidence.findById(first.evidenceId()).orElseThrow();
        assertThat(note.getSource()).isEqualTo(RadarEvidenceSource.USER_NOTE);
        assertThat(note.getSourceRef()).isEqualTo("atelier-message:" + messageId);
        assertThat(links.findByUserIdAndHostIdAndEvidenceId(aliceA.userId(), aliceA.hostId(), note.getId()))
                .anyMatch(l -> l.getTargetKind() == RadarLinkKind.CHRONOLOGY && l.getSubjectId().equals(subject.getId()));

        RadarToolExecutor.Outcome same = run(aliceA, RadarToolCatalog.UPDATE_SUBJECT,
                "{\"subject_id\":\"" + subject.getId() + "\",\"state\":\"WAITING\"}", "autre message");
        assertThat(same.error()).isFalse();
        assertThat(same.content()).startsWith("Rien à changer");
        assertThat(same.evidenceId()).isNull();
    }

    @Test
    @DisplayName("créer un sujet, puis annuler la création : le sujet disparaît ; conflit s'il a vécu depuis")
    void createThenUndo() {
        RadarToolExecutor.Outcome created = run(aliceA, RadarToolCatalog.UPDATE_SUBJECT,
                "{\"new_subject_name\":\"Accès réseau pour Sophie\",\"next_step\":\"Présenter Sophie à Karim\"}",
                "Nouveau sujet : l'accès réseau de Sophie");
        assertThat(created.error()).isFalse();
        UUID subjectId = created.changes().get(0).subjectId();
        assertThat(subjects.findById(subjectId)).isPresent();

        // Défaire dans l'ordre inverse : la prochaine étape, puis la création.
        List<RadarCorrection> rows = corrections.findByUserIdAndHostIdAndEvidenceIdOrderByCreatedAtDesc(
                aliceA.userId(), aliceA.hostId(), created.evidenceId());
        assertThat(rows).extracting(RadarCorrection::getAction)
                .containsExactly(RadarCorrectionAction.SET_NEXT_STEP, RadarCorrectionAction.CREATE_SUBJECT);
        correctionService.undo(aliceA, rows.get(0).getId());
        correctionService.undo(aliceA, rows.get(1).getId());
        assertThat(subjects.findById(subjectId)).isEmpty();

        RadarToolExecutor.Outcome again = run(aliceA, RadarToolCatalog.UPDATE_SUBJECT,
                "{\"new_subject_name\":\"Revue RSSI\"}", "un autre sujet : la revue RSSI");
        UUID revue = again.changes().get(0).subjectId();
        registry.attachEvidence(aliceA, revue, ids(proof(aliceA, "La revue RSSI est planifiée.")));
        UUID creation = corrections.findByUserIdAndHostIdAndEvidenceIdOrderByCreatedAtDesc(
                aliceA.userId(), aliceA.hostId(), again.evidenceId()).get(0).getId();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> correctionService.undo(aliceA, creation))
                .isInstanceOf(RadarCorrectionConflictException.class);
    }

    @Test
    @DisplayName("clore : clos immédiatement, les engagements ouverts sont rendus pour poser la question")
    void closeReturnsOpenCommitments() {
        RadarSubject ldap = registry.createSubject(aliceA, "LDAP", RadarSubjectState.ADVANCING,
                ids(proof(aliceA, "Migration LDAP")));
        registry.recordCommitment(aliceA, new RadarRegistry.CommitmentInput(ldap.getId(),
                RadarCommitmentDirection.ME_TO_OTHER, "Envoyer le bilan LDAP", null, null, null, null, false,
                RadarCertainty.CERTAIN, null, ids(proof(aliceA, "je t'envoie le bilan"))));

        RadarToolExecutor.Outcome closed = run(aliceA, RadarToolCatalog.CLOSE_SUBJECT,
                "{\"subject_id\":\"" + ldap.getId() + "\"}", "le sujet LDAP peut être considéré comme clos");

        assertThat(closed.error()).isFalse();
        assertThat(closed.content()).contains("Envoyer le bilan LDAP", "NE LES FERME PAS");
        assertThat(subjects.findById(ldap.getId()).orElseThrow().getState()).isEqualTo(RadarSubjectState.CLOSED);

        RadarToolExecutor.Outcome twice = run(aliceA, RadarToolCatalog.CLOSE_SUBJECT,
                "{\"subject_id\":\"" + ldap.getId() + "\"}", "clos");
        assertThat(twice.error()).isTrue();
        assertThat(twice.content()).contains("déjà clos");
    }

    @Test
    @DisplayName("ajouter un engagement (personne retrouvée ou créée), le marquer, l'annuler ; conflit s'il a été corrigé")
    void addMarkAndUndoCommitment() {
        RadarSubject subject = mfa();
        registry.upsertPerson(aliceA, "teams:julie", "Julie Martin", null);

        RadarToolExecutor.Outcome added = run(aliceA, RadarToolCatalog.ADD_ENGAGEMENT,
                "{\"subject_id\":\"" + subject.getId() + "\",\"direction\":\"OTHER_TO_ME\","
                        + "\"description\":\"Retour de l'éditeur SSO\",\"from_person\":\"julie martin\","
                        + "\"due_date\":\"2026-09-20\"}",
                "Julie doit me faire le retour de l'éditeur SSO pour le 20");
        assertThat(added.error()).isFalse();
        RadarCommitment commitment = commitments.findByUserIdAndHostIdAndSubjectId(
                aliceA.userId(), aliceA.hostId(), subject.getId()).get(0);
        assertThat(commitment.getCertainty()).isEqualTo(RadarCertainty.CERTAIN);
        assertThat(commitment.isSovereign()).isTrue();
        assertThat(people.findById(commitment.getFromPersonId()).orElseThrow().getSourceKey()).isEqualTo("teams:julie");

        RadarToolExecutor.Outcome withNewPerson = run(aliceA, RadarToolCatalog.ADD_ENGAGEMENT,
                "{\"subject_id\":\"" + subject.getId() + "\",\"direction\":\"INTRODUCTION\","
                        + "\"description\":\"Présenter Sophie à Karim\",\"to_person\":\"Sophie\",\"other_person\":\"Karim\"}",
                "je dois présenter Sophie à Karim");
        assertThat(withNewPerson.error()).isFalse();
        assertThat(people.findByUserIdAndHostIdAndSourceKey(aliceA.userId(), aliceA.hostId(), "note:karim")).isPresent();

        RadarToolExecutor.Outcome marked = run(aliceA, RadarToolCatalog.MARK_ENGAGEMENT,
                "{\"commitment_id\":\"" + commitment.getId() + "\",\"status\":\"DONE\"}", "Julie m'a répondu");
        assertThat(marked.error()).isFalse();
        assertThat(commitments.findById(commitment.getId()).orElseThrow().getStatus()).isEqualTo(RadarCommitmentStatus.KEPT);

        UUID addition = corrections.findByUserIdAndHostIdAndEvidenceIdOrderByCreatedAtDesc(
                aliceA.userId(), aliceA.hostId(), added.evidenceId()).stream()
                .filter(c -> c.getAction() == RadarCorrectionAction.ADD_COMMITMENT && c.getTargetId().equals(commitment.getId()))
                .findFirst().orElseThrow().getId();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> correctionService.undo(aliceA, addition))
                .isInstanceOf(RadarCorrectionConflictException.class);

        UUID introduction = commitments.findByUserIdAndHostIdAndSubjectId(aliceA.userId(), aliceA.hostId(), subject.getId())
                .stream().filter(c -> c.getDirection() == RadarCommitmentDirection.INTRODUCTION).findFirst().orElseThrow().getId();
        UUID introductionAddition = corrections.findByUserIdAndHostIdAndTargetIdAndUndoneAtIsNull(
                aliceA.userId(), aliceA.hostId(), introduction).get(0).getId();
        correctionService.undo(aliceA, introductionAddition);
        assertThat(commitments.findById(introduction)).isEmpty();
        assertThat(links.findByUserIdAndHostIdAndTargetKindAndTargetId(aliceA.userId(), aliceA.hostId(),
                RadarLinkKind.COMMITMENT, introduction)).isEmpty();
        correctionService.correctCommitment(aliceA, commitment.getId(),
                new CommitmentCorrectionRequest(RadarCorrectionAction.REOPEN, null));
    }

    @Test
    @DisplayName("fusionner deux sujets que l'utilisateur dit être le même")
    void mergeSubjects() {
        RadarSubject subject = mfa();
        RadarSubject okta = registry.createSubject(aliceA, "Chantier Okta", null, ids(proof(aliceA, "Okta")));

        RadarToolExecutor.Outcome merged = run(aliceA, RadarToolCatalog.MERGE_SUBJECTS,
                "{\"source_subject_id\":\"" + okta.getId() + "\",\"into_subject_id\":\"" + subject.getId() + "\"}",
                "le chantier Okta, c'est le MFA");

        assertThat(merged.error()).isFalse();
        assertThat(subjects.findById(okta.getId()).orElseThrow().getMergedIntoId()).isEqualTo(subject.getId());
        assertThat(corrections.findByUserIdAndHostIdAndEvidenceIdOrderByCreatedAtDesc(
                aliceA.userId(), aliceA.hostId(), merged.evidenceId()))
                .extracting(RadarCorrection::getAction).containsExactly(RadarCorrectionAction.MERGE);
    }

    @Test
    @DisplayName("ISOLATION et erreurs : un sujet d'un autre poste ou compte est introuvable, rien n'est écrit")
    void isolationAndErrorsWriteNothing() {
        RadarSubject cagip = registry.createSubject(aliceB, "Sujet CAGIP", null, ids(proof(aliceB, "CAGIP")));
        RadarSubject bobs = registry.createSubject(bobScope, "Sujet de Bob", null, ids(proof(bobScope, "Bob")));
        long evidenceBefore = evidence.count();
        long correctionsBefore = corrections.count();

        for (RadarSubject foreign : List.of(cagip, bobs)) {
            RadarToolExecutor.Outcome outcome = run(aliceA, RadarToolCatalog.CLOSE_SUBJECT,
                    "{\"subject_id\":\"" + foreign.getId() + "\"}", "clos");
            assertThat(outcome.error()).isTrue();
            assertThat(outcome.content()).contains("introuvable sur ce poste");
        }
        RadarSubject subject = mfa();
        long evidenceWithMfa = evidence.count();
        assertThat(run(aliceA, RadarToolCatalog.UPDATE_SUBJECT,
                "{\"subject_id\":\"" + subject.getId() + "\",\"state\":\"CLOSED\"}", "x").error()).isTrue();
        assertThat(run(aliceA, RadarToolCatalog.UPDATE_SUBJECT,
                "{\"subject_id\":\"" + subject.getId() + "\",\"due_date\":\"jeudi\"}", "x").error()).isTrue();
        // Partie manquante : l'erreur survient APRÈS la preuve — la transaction l'emporte.
        assertThat(run(aliceA, RadarToolCatalog.ADD_ENGAGEMENT,
                "{\"subject_id\":\"" + subject.getId() + "\",\"direction\":\"OTHER_TO_ME\",\"description\":\"x\"}",
                "x").error()).isTrue();

        assertThat(evidence.count()).isEqualTo(evidenceWithMfa);
        assertThat(evidenceWithMfa).isEqualTo(evidenceBefore + 1);
        assertThat(corrections.count()).isEqualTo(correctionsBefore);
        assertThat(subjects.findById(cagip.getId()).orElseThrow().getState()).isEqualTo(RadarSubjectState.NEW);
    }
}
