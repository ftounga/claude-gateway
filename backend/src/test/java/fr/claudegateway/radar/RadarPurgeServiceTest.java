package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fr.claudegateway.radar.RadarRegistry.CommitmentInput;
import fr.claudegateway.radar.RadarRegistry.SummarySentence;
import fr.claudegateway.radar.dto.RadarCorrectionRequests.SubjectCorrectionRequest;
import fr.claudegateway.runner.host.RunnerHostService;

/** F-99 / SF-99-05 — la purge efface tout un périmètre, et rien au-delà. */
class RadarPurgeServiceTest extends RadarIntegrationTestBase {

    @Autowired private RadarPurgeService purgeService;
    @Autowired private RunnerHostService hostService;

    /** Remplit un périmètre de toutes les sortes de lignes du Radar. */
    private RadarSubject fill(RadarScope scope, String name) {
        RadarEvidence p = proof(scope, name + " démarre");
        RadarSubject subject = registry.createSubject(scope, name, null, List.of(p.getId()));
        registry.replaceSummary(scope, subject.getId(), List.of(new SummarySentence(name + ".", List.of(p.getId()))));
        RadarPerson person = registry.upsertPerson(scope, name + "@client.fr", "Paul", null);
        registry.assignRole(scope, subject.getId(), person.getId(), RadarRole.DRIVES, List.of(p.getId()));
        registry.recordCommitment(scope, new CommitmentInput(subject.getId(), RadarCommitmentDirection.ME_TO_OTHER,
                "Faire", null, null, null, null, false, RadarCertainty.CERTAIN, null, List.of(p.getId())));
        registry.addAlias(scope, subject.getId(), name + " bis");
        registry.startSync(scope);
        correctionService.correctSubject(scope, subject.getId(),
                new SubjectCorrectionRequest(RadarCorrectionAction.SET_STATE, null, RadarSubjectState.BLOCKED, null, null));
        return subject;
    }

    private long rows(RadarScope scope) {
        return subjects.findByUserIdAndHostId(scope.userId(), scope.hostId()).size()
                + evidence.findByUserIdAndHostId(scope.userId(), scope.hostId()).size()
                + links.findByUserIdAndHostId(scope.userId(), scope.hostId()).size()
                + commitments.findByUserIdAndHostId(scope.userId(), scope.hostId()).size()
                + roles.findByUserIdAndHostId(scope.userId(), scope.hostId()).size()
                + people.findByUserIdAndHostIdOrderByDisplayNameAsc(scope.userId(), scope.hostId()).size()
                + corrections.findByUserIdAndHostIdOrderByCreatedAtDesc(scope.userId(), scope.hostId()).size()
                + facts.findAll().stream().filter(f -> f.getHostId().equals(scope.hostId())).count()
                + aliases.findAll().stream().filter(a -> a.getHostId().equals(scope.hostId())).count()
                + syncs.findAll().stream().filter(s -> s.getHostId().equals(scope.hostId())).count();
    }

    @Test
    @DisplayName("ISOLATION : purger le poste A efface tout son Radar, pas celui du poste B ni celui de Bob")
    void purgeIsScoped() {
        fill(aliceA, "MFA");
        fill(aliceB, "LDAP");
        fill(bobScope, "VPN");
        long before = rows(aliceB) + rows(bobScope);

        RadarPurge trace = purgeService.purge(aliceA, RadarPurgeReason.USER_REQUEST);

        assertThat(rows(aliceA)).isZero();
        assertThat(rows(aliceB) + rows(bobScope)).isEqualTo(before);
        assertThat(trace.getSubjectsCount()).isEqualTo(1);
        assertThat(trace.getEvidenceCount()).isEqualTo(1);
        assertThat(purgeService.traces(aliceA)).singleElement()
                .satisfies(t -> assertThat(t.reason()).isEqualTo(RadarPurgeReason.USER_REQUEST));
        assertThat(purgeService.traces(aliceB)).isEmpty();
    }

    @Test
    @DisplayName("purger un compte efface tous ses postes et ses traces, pas ceux de Bob")
    void purgeUser() {
        fill(aliceA, "MFA");
        fill(aliceB, "LDAP");
        fill(bobScope, "VPN");
        purgeService.purge(aliceB, RadarPurgeReason.USER_REQUEST);
        long bob = rows(bobScope);

        purgeService.purgeUser(alice.getId());

        assertThat(rows(aliceA) + rows(aliceB)).isZero();
        assertThat(purges.findAll()).isEmpty();
        assertThat(rows(bobScope)).isEqualTo(bob);
    }

    @Test
    @DisplayName("supprimer un poste purge son Radar dans la même transaction")
    void deletingAHostPurgesItsRadar() {
        fill(aliceA, "MFA");
        fill(aliceB, "LDAP");

        hostService.deleteWithCredentials(alice.getId(), aliceA.hostId());

        assertThat(rows(aliceA)).isZero();
        assertThat(rows(aliceB)).isPositive();
        assertThat(purges.findAll()).singleElement()
                .satisfies(p -> assertThat(p.getReason()).isEqualTo(RadarPurgeReason.HOST_DELETED));
    }
}
