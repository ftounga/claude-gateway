package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests du journal de consommation par tour (F-61 / SF-61-01) : ce qui est rangé, ce qui ne l'est
 * pas, et surtout le fait qu'un relevé raté ne peut pas faire échouer un tour déjà payé.
 */
@ExtendWith(MockitoExtension.class)
class UsageLedgerServiceTest {

    @Mock
    private UsageTurnWriter writer;

    private UsageLedgerService service;

    private final UUID alice = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID host = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new UsageLedgerService(writer);
    }

    @Test
    void recordsTurnWithProjectAndHost() {
        service.recordTurn(alice, project, host, 1_000L, 200L);

        verify(writer).write(alice, project, host, 1_000L, 200L);
    }

    @Test
    void recordsTurnWithoutProjectForGatewayCalls() {
        // /chat et /ask consomment aussi : les ignorer empêcherait la somme des clients de se
        // réconcilier avec le total de la période.
        service.recordTurn(alice, null, null, 500L, 100L);

        verify(writer).write(alice, null, null, 500L, 100L);
    }

    @Test
    void writesNothingWhenTurnConsumedNothing() {
        service.recordTurn(alice, project, host, 0L, 0L);

        verifyNoInteractions(writer);
    }

    @Test
    void negativeValuesAreClampedToZero() {
        service.recordTurn(alice, project, host, -10L, 300L);

        verify(writer).write(alice, project, host, 0L, 300L);
    }

    @Test
    void writesNothingWithoutUser() {
        service.recordTurn(null, project, host, 100L, 100L);

        verifyNoInteractions(writer);
    }

    @Test
    void writeFailureNeverBreaksTheTurn() {
        // Quand le journal échoue, le fournisseur a déjà été appelé et payé : un relevé perdu est un
        // défaut d'information, un tour en échec serait un défaut de service ET d'argent.
        doThrow(new IllegalStateException("base indisponible"))
                .when(writer).write(any(), any(), any(), anyLong(), anyLong());

        assertThatCode(() -> service.recordTurn(alice, project, host, 1_000L, 200L))
                .doesNotThrowAnyException();
    }

    @Test
    void doesNotWriteWhenOnlyOutputIsNegative() {
        service.recordTurn(alice, project, host, 0L, -5L);

        verify(writer, never()).write(eq(alice), any(), any(), anyLong(), anyLong());
    }
}
