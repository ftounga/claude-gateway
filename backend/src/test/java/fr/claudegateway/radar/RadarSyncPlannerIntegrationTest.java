package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fr.claudegateway.radar.sync.RadarHostSettings;
import fr.claudegateway.radar.sync.RadarSyncLauncher;
import fr.claudegateway.radar.sync.RadarSyncPlanner;
import fr.claudegateway.runner.channel.RunnerTarget;

/**
 * F-100 / SF-100-02 — <b>le planificateur</b> : le créneau du soir à l'heure du poste, le rattrapage à la
 * connexion suivante, une seule synchro à la fois, les synchros abandonnées closes.
 */
class RadarSyncPlannerIntegrationTest extends RadarSyncIntegrationTestBase {

    @Autowired private RadarSyncPlanner planner;
    @Autowired private RadarSyncLauncher launcher;

    /** Le 13 septembre 2026 à 22 h 05, heure de Paris. */
    private static final OffsetDateTime AT_2205_PARIS = OffsetDateTime.parse("2026-09-13T20:05:00Z");
    private static final LocalDate YESTERDAY = LocalDate.parse("2026-09-12");
    private static final LocalDate TODAY = LocalDate.parse("2026-09-13");

    private RadarHostSettings settingsOf(RadarScope scope) {
        return hostSettings.findByUserIdAndHostId(scope.userId(), scope.hostId()).orElseThrow();
    }

    @Test
    @DisplayName("F-106 : un client retiré de la Vigie ne se synchronise pas, et son créneau reste à traiter")
    void aHostOutsideTheVigieIsNotSynchronized() {
        enable(aliceA, "22:00", "Europe/Paris", YESTERDAY);
        when(liveness.isAlive(alice.getId(), aliceA.hostId())).thenReturn(true);
        runnerAcceptsSyncs();
        hostSpaces.findByUserIdAndHostId(alice.getId(), aliceA.hostId()).stream()
                .filter(row -> row.getSpace() == fr.claudegateway.runner.host.ClientSpace.VIGIE)
                .forEach(hostSpaces::delete);

        assertThat(planner.runOnce(AT_2205_PARIS)).isZero();

        assertThat(syncs.findAll()).isEmpty();
        assertThat(settingsOf(aliceA).getLastSlotDate()).isEqualTo(YESTERDAY);
        verify(router, never()).call(any(RunnerTarget.class), anyString(), eq(RadarSyncLauncher.COLLECT), any(), anyLong());
    }

    @Test
    @DisplayName("Créneau dû et runner vivant : une synchro SCHEDULED, créneau traité ; rien de plus au passage suivant")
    void scheduledSlot() {
        enable(aliceA, "22:00", "Europe/Paris", YESTERDAY);
        when(liveness.isAlive(alice.getId(), aliceA.hostId())).thenReturn(true);
        runnerAcceptsSyncs();

        assertThat(planner.runOnce(AT_2205_PARIS)).isEqualTo(1);

        RadarSync sync = syncs.findAll().get(0);
        assertThat(sync.getTriggerKind()).isEqualTo(RadarSyncTrigger.SCHEDULED);
        assertThat(sync.getScheduledFor().toInstant().toString()).isEqualTo("2026-09-13T20:00:00Z");
        assertThat(settingsOf(aliceA).getLastSlotDate()).isEqualTo(TODAY);
        assertThat(settingsOf(aliceA).getRunningSyncId()).isEqualTo(sync.getId());

        assertThat(planner.runOnce(AT_2205_PARIS.plusMinutes(1))).isZero();
        verify(router, times(1)).call(any(RunnerTarget.class), anyString(), eq(RadarSyncLauncher.COLLECT), any(), anyLong());
    }

    @Test
    @DisplayName("Avant l'heure, rien ; fuseau respecté : 22 h à New York n'est pas 22 h à Paris")
    void notYetAndTimeZones() {
        enable(aliceA, "22:00", "Europe/Paris", TODAY);
        enable(aliceB, "22:00", "America/New_York", YESTERDAY);
        when(liveness.isAlive(any(), any())).thenReturn(true);
        runnerAcceptsSyncs();

        // 22 h 05 à Paris = 16 h 05 à New York : le créneau new-yorkais du 13 n'est pas encore dû.
        assertThat(planner.runOnce(AT_2205_PARIS)).isZero();
        assertThat(syncs.findAll()).isEmpty();

        // 22 h 01 à New York le 13 = 02 h 01 UTC le 14.
        OffsetDateTime at2201NewYork = LocalDate.parse("2026-09-13").atTime(22, 1)
                .atZone(ZoneId.of("America/New_York")).toOffsetDateTime();
        assertThat(planner.runOnce(at2201NewYork)).isEqualTo(1);
        assertThat(syncs.findAll()).singleElement().satisfies(s -> assertThat(s.getHostId()).isEqualTo(aliceB.hostId()));
    }

    @Test
    @DisplayName("Portable fermé à l'heure : créneau noté manqué, puis rattrapé (CATCH_UP) dès que le runner bat")
    void missedThenCaughtUp() {
        enable(aliceA, "22:00", "Europe/Paris", YESTERDAY);
        when(liveness.isAlive(alice.getId(), aliceA.hostId())).thenReturn(false);
        runnerAcceptsSyncs();

        assertThat(planner.runOnce(AT_2205_PARIS)).isZero();
        assertThat(settingsOf(aliceA).getMissedSlotAt().toInstant().toString()).isEqualTo("2026-09-13T20:00:00Z");
        assertThat(settingsOf(aliceA).getLastSlotDate()).isEqualTo(YESTERDAY);
        verify(router, never()).call(any(RunnerTarget.class), anyString(), anyString(), any(), anyLong());

        when(liveness.isAlive(alice.getId(), aliceA.hostId())).thenReturn(true);
        OffsetDateTime nextMorning = OffsetDateTime.parse("2026-09-14T06:12:00Z"); // 8 h 12 à Paris
        assertThat(planner.runOnce(nextMorning)).isEqualTo(1);

        RadarSync sync = syncs.findAll().get(0);
        assertThat(sync.getTriggerKind()).isEqualTo(RadarSyncTrigger.CATCH_UP);
        assertThat(sync.getScheduledFor().toInstant().toString()).isEqualTo("2026-09-13T20:00:00Z");
        assertThat(settingsOf(aliceA).getMissedSlotAt()).isNull();
        assertThat(settingsOf(aliceA).getLastSlotDate()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("Une synchro tient déjà le poste : le créneau est traité sans lancement ; sans droit, rien")
    void alreadyRunningOrNoAccess() {
        enable(aliceA, "22:00", "Europe/Paris", YESTERDAY);
        when(liveness.isAlive(any(), any())).thenReturn(true);
        runnerAcceptsSyncs();
        RadarSync manual = launcher.start(aliceA, RadarSyncTrigger.MANUAL, null);
        // Vivante au moment du passage ET pour le lanceur, qui juge l'abandon à l'horloge réelle :
        // un battement figé au 13 septembre deviendrait « abandonné » dès le lendemain du jour d'écriture.
        OffsetDateTime passage = AT_2205_PARIS.minusMinutes(1);
        OffsetDateTime realNow = OffsetDateTime.now();
        manual.setHeartbeatAt(realNow.isAfter(passage) ? realNow : passage);
        syncs.save(manual);

        assertThat(planner.runOnce(AT_2205_PARIS)).isZero();
        assertThat(syncs.findAll()).hasSize(1);
        assertThat(settingsOf(aliceA).getLastSlotDate()).isEqualTo(TODAY);

        enable(bobScope, "22:00", "Europe/Paris", YESTERDAY);
        when(teamsAccess.hasAccess(bob.getId())).thenReturn(false);
        assertThat(planner.runOnce(AT_2205_PARIS)).isZero();
        assertThat(settingsOf(bobScope).getLastSlotDate()).isEqualTo(YESTERDAY);
    }

    @Test
    @DisplayName("Une synchro sans battement depuis 15 min est close FAILED (interrompue) et libère le poste")
    void staleSyncIsClosed() {
        // Créneau déjà traité, quelle que soit la date du jour où le test tourne.
        enable(aliceA, "22:00", "Europe/Paris", LocalDate.now(ZoneId.of("Europe/Paris")).plusDays(1));
        when(liveness.isAlive(any(), any())).thenReturn(true);
        runnerAcceptsSyncs();
        RadarSync sync = launcher.start(aliceA, RadarSyncTrigger.MANUAL, null);

        planner.runOnce(OffsetDateTime.now().plusMinutes(5));
        assertThat(syncs.findById(sync.getId()).orElseThrow().getStatus()).isEqualTo(RadarSyncStatus.RUNNING);

        planner.runOnce(OffsetDateTime.now().plusMinutes(16));
        RadarSync closed = syncs.findById(sync.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(RadarSyncStatus.FAILED);
        assertThat(closed.getCoverage()).contains("INTERRUPTED");
        assertThat(settingsOf(aliceA).getRunningSyncId()).isNull();
    }

}
