package fr.claudegateway.vigie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import fr.claudegateway.runner.RunnerLiveness;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostService;
import fr.claudegateway.vigie.dto.VigieReadinessItem;
import fr.claudegateway.vigie.dto.VigieReadinessResponse;

/** F-122 / SF-122-02 — l'agrégation des quatre vérifications et la décision « on peut démarrer ». */
class VigieReadinessServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    private final RunnerHostService hostService = mock(RunnerHostService.class);
    private final RunnerLiveness liveness = mock(RunnerLiveness.class);
    private final VigieReadinessStore store = new VigieReadinessStore(Duration.ofMinutes(2));
    private final VigieReadinessService service =
            new VigieReadinessService(hostService, liveness, store);

    private void owned() {
        when(hostService.requireOwned(userId, hostId))
                .thenReturn(RunnerHost.builder().userId(userId).name("Poste").build());
    }

    private static Function<String, String> statuses(VigieReadinessResponse response) {
        Map<String, String> byCheck = response.checks().stream()
                .collect(java.util.stream.Collectors.toMap(VigieReadinessItem::check,
                        VigieReadinessItem::status));
        return byCheck::get;
    }

    @Test
    void runner_deconnecte_sans_instantane_tout_bloque() {
        owned();
        when(liveness.isAlive(userId, hostId)).thenReturn(false);

        VigieReadinessResponse response = service.readiness(userId, hostId);

        Function<String, String> status = statuses(response);
        assertThat(status.apply("RUNNER_CONNECTED")).isEqualTo("KO");
        assertThat(status.apply("CHROME_REACHABLE")).isEqualTo("PENDING");
        assertThat(status.apply("TEAMS_CONNECTED")).isEqualTo("PENDING");
        assertThat(status.apply("TEAMS_READ_TEST")).isEqualTo("PENDING");
        assertThat(response.canStart()).isFalse();
    }

    @Test
    void runner_connecte_sans_instantane_reste_en_attente() {
        owned();
        when(liveness.isAlive(userId, hostId)).thenReturn(true);

        VigieReadinessResponse response = service.readiness(userId, hostId);

        Function<String, String> status = statuses(response);
        assertThat(status.apply("RUNNER_CONNECTED")).isEqualTo("OK");
        assertThat(status.apply("CHROME_REACHABLE")).isEqualTo("PENDING");
        assertThat(response.canStart()).isFalse();
    }

    @Test
    void tout_au_vert_debloque_le_demarrage() {
        owned();
        when(liveness.isAlive(userId, hostId)).thenReturn(true);
        service.report(hostId, new VigieReadinessSnapshot(true, true, false, true, "OK"));

        VigieReadinessResponse response = service.readiness(userId, hostId);

        Function<String, String> status = statuses(response);
        assertThat(status.apply("RUNNER_CONNECTED")).isEqualTo("OK");
        assertThat(status.apply("CHROME_REACHABLE")).isEqualTo("OK");
        assertThat(status.apply("TEAMS_CONNECTED")).isEqualTo("OK");
        assertThat(status.apply("TEAMS_READ_TEST")).isEqualTo("OK");
        assertThat(response.canStart()).isTrue();
        assertThat(response.teamsSignInRequired()).isFalse();
    }

    @Test
    void teams_non_connecte_propage_le_besoin_de_login_et_bloque() {
        owned();
        when(liveness.isAlive(userId, hostId)).thenReturn(true);
        service.report(hostId, new VigieReadinessSnapshot(true, false, true, false, null));

        VigieReadinessResponse response = service.readiness(userId, hostId);

        Function<String, String> status = statuses(response);
        assertThat(status.apply("CHROME_REACHABLE")).isEqualTo("OK");
        assertThat(status.apply("TEAMS_CONNECTED")).isEqualTo("KO");
        assertThat(status.apply("TEAMS_READ_TEST")).isEqualTo("KO");
        assertThat(response.teamsSignInRequired()).isTrue();
        assertThat(response.canStart()).isFalse();
    }

    @Test
    void un_instantane_perime_retombe_en_attente() {
        owned();
        when(liveness.isAlive(userId, hostId)).thenReturn(true);
        // Rapporté il y a 5 minutes, au-delà du stale-after de 2 minutes.
        store.putAt(hostId, new VigieReadinessSnapshot(true, true, false, true, null),
                Instant.now().minus(Duration.ofMinutes(5)));

        VigieReadinessResponse response = service.readiness(userId, hostId);

        Function<String, String> status = statuses(response);
        assertThat(status.apply("CHROME_REACHABLE")).isEqualTo("PENDING");
        assertThat(status.apply("TEAMS_CONNECTED")).isEqualTo("PENDING");
        assertThat(response.canStart()).isFalse();
    }
}
