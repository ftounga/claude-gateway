package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fr.claudegateway.radar.dto.RadarCorrectionRequests.SubjectCorrectionRequest;

/** F-99 / SF-99-04 — le silence met en sommeil, il ne clôt jamais. */
class RadarDormancyServiceTest extends RadarIntegrationTestBase {

    @Autowired private RadarDormancyService dormancy;
    @Autowired private RadarClosureService closure;

    @Test
    @DisplayName("22 jours de silence : en sommeil ; 20 jours : inchangé ; jamais clos")
    void silenceMakesDormantNeverClosed() {
        OffsetDateTime now = OffsetDateTime.now();
        RadarSubject silent = registry.createSubject(aliceA, "Silencieux", RadarSubjectState.WAITING,
                ids(proof(aliceA, "vieux", now.minusDays(22))));
        RadarSubject recent = registry.createSubject(aliceA, "Récent", null, ids(proof(aliceA, "récent", now.minusDays(20))));

        int count = dormancy.sweep(now);

        assertThat(count).isEqualTo(1);
        RadarSubject slept = subjects.findById(silent.getId()).orElseThrow();
        assertThat(slept.getState()).isEqualTo(RadarSubjectState.DORMANT);
        assertThat(slept.getPreviousState()).isEqualTo(RadarSubjectState.WAITING);
        assertThat(slept.getDormantSince()).isNotNull();
        assertThat(slept.getClosedAt()).isNull();
        assertThat(subjects.findById(recent.getId()).orElseThrow().getState()).isEqualTo(RadarSubjectState.NEW);
        assertThat(dormancy.sweep(now)).as("idempotent").isZero();
    }

    @Test
    @DisplayName("clos et « clos ? » ne sont jamais touchés par le silence")
    void closedAndProposedAreSpared() {
        OffsetDateTime now = OffsetDateTime.now();
        RadarSubject closed = registry.createSubject(aliceA, "Clos", null, ids(proof(aliceA, "a", now.minusDays(60))));
        RadarSubject proposed = registry.createSubject(aliceA, "Proposé", null, ids(proof(aliceA, "b", now.minusDays(60))));
        closure.close(aliceA, closed.getId());
        registry.proposeClosure(aliceA, proposed.getId(), ids(proof(aliceA, "on peut fermer", now.minusDays(59))));

        assertThat(dormancy.sweep(now)).isZero();
        assertThat(subjects.findById(closed.getId()).orElseThrow().getState()).isEqualTo(RadarSubjectState.CLOSED);
        assertThat(subjects.findById(proposed.getId()).orElseThrow().getState())
                .isEqualTo(RadarSubjectState.CLOSE_PROPOSED);
    }

    @Test
    @DisplayName("une activité plus récente ramène le sujet à son état d'avant, souverain compris")
    void newActivityWakesFromDormancy() {
        OffsetDateTime now = OffsetDateTime.now();
        RadarSubject subject = registry.createSubject(aliceA, "MFA", null, ids(proof(aliceA, "a", now.minusDays(30))));
        correctionService.correctSubject(aliceA, subject.getId(),
                new SubjectCorrectionRequest(RadarCorrectionAction.SET_STATE, null, RadarSubjectState.BLOCKED, null, null));
        dormancy.sweep(now);
        assertThat(subjects.findById(subject.getId()).orElseThrow().getState()).isEqualTo(RadarSubjectState.DORMANT);

        registry.attachEvidence(aliceA, subject.getId(), ids(proof(aliceA, "ça repart", now.minusHours(1))));

        RadarSubject awake = subjects.findById(subject.getId()).orElseThrow();
        assertThat(awake.getState()).isEqualTo(RadarSubjectState.BLOCKED);
        assertThat(awake.getDormantSince()).isNull();
        assertThat(awake.isStateSovereign()).isTrue();
    }

    @Test
    @DisplayName("ISOLATION : le balayage d'un poste ne touche que ses propres sujets")
    void sweepIsScoped() {
        OffsetDateTime now = OffsetDateTime.now();
        RadarSubject silentB = registry.createSubject(aliceB, "Silencieux B", null, ids(proof(aliceB, "b", now.minusDays(40))));
        RadarSubject silentA = registry.createSubject(aliceA, "Silencieux A", null, ids(proof(aliceA, "a", now.minusDays(40))));

        assertThat(dormancy.sweep(aliceB, now)).isEqualTo(1);

        assertThat(subjects.findById(silentB.getId()).orElseThrow().getState()).isEqualTo(RadarSubjectState.DORMANT);
        assertThat(subjects.findById(silentA.getId()).orElseThrow().getState()).isEqualTo(RadarSubjectState.NEW);
    }
}
