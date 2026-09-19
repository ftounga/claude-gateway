package fr.claudegateway.teams.meeting;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Le worker de rétention (F-128 / SF-128-07) : délègue au service, et n'explose jamais le planificateur. */
@ExtendWith(MockitoExtension.class)
class MeetingRetentionWorkerTest {

    @Mock private MeetingRetentionService retentionService;

    @Test
    void deleguePurgeExpiredAuService() {
        when(retentionService.purgeExpired(any())).thenReturn(2);
        new MeetingRetentionWorker(retentionService).purge();
        verify(retentionService).purgeExpired(any());
    }

    @Test
    void uneErreurNeTuePasLePlanificateur() {
        when(retentionService.purgeExpired(any())).thenThrow(new RuntimeException("boom"));
        assertThatCode(() -> new MeetingRetentionWorker(retentionService).purge())
                .doesNotThrowAnyException();
    }
}
