package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests du journal de consommation par tour (F-61 / SF-61-01) : ce qui est rangé, ce qui ne l'est
 * pas, et surtout le fait qu'un relevé raté ne peut pas faire échouer un tour déjà payé.
 *
 * <p>Depuis F-133 / SF-133-01, le journal reçoit les <b>quatre</b> natures de tokens et le coût
 * réel du tour, là où il ne recevait que deux totaux.</p>
 */
@ExtendWith(MockitoExtension.class)
class UsageLedgerServiceTest {

    @Mock
    private UsageTurnWriter writer;

    private UsageLedgerService service;

    private final UUID alice = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID host = UUID.randomUUID();

    private static final TurnCost COST = new TurnCost(new BigDecimal("0.275000"),
            TurnCost.Source.CALCULATED, "claude-opus-5", "2026-09-20", false);

    @BeforeEach
    void setUp() {
        service = new UsageLedgerService(writer);
    }

    @Test
    void recordsTurnWithProjectAndHost() {
        TurnTokens tokens = TurnTokens.of(1_000L, 200L);

        service.recordTurn(alice, project, host, tokens, COST);

        verify(writer).write(alice, project, host, tokens, COST);
    }

    @Test
    void recordsTurnWithoutProjectForGatewayCalls() {
        // /chat et /ask consomment aussi : les ignorer empêcherait la somme des clients de se
        // réconcilier avec le total de la période.
        TurnTokens tokens = TurnTokens.of(500L, 100L);

        service.recordTurn(alice, null, null, tokens, COST);

        verify(writer).write(alice, null, null, tokens, COST);
    }

    @Test
    void carriesTheFourNaturesDownToTheWriter() {
        // C'est l'objet de SF-133-01 : le cache ne s'arrête plus à mi-chemin. Sans lui, la dépense
        // d'un tour agentique est surestimée d'un ordre de grandeur.
        TurnTokens tokens = new TurnTokens(10_000L, 5_000L, 40_000L, 8_000L);

        service.recordTurn(alice, project, host, tokens, COST);

        verify(writer).write(alice, project, host, tokens, COST);
    }

    @Test
    void writesNothingWhenTurnConsumedNothing() {
        service.recordTurn(alice, project, host, TurnTokens.of(0L, 0L), COST);

        verifyNoInteractions(writer);
    }

    @Test
    void negativeValuesAreClampedToZero() {
        // `TurnTokens` normalise déjà : une valeur négative n'atteint jamais le journal.
        service.recordTurn(alice, project, host, TurnTokens.of(-10, 300), COST);

        verify(writer).write(alice, project, host, TurnTokens.of(0, 300), COST);
    }

    @Test
    void writesNothingWithoutUser() {
        service.recordTurn(null, project, host, TurnTokens.of(100L, 100L), COST);

        verifyNoInteractions(writer);
    }

    @Test
    void writesTheTurnEvenWithoutACost() {
        // Un coût absent ne doit pas faire perdre le VOLUME : la consommation par client (F-61) en
        // vit, et elle existait avant que le coût ne soit enregistré.
        TurnTokens tokens = TurnTokens.of(800L, 90L);

        service.recordTurn(alice, project, host, tokens, null);

        verify(writer).write(alice, project, host, tokens, null);
    }

    @Test
    void writeFailureNeverBreaksTheTurn() {
        // Quand le journal échoue, le fournisseur a déjà été appelé et payé : un relevé perdu est un
        // défaut d'information, un tour en échec serait un défaut de service ET d'argent.
        doThrow(new IllegalStateException("base indisponible"))
                .when(writer).write(any(), any(), any(), any(), any());

        assertThatCode(() -> service.recordTurn(alice, project, host, TurnTokens.of(1_000L, 200L),
                COST)).doesNotThrowAnyException();
    }

    @Test
    void doesNotWriteWhenOnlyOutputIsNegative() {
        service.recordTurn(alice, project, host, TurnTokens.of(0, -5), COST);

        verify(writer, never()).write(eq(alice), any(), any(), any(), any());
    }
}
