package fr.claudegateway.runner.rupture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Ce que les ruptures disent</b> (F-161 / SF-161-03).
 *
 * <p>Le rapport ne conseille rien : le cadrage F-161 §6 refuse de deviner la cause des
 * déconnexions, et en tirer un conseil reviendrait à la deviner quand même, avec l'autorité d'un
 * écran en plus. Ces tests vérifient donc des <b>comptes</b>, pas des jugements.</p>
 */
class RunnerDisconnectServiceTest {

    private final RunnerDisconnectRepository repository = mock(RunnerDisconnectRepository.class);
    private final RunnerDisconnectService service = new RunnerDisconnectService(repository);

    private final UUID userId = UUID.randomUUID();
    private final UUID hostA = UUID.randomUUID();
    private final UUID hostB = UUID.randomUUID();
    private final OffsetDateTime from = OffsetDateTime.parse("2026-09-19T00:00:00+02:00");
    private final OffsetDateTime to = OffsetDateTime.parse("2026-09-26T00:00:00+02:00");

    private RunnerDisconnect rupture(UUID host, RunnerDisconnectCause cause, String at,
                                     Long silentMs, int inFlight) {
        return RunnerDisconnect.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .hostId(host)
                .cause(cause)
                .transport(RunnerTransport.POLLING)
                .silentMs(silentMs)
                .callsInFlight(inFlight)
                .createdAt(OffsetDateTime.parse(at))
                .build();
    }

    private void given(RunnerDisconnect... ruptures) {
        when(repository.findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(userId, from, to))
                .thenReturn(List.of(ruptures));
    }

    @Test
    @DisplayName("les ruptures SUBIES excluent l'arrêt propre et le canal remplacé")
    void onlyUnwantedBreaksCount() {
        given(
                rupture(hostA, RunnerDisconnectCause.ARRET_PROPRE, "2026-09-20T10:00:00+02:00", 100L, 0),
                rupture(hostA, RunnerDisconnectCause.REMPLACE, "2026-09-20T11:00:00+02:00", 100L, 0),
                rupture(hostA, RunnerDisconnectCause.INACTIVITE, "2026-09-20T12:00:00+02:00", 95_000L, 2),
                rupture(hostB, RunnerDisconnectCause.SOCKET_MUETTE, "2026-09-21T23:00:00+02:00", 120_000L, 0));

        RunnerDisconnectReport report = service.report(userId, from, to);

        assertThat(report.total()).isEqualTo(4);
        assertThat(report.subies())
                .as("un poste qui raccroche ou qui REVIENT n'a pas décroché")
                .isEqualTo(2);
        assertThat(report.avecAppels())
                .as("la seule rupture qui a coûté quelque chose")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("TOUTES les causes sont rendues, y compris à zéro : « aucune » est une information")
    void everyCauseIsListedEvenAtZero() {
        given(rupture(hostA, RunnerDisconnectCause.INACTIVITE, "2026-09-20T12:00:00+02:00", 95_000L, 0));

        RunnerDisconnectReport report = service.report(userId, from, to);

        assertThat(report.parCause()).hasSize(RunnerDisconnectCause.values().length);
        assertThat(report.parCause()).anySatisfy(line -> {
            assertThat(line.cause()).isEqualTo("SOCKET_MUETTE");
            assertThat(line.total()).isZero();
        });
        // Une ligne absente se lirait comme une mesure manquante, pas comme un zéro.
        assertThat(report.parCause()).extracting(RunnerDisconnectReport.CauseLine::cause)
                .contains("ARRET_PROPRE", "INACTIVITE", "REMPLACE", "SOCKET_FERMEE", "SOCKET_MUETTE");
    }

    @Test
    @DisplayName("par poste : le plus touché d'abord, avec la MÉDIANE du silence")
    void hostsAreRankedByUnwantedBreaks() {
        given(
                rupture(hostA, RunnerDisconnectCause.ARRET_PROPRE, "2026-09-20T10:00:00+02:00", 10L, 0),
                rupture(hostB, RunnerDisconnectCause.INACTIVITE, "2026-09-20T12:00:00+02:00", 91_000L, 0),
                rupture(hostB, RunnerDisconnectCause.SOCKET_MUETTE, "2026-09-20T13:00:00+02:00", 95_000L, 0),
                // Une socket oubliée six heures : une MOYENNE serait rendue illisible par elle.
                rupture(hostB, RunnerDisconnectCause.SOCKET_MUETTE, "2026-09-20T14:00:00+02:00", 21_600_000L, 0));

        RunnerDisconnectReport report = service.report(userId, from, to);

        assertThat(report.parPoste()).hasSize(2);
        assertThat(report.parPoste().get(0).hostId()).isEqualTo(hostB.toString());
        assertThat(report.parPoste().get(0).subies()).isEqualTo(3);
        assertThat(report.parPoste().get(0).medianSilentMs())
                .as("la médiane tient ; une moyenne aurait dit 7 h et n'aurait rien appris")
                .isEqualTo(95_000L);
    }

    @Test
    @DisplayName("par heure locale : c'est là que la veille du poste se verrait")
    void breaksAreGroupedByLocalHour() {
        given(
                rupture(hostA, RunnerDisconnectCause.SOCKET_MUETTE, "2026-09-20T23:10:00+02:00", 95_000L, 0),
                rupture(hostA, RunnerDisconnectCause.SOCKET_MUETTE, "2026-09-21T23:40:00+02:00", 95_000L, 0));

        RunnerDisconnectReport report = service.report(userId, from, to);

        assertThat(report.parHeure()).hasSize(24);
        int totalDeNuit = report.parHeure().stream()
                .filter(line -> line.total() > 0)
                .mapToInt(RunnerDisconnectReport.HourLine::total)
                .sum();
        assertThat(totalDeNuit).isEqualTo(2);
    }

    @Test
    @DisplayName("ISOLATION : la lecture est filtrée par user_id, jamais globale")
    void theReadIsScopedToTheAccount() {
        given();

        service.report(userId, from, to);

        // Sans ce filtre, l'écran d'un administrateur rendrait les postes d'autres clients.
        verify(repository).findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                eq(userId), any(), any());
    }

    @Test
    @DisplayName("aucune rupture : un rapport vide, pas une absence de rapport")
    void noBreaksIsStillAReport() {
        given();

        RunnerDisconnectReport report = service.report(userId, from, to);

        assertThat(report.total()).isZero();
        assertThat(report.parCause()).hasSize(RunnerDisconnectCause.values().length);
        assertThat(report.parPoste()).isEmpty();
        assertThat(report.parHeure()).hasSize(24);
    }
}
