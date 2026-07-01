package fr.claudegateway.rag;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests unitaires du worker d'auto-indexation (F-06 / SF-06-02) : délégation au service et
 * robustesse (une exception du service ne remonte pas / n'arrête pas le planificateur).
 */
@ExtendWith(MockitoExtension.class)
class IngestionWorkerTest {

    @Mock
    private IngestionService ingestionService;

    @InjectMocks
    private IngestionWorker worker;

    @Test
    void delegatesToService() {
        when(ingestionService.ingestPending()).thenReturn(3);
        worker.run();
        verify(ingestionService, times(1)).ingestPending();
    }

    @Test
    void swallowsUnexpectedExceptions() {
        when(ingestionService.ingestPending()).thenThrow(new RuntimeException("boom"));
        // Le planificateur ne doit jamais s'arrêter : l'exception est capturée par le worker.
        assertThatCode(() -> worker.run()).doesNotThrowAnyException();
        verify(ingestionService).ingestPending();
    }
}
