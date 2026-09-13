package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fr.claudegateway.radar.RadarRegistry.CommitmentInput;
import fr.claudegateway.radar.RadarRegistry.SubjectContext;
import fr.claudegateway.radar.RadarRegistry.SummarySentence;
import fr.claudegateway.radar.dto.RadarCorrectionRequests.SubjectCorrectionRequest;
import fr.claudegateway.radar.dto.RadarViews.CorrectionView;
import fr.claudegateway.radar.dto.RadarViews.SubjectDetail;

/**
 * F-99 / SF-99-03 — fusionner, séparer : tout ce qui bouge bouge avec ses preuves, tout s'annule, et
 * chaque geste laisse un alias ou une consigne pour ne pas refaire l'erreur.
 */
class RadarStructureServiceTest extends RadarIntegrationTestBase {

    @Autowired private RadarStructureService structure;
    @Autowired private RadarReadService read;

    private RadarEvidence mfaProof;
    private RadarEvidence doubleAuthProof;
    private RadarSubject mfa;
    private RadarSubject doubleAuth;
    private RadarPerson paul;

    @BeforeEach
    void seed() {
        mfaProof = proof(aliceA, "Pilote MFA en octobre.", OffsetDateTime.now().minusDays(3));
        doubleAuthProof = proof(aliceA, "La double auth des presta bloque.", OffsetDateTime.now().minusDays(1));
        mfa = registry.createSubject(aliceA, "MFA", RadarSubjectState.ADVANCING, ids(mfaProof));
        doubleAuth = registry.createSubject(aliceA, "Double auth des presta", RadarSubjectState.BLOCKED,
                ids(doubleAuthProof));
        registry.replaceSummary(aliceA, mfa.getId(), List.of(new SummarySentence("Pilote en octobre.", ids(mfaProof))));
        registry.replaceSummary(aliceA, doubleAuth.getId(),
                List.of(new SummarySentence("Les prestataires bloquent.", ids(doubleAuthProof))));
        paul = registry.upsertPerson(aliceA, "paul@client.fr", "Paul", null);
        registry.assignRole(aliceA, mfa.getId(), paul.getId(), RadarRole.DECIDES, ids(mfaProof));
        registry.assignRole(aliceA, doubleAuth.getId(), paul.getId(), RadarRole.INFORMED, ids(doubleAuthProof));
        registry.recordCommitment(aliceA, new CommitmentInput(doubleAuth.getId(), RadarCommitmentDirection.ME_TO_OTHER,
                "Relancer l'éditeur", null, null, null, null, false, RadarCertainty.CERTAIN, null, ids(doubleAuthProof)));
        registry.addAlias(aliceA, doubleAuth.getId(), "2FA presta");
    }

    @Test
    @DisplayName("FUSION : chronologie, engagements, phrases et alias passent à la cible ; ses valeurs gagnent")
    void mergeMovesEverythingButValues() {
        structure.merge(aliceA, doubleAuth.getId(), mfa.getId());

        SubjectDetail target = read.subject(aliceA, mfa.getId());
        assertThat(target.state()).isEqualTo(RadarSubjectState.ADVANCING);
        assertThat(target.chronology()).extracting(v -> v.id()).containsExactlyInAnyOrder(
                mfaProof.getId(), doubleAuthProof.getId());
        assertThat(target.commitments()).extracting(v -> v.description()).containsExactly("Relancer l'éditeur");
        assertThat(target.summary()).extracting(v -> v.text())
                .containsExactly("Pilote en octobre.", "Les prestataires bloquent.");
        assertThat(target.summary().get(1).evidenceIds()).containsExactly(doubleAuthProof.getId());
        assertThat(target.aliases()).extracting(v -> v.alias())
                .containsExactlyInAnyOrder("2FA presta", "Double auth des presta");
        // Paul décidait déjà sur MFA : son rôle de la source ne le remplace pas.
        assertThat(target.people()).singleElement().satisfies(r -> assertThat(r.role()).isEqualTo(RadarRole.DECIDES));
        assertThat(target.lastActivityAt()).isEqualToIgnoringNanos(doubleAuthProof.getOccurredAt());

        assertThat(read.subjects(aliceA, null, true)).extracting(v -> v.id()).containsExactly(mfa.getId());
        assertThat(read.subject(aliceA, doubleAuth.getId()).mergedIntoId()).isEqualTo(mfa.getId());
    }

    @Test
    @DisplayName("FUSION : une écriture tardive de la synchro sur le sujet absorbé arrive sur la cible")
    void lateSyncWriteIsRedirected() {
        structure.merge(aliceA, doubleAuth.getId(), mfa.getId());
        RadarEvidence late = proof(aliceA, "Toujours bloqué côté presta.");

        registry.attachEvidence(aliceA, doubleAuth.getId(), ids(late));

        assertThat(read.subject(aliceA, mfa.getId()).chronology()).extracting(v -> v.id()).contains(late.getId());
    }

    @Test
    @DisplayName("FUSION : un sujet absorbé ne se fusionne, ne se sépare, ni ne se corrige plus")
    void mergedSubjectIsFrozen() {
        structure.merge(aliceA, doubleAuth.getId(), mfa.getId());

        assertThatThrownBy(() -> structure.merge(aliceA, mfa.getId(), doubleAuth.getId()))
                .isInstanceOf(RadarSubjectMergedException.class);
        assertThatThrownBy(() -> structure.split(aliceA, doubleAuth.getId(), "X", ids(doubleAuthProof), null))
                .isInstanceOf(RadarSubjectMergedException.class);
        assertThatThrownBy(() -> correctionService.correctSubject(aliceA, doubleAuth.getId(),
                new SubjectCorrectionRequest(RadarCorrectionAction.RENAME, "X", null, null, null)))
                .isInstanceOf(RadarSubjectMergedException.class);
        assertThatThrownBy(() -> structure.merge(aliceA, mfa.getId(), mfa.getId()))
                .isInstanceOf(InvalidRadarInputException.class);
    }

    @Test
    @DisplayName("FUSION annulée : la source revient avec tout ce qu'elle avait")
    void undoMergeRestoresTheSource() {
        CorrectionView merge = structure.merge(aliceA, doubleAuth.getId(), mfa.getId());

        correctionService.undo(aliceA, merge.id());

        SubjectDetail source = read.subject(aliceA, doubleAuth.getId());
        assertThat(source.mergedIntoId()).isNull();
        assertThat(source.chronology()).extracting(v -> v.id()).containsExactly(doubleAuthProof.getId());
        assertThat(source.commitments()).hasSize(1);
        assertThat(source.summary()).extracting(v -> v.text()).containsExactly("Les prestataires bloquent.");
        assertThat(source.aliases()).extracting(v -> v.alias()).containsExactly("2FA presta");
        SubjectDetail target = read.subject(aliceA, mfa.getId());
        assertThat(target.aliases()).isEmpty();
        assertThat(target.chronology()).extracting(v -> v.id()).containsExactly(mfaProof.getId());
        assertThat(read.subjects(aliceA, null, true)).hasSize(2);
    }

    @Test
    @DisplayName("SÉPARATION : preuves, engagement et phrase partent ; consignes des deux côtés")
    void splitMovesDesignatedEvidenceAndLeavesHints() {
        RadarEvidence okta = proof(aliceA, "Le contrat Okta est signé.", OffsetDateTime.now().minusHours(5));
        registry.attachEvidence(aliceA, mfa.getId(), ids(okta));
        registry.replaceSummary(aliceA, mfa.getId(), List.of(
                new SummarySentence("Pilote en octobre.", ids(mfaProof)),
                new SummarySentence("Contrat signé.", ids(okta))));
        RadarCommitment contract = registry.recordCommitment(aliceA, new CommitmentInput(mfa.getId(),
                RadarCommitmentDirection.ME_TO_OTHER, "Archiver le contrat", null, null, null, null, false,
                RadarCertainty.CERTAIN, null, ids(okta)));

        CorrectionView split = structure.split(aliceA, mfa.getId(), "Contrat Okta", ids(okta), List.of(contract.getId()));
        UUID createdId = UUID.fromString(split.after().get("created").asText());

        SubjectDetail created = read.subject(aliceA, createdId);
        assertThat(created.name()).isEqualTo("Contrat Okta");
        assertThat(created.nameSovereign()).isTrue();
        assertThat(created.chronology()).extracting(v -> v.id()).containsExactly(okta.getId());
        assertThat(created.commitments()).extracting(v -> v.description()).containsExactly("Archiver le contrat");
        assertThat(created.summary()).extracting(v -> v.text()).containsExactly("Contrat signé.");
        assertThat(created.aliases()).singleElement().satisfies(a -> {
            assertThat(a.alias()).isEqualTo("MFA");
            assertThat(a.rejected()).isTrue();
            assertThat(a.origin()).isEqualTo(RadarAliasOrigin.SPLIT);
        });
        SubjectDetail source = read.subject(aliceA, mfa.getId());
        assertThat(source.chronology()).extracting(v -> v.id()).containsExactly(mfaProof.getId());
        assertThat(source.summary()).extracting(v -> v.text()).containsExactly("Pilote en octobre.");
        assertThat(source.aliases()).singleElement().satisfies(a -> assertThat(a.rejected()).isTrue());

        // La consigne est souveraine : l'analyse ne peut plus proposer ce nom pour MFA.
        assertThat(registry.addAlias(aliceA, mfa.getId(), "contrat okta")).isEmpty();
        List<SubjectContext> context = registry.attachmentContext(aliceA, false);
        assertThat(context).filteredOn(c -> c.id().equals(mfa.getId())).singleElement()
                .satisfies(c -> assertThat(c.rejectedAliases()).containsExactly("Contrat Okta"));
    }

    @Test
    @DisplayName("SÉPARATION annulée : le nouveau sujet disparaît, tout revient")
    void undoSplitRemovesTheNewSubject() {
        RadarEvidence okta = proof(aliceA, "Okta signé.");
        registry.attachEvidence(aliceA, mfa.getId(), ids(okta));
        CorrectionView split = structure.split(aliceA, mfa.getId(), "Contrat Okta", ids(okta), null);
        UUID createdId = UUID.fromString(split.after().get("created").asText());

        correctionService.undo(aliceA, split.id());

        assertThat(subjects.findById(createdId)).isEmpty();
        SubjectDetail source = read.subject(aliceA, mfa.getId());
        assertThat(source.chronology()).extracting(v -> v.id()).containsExactlyInAnyOrder(mfaProof.getId(), okta.getId());
        assertThat(source.aliases()).isEmpty();
    }

    @Test
    @DisplayName("SÉPARATION annulée refusée si le nouveau sujet a vécu depuis")
    void undoSplitRefusedWhenTheNewSubjectLived() {
        RadarEvidence okta = proof(aliceA, "Okta signé.");
        registry.attachEvidence(aliceA, mfa.getId(), ids(okta));
        CorrectionView split = structure.split(aliceA, mfa.getId(), "Contrat Okta", ids(okta), null);
        UUID createdId = UUID.fromString(split.after().get("created").asText());
        registry.attachEvidence(aliceA, createdId, ids(proof(aliceA, "Nouvelle pièce")));

        assertThatThrownBy(() -> correctionService.undo(aliceA, split.id()))
                .isInstanceOf(RadarCorrectionConflictException.class);
    }

    @Test
    @DisplayName("annuler une fusion recouverte par une fusion plus récente de la cible → 409")
    void undoMergeCoveredByALaterMerge() {
        RadarSubject third = registry.createSubject(aliceA, "Okta", null, ids(proof(aliceA, "Okta")));
        CorrectionView first = structure.merge(aliceA, doubleAuth.getId(), mfa.getId());
        structure.merge(aliceA, mfa.getId(), third.getId());

        assertThatThrownBy(() -> correctionService.undo(aliceA, first.id()))
                .isInstanceOf(RadarCorrectionConflictException.class);
    }

    @Test
    void invalidSplitsAreRefused() {
        assertThatThrownBy(() -> structure.split(aliceA, mfa.getId(), "X", ids(mfaProof), null))
                .as("toutes les preuves").isInstanceOf(InvalidRadarInputException.class);
        RadarEvidence extra = proof(aliceA, "autre");
        registry.attachEvidence(aliceA, mfa.getId(), ids(extra));
        assertThatThrownBy(() -> structure.split(aliceA, mfa.getId(), "X", ids(doubleAuthProof), null))
                .as("preuve d'un autre sujet").isInstanceOf(InvalidRadarInputException.class);
        assertThatThrownBy(() -> structure.split(aliceA, mfa.getId(), " ", ids(extra), null))
                .isInstanceOf(InvalidRadarInputException.class);
        assertThatThrownBy(() -> structure.split(aliceA, mfa.getId(), "X", List.of(), null))
                .isInstanceOf(InvalidRadarInputException.class);
        assertThat(subjects.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("ISOLATION : fusionner vers un sujet d'un autre poste → introuvable, rien ne bouge")
    void mergeAcrossHostsIsNotFound() {
        RadarSubject elsewhere = registry.createSubject(aliceB, "MFA CAGIP", null, ids(proof(aliceB, "CAGIP")));

        assertThatThrownBy(() -> structure.merge(aliceA, doubleAuth.getId(), elsewhere.getId()))
                .isInstanceOf(RadarNotFoundException.class);
        assertThat(subjects.findById(doubleAuth.getId()).orElseThrow().getMergedIntoId()).isNull();
        assertThat(corrections.findAll()).isEmpty();
    }

    @Test
    void userAliasesAreAddedAndRemoved() {
        var alias = structure.addUserAlias(aliceA, mfa.getId(), "Chantier Okta");
        assertThatThrownBy(() -> structure.addUserAlias(aliceA, mfa.getId(), "chantier okta"))
                .isInstanceOf(InvalidRadarInputException.class);
        assertThatThrownBy(() -> structure.removeAlias(aliceB, mfa.getId(), alias.id()))
                .isInstanceOf(RadarNotFoundException.class);

        structure.removeAlias(aliceA, mfa.getId(), alias.id());

        assertThat(read.subject(aliceA, mfa.getId()).aliases()).isEmpty();
    }

    @Test
    @DisplayName("ALIAS (SF-99-06) : l'ajout est journalisé et s'annule depuis le journal")
    void addedAliasIsJournaledAndUndone() {
        var alias = structure.addUserAlias(aliceA, mfa.getId(), "Chantier Okta");

        CorrectionView added = correctionService.journal(aliceA, mfa.getId()).get(0);
        assertThat(added.action()).isEqualTo(RadarCorrectionAction.ADD_ALIAS);
        assertThat(added.after().path("aliasId").asText()).isEqualTo(alias.id().toString());
        assertThat(added.after().path("alias").asText()).isEqualTo("Chantier Okta");

        correctionService.undo(aliceA, added.id());

        assertThat(read.subject(aliceA, mfa.getId()).aliases()).isEmpty();
        assertThat(correctionService.journal(aliceA, mfa.getId()).get(0).undoneAt()).isNotNull();
    }

    @Test
    @DisplayName("ALIAS (SF-99-06) : un retrait se recrée à l'identique, consigne comprise")
    void removedHintIsRecreatedOnUndo() {
        RadarEvidence okta = proof(aliceA, "Okta signé.");
        registry.attachEvidence(aliceA, mfa.getId(), ids(okta));
        structure.split(aliceA, mfa.getId(), "Contrat Okta", ids(okta), null);
        var hint = read.subject(aliceA, mfa.getId()).aliases().get(0);
        assertThat(hint.rejected()).isTrue();

        structure.removeAlias(aliceA, mfa.getId(), hint.id());
        CorrectionView removed = correctionService.journal(aliceA, mfa.getId()).get(0);
        assertThat(removed.action()).isEqualTo(RadarCorrectionAction.REMOVE_ALIAS);
        assertThat(removed.before().path("alias").asText()).isEqualTo("Contrat Okta");
        assertThat(read.subject(aliceA, mfa.getId()).aliases()).isEmpty();

        correctionService.undo(aliceA, removed.id());

        assertThat(read.subject(aliceA, mfa.getId()).aliases()).singleElement().satisfies(a -> {
            assertThat(a.alias()).isEqualTo("Contrat Okta");
            assertThat(a.rejected()).isTrue();
            assertThat(a.origin()).isEqualTo(RadarAliasOrigin.SPLIT);
        });
    }

    @Test
    @DisplayName("ALIAS (SF-99-06) : annulations impossibles dites (déjà retiré, nom de nouveau connu, autre poste)")
    void aliasUndoConflicts() {
        var alias = structure.addUserAlias(aliceA, mfa.getId(), "Chantier Okta");
        UUID addedId = correctionService.journal(aliceA, mfa.getId()).get(0).id();
        structure.removeAlias(aliceA, mfa.getId(), alias.id());
        UUID removedId = correctionService.journal(aliceA, mfa.getId()).get(0).id();

        assertThatThrownBy(() -> correctionService.undo(aliceA, addedId))
                .isInstanceOf(RadarCorrectionConflictException.class);

        structure.addUserAlias(aliceA, mfa.getId(), "chantier OKTA");
        assertThatThrownBy(() -> correctionService.undo(aliceA, removedId))
                .isInstanceOf(RadarCorrectionConflictException.class);

        // Isolation : le poste B d'Alice ne voit pas la correction du poste A.
        assertThatThrownBy(() -> correctionService.undo(aliceB, removedId))
                .isInstanceOf(RadarNotFoundException.class);
        assertThat(read.subject(aliceA, mfa.getId()).aliases()).extracting(v -> v.alias())
                .containsExactly("chantier OKTA");
    }
}
