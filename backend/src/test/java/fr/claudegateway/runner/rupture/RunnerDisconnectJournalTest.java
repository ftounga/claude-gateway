package fr.claudegateway.runner.rupture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * <b>L'écriture du journal</b> (F-161 / SF-161-03).
 *
 * <p>Ce que ces tests tiennent : <b>un journal perdu vaut mieux qu'un canal bloqué</b>. Si cette
 * garantie tombait, une panne de base empêcherait les sockets de se fermer — soit exactement le
 * défaut qu'on cherche à mesurer, aggravé par l'outil censé le mesurer.</p>
 */
class RunnerDisconnectJournalTest {

    private final RunnerDisconnectRepository repository = mock(RunnerDisconnectRepository.class);
    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    private RunnerDisconnectJournal journal(boolean enabled) {
        return new RunnerDisconnectJournal(repository, enabled);
    }

    @Test
    @DisplayName("une rupture est consignée avec sa cause, son transport et ses durées")
    void aBreakIsRecorded() {
        journal(true).record(userId, hostId, RunnerDisconnectCause.SOCKET_MUETTE,
                RunnerTransport.WEBSOCKET, Instant.now().minusSeconds(600),
                Instant.now().minusSeconds(120), "SESSION_NOT_RELIABLE", 3);

        ArgumentCaptor<RunnerDisconnect> saved = ArgumentCaptor.forClass(RunnerDisconnect.class);
        verify(repository).save(saved.capture());
        RunnerDisconnect row = saved.getValue();
        assertThat(row.getUserId()).isEqualTo(userId);
        assertThat(row.getHostId()).isEqualTo(hostId);
        assertThat(row.getCause()).isEqualTo(RunnerDisconnectCause.SOCKET_MUETTE);
        assertThat(row.getTransport()).isEqualTo(RunnerTransport.WEBSOCKET);
        assertThat(row.getLivedMs()).isBetween(595_000L, 615_000L);
        assertThat(row.getSilentMs()).isBetween(115_000L, 135_000L);
        assertThat(row.getCloseStatus()).isEqualTo("SESSION_NOT_RELIABLE");
        assertThat(row.getCallsInFlight())
                .as("les appels en vol sont le seul chiffre qui dise si la rupture a coûté")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("UN DÉPÔT QUI LÈVE NE FAIT RIEN ÉCHOUER — un canal doit toujours pouvoir se fermer")
    void aFailingRepositoryNeverPropagates() {
        when(repository.save(any())).thenThrow(new IllegalStateException("base injoignable"));

        assertThatCode(() -> journal(true).record(userId, hostId,
                RunnerDisconnectCause.INACTIVITE, RunnerTransport.POLLING,
                Instant.now(), Instant.now(), null, 0))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("débranché : aucune écriture, comportement d'avant")
    void disabledWritesNothing() {
        journal(false).record(userId, hostId, RunnerDisconnectCause.ARRET_PROPRE,
                RunnerTransport.POLLING, Instant.now(), Instant.now(), null, 0);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("une identité incomplète n'écrit rien plutôt qu'une ligne inexploitable")
    void incompleteIdentityWritesNothing() {
        RunnerDisconnectJournal journal = journal(true);

        journal.record(null, hostId, RunnerDisconnectCause.INACTIVITE, RunnerTransport.POLLING,
                null, null, null, 0);
        journal.record(userId, null, RunnerDisconnectCause.INACTIVITE, RunnerTransport.POLLING,
                null, null, null, 0);
        journal.record(userId, hostId, null, RunnerTransport.POLLING, null, null, null, 0);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("des instants inconnus donnent des durées nulles, jamais une ligne refusée")
    void unknownInstantsStillRecord() {
        journal(true).record(userId, hostId, RunnerDisconnectCause.ARRET_PROPRE,
                RunnerTransport.POLLING, null, null, null, 0);

        ArgumentCaptor<RunnerDisconnect> saved = ArgumentCaptor.forClass(RunnerDisconnect.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getLivedMs()).isNull();
        assertThat(saved.getValue().getSilentMs()).isNull();
    }

    @Test
    @DisplayName("un CloseStatus bavard est tronqué : la base n'a pas à porter son roman")
    void aVerboseStatusIsTrimmed() {
        journal(true).record(userId, hostId, RunnerDisconnectCause.SOCKET_FERMEE,
                RunnerTransport.WEBSOCKET, Instant.now(), Instant.now(), "x".repeat(500), 0);

        ArgumentCaptor<RunnerDisconnect> saved = ArgumentCaptor.forClass(RunnerDisconnect.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getCloseStatus()).hasSize(120);
    }
}
