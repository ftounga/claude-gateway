package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import fr.claudegateway.radar.RadarSyncRunningException;
import fr.claudegateway.radar.sync.RadarSyncLauncher;
import fr.claudegateway.radar.sync.RadarSyncPlanner;

/**
 * F-100 / SF-100-08 — <b>le verrou sur PostgreSQL réel</b>. Le verrou « une synchro à la fois par poste »
 * repose sur une mise à jour conditionnelle ({@code UPDATE … WHERE running_sync_id IS NULL}) éprouvée
 * jusqu'ici seulement en séquentiel sur H2. Ce test la met sous une vraie contention, sur un vrai PostgreSQL
 * (image {@code pgvector}, car la migration {@code 002} exige l'extension {@code vector}), avec plusieurs
 * planificateurs concurrents : <b>jamais deux synchros du même poste</b>.
 *
 * <p>Ignoré proprement quand aucun démon Docker n'est disponible ({@code disabledWithoutDocker = true}) : la
 * suite reste verte, le test s'exécute là où Docker existe (poste de dev, machine d'intégration).</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class RadarSyncPostgresConcurrencyIntegrationTest extends RadarSyncIntegrationTestBase {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private RadarSyncPlanner planner;
    @Autowired private RadarSyncLauncher launcher;

    /** Le 13 septembre 2026 à 22 h 05, heure de Paris — un créneau dû. */
    private static final OffsetDateTime AT_2205_PARIS = OffsetDateTime.parse("2026-09-13T20:05:00Z");

    @Test
    @DisplayName("Deux planificateurs concurrents (PostgreSQL réel) : une seule synchro RUNNING pour le poste")
    void concurrentPlannersStartExactlyOneSync() throws Exception {
        enable(aliceA, "22:00", "Europe/Paris", LocalDate.parse("2026-09-12"));
        when(liveness.isAlive(any(), any())).thenReturn(true);
        runnerAcceptsSyncs();

        int started = raceOn(threads -> planner.runOnce(AT_2205_PARIS), 8);

        assertThat(started).isEqualTo(1);
        assertThat(syncs.findAll()).filteredOn(s -> s.getStatus() == RadarSyncStatus.RUNNING).hasSize(1);
        assertThat(syncs.findAll().stream().filter(s -> s.getHostId().equals(aliceA.hostId())).count())
                .isEqualTo(1L); // les perdants annulent leur synchro par rollback : aucun orphelin
        assertThat(hostSettings.findByUserIdAndHostId(alice.getId(), aliceA.hostId()).orElseThrow()
                .getRunningSyncId()).isNotNull();
    }

    @Test
    @DisplayName("Deux lanceurs concurrents (PostgreSQL réel) : un seul succès, les autres RadarSyncRunningException")
    void concurrentLaunchersClaimOnce() throws Exception {
        enable(aliceA, "22:00", "Europe/Paris", null);
        when(liveness.isAlive(any(), any())).thenReturn(true);
        runnerAcceptsSyncs();

        AtomicInteger conflicts = new AtomicInteger();
        int started = raceOn(threads -> {
            try {
                launcher.start(aliceA, RadarSyncTrigger.MANUAL, null);
                return 1;
            } catch (RadarSyncRunningException e) {
                conflicts.incrementAndGet();
                return 0;
            }
        }, 8);

        assertThat(started).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(7);
        assertThat(syncs.findAll()).filteredOn(s -> s.getStatus() == RadarSyncStatus.RUNNING).hasSize(1);
    }

    /** Lance {@code count} appels en même temps (barrière) et somme leurs retours. */
    private int raceOn(java.util.function.IntFunction<Integer> call, int count) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            CyclicBarrier barrier = new CyclicBarrier(count);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return call.apply(index);
                }));
            }
            int total = 0;
            for (Future<Integer> future : futures) {
                total += future.get(30, TimeUnit.SECONDS);
            }
            return total;
        } finally {
            pool.shutdownNow();
        }
    }
}
