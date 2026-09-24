package fr.claudegateway.bilan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.user.UserRole;

/**
 * Le déclenchement du bilan (F-155 / SF-155-03).
 *
 * <p>Ce que ces tests tiennent : <b>euros OU tours, le premier atteint</b> ; le silence quand il n'y
 * a rien à dire ; <b>aucun calcul</b> pour qui n'est pas administrateur ; et un nouveau départ que
 * le bilan ne peut pas faire échouer.</p>
 */
class SessionBilanTriggerServiceTest {

    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-09-24T08:00:00Z");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2026-09-24T12:00:00Z");

    private final SessionLedgerService ledgers = mock(SessionLedgerService.class);
    private final SessionSuggestionService suggestions = mock(SessionSuggestionService.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private SessionBilanTriggerService service;

    @BeforeEach
    void setUp() {
        service = new SessionBilanTriggerService(ledgers, suggestions,
                SessionBilanProperties.defaults());
    }

    private SessionLedger ledger(int turns, String costEur) {
        return new SessionLedger(FROM, TO, turns, Duration.ofHours(4), 5, 0, 1,
                new BigDecimal(costEur), 1000, 100, 0, 0, 0, 0, "claude-opus-5",
                Duration.ofSeconds(10), List.of(), List.of());
    }

    private void given(SessionLedger ledger, int kept, int discarded) {
        when(ledgers.of(userId, workspaceId, FROM, TO)).thenReturn(ledger);
        List<SessionSuggestion> list = java.util.stream.IntStream.range(0, kept)
                .mapToObj(i -> SessionSuggestion.of(SessionSuggestion.Axis.TEMPS, "a", "m", 20))
                .toList();
        when(suggestions.examine(ledger))
                .thenReturn(new SessionSuggestionService.Verdict(list, discarded));
    }

    private BilanTrigger decide() {
        return service.decide(userId, workspaceId, UserRole.ADMIN, FROM, TO).trigger();
    }

    @Test
    @DisplayName("au-dessus du seuil en EUROS seul : automatique — une session courte et chère le mérite")
    void aboveTheEuroThresholdAlone() {
        given(ledger(3, "2.50"), 1, 0); // 3 tours seulement, mais 2,50 €
        assertThat(decide()).isEqualTo(BilanTrigger.AUTOMATIQUE);
    }

    @Test
    @DisplayName("au-dessus du seuil en TOURS seul : automatique — une session longue et bon marché aussi")
    void aboveTheTurnThresholdAlone() {
        given(ledger(25, "0.30"), 1, 0); // 30 centimes, mais 25 tours
        assertThat(decide()).isEqualTo(BilanTrigger.AUTOMATIQUE);
    }

    @Test
    @DisplayName("sous les DEUX, avec quelque chose à dire : proposé d'un clic")
    void belowBothIsOnlyProposed() {
        given(ledger(3, "0.20"), 1, 0);
        assertThat(decide()).isEqualTo(BilanTrigger.PROPOSE);
    }

    @Test
    @DisplayName("une suggestion ÉCARTÉE suffit à proposer : « j'ai regardé » vaut d'être dit")
    void discardedAloneStillProposes() {
        given(ledger(3, "0.20"), 0, 2);
        assertThat(decide()).isEqualTo(BilanTrigger.PROPOSE);
    }

    @Test
    @DisplayName("rien à dire du tout : AUCUN — un bilan à chaque départ deviendrait un bruit")
    void nothingToSayStaysSilent() {
        given(ledger(30, "50.00"), 0, 0); // pourtant longue ET chère : rien à dire reste rien
        assertThat(decide()).isEqualTo(BilanTrigger.AUCUN);
    }

    @Test
    @DisplayName("session vide : AUCUN, et les suggestions ne sont même pas consultées")
    void anEmptySessionIsSilent() {
        when(ledgers.of(userId, workspaceId, FROM, TO)).thenReturn(SessionLedger.empty(FROM, TO));
        assertThat(decide()).isEqualTo(BilanTrigger.AUCUN);
        verify(suggestions, never()).examine(any());
    }

    @Test
    @DisplayName("non-administrateur : AUCUN, et AUCUN relevé n'est calculé")
    void aNonAdminCostsNothing() {
        assertThat(service.decide(userId, workspaceId, UserRole.USER, FROM, TO).trigger())
                .isEqualTo(BilanTrigger.AUCUN);
        assertThat(service.decide(userId, workspaceId, null, FROM, TO).trigger())
                .isEqualTo(BilanTrigger.AUCUN);

        verify(ledgers, never()).of(any(), any(), any(), any());
        verify(suggestions, never()).examine(any());
    }

    @Test
    @DisplayName("une erreur du relevé ne casse rien : on perd un bilan, pas le geste de l'utilisateur")
    void afailureLosesTheBilanNotTheGesture() {
        when(ledgers.of(userId, workspaceId, FROM, TO))
                .thenThrow(new IllegalStateException("base HS"));

        SessionBilanTriggerService.Decision decision =
                service.decide(userId, workspaceId, UserRole.ADMIN, FROM, TO);

        assertThat(decision.trigger()).isEqualTo(BilanTrigger.AUCUN);
        assertThat(decision.ledger()).isNull();
    }

    @Test
    @DisplayName("les seuils sont configurables, et respectés")
    void thresholdsAreConfigurable() {
        SessionBilanTriggerService strict = new SessionBilanTriggerService(ledgers, suggestions,
                new SessionBilanProperties(null, null, null, new BigDecimal("100"), 1000));
        given(ledger(25, "2.50"), 1, 0); // largement au-dessus des DÉFAUTS

        assertThat(strict.decide(userId, workspaceId, UserRole.ADMIN, FROM, TO).trigger())
                .as("sous les seuils configurés, quoi qu'en disent les défauts")
                .isEqualTo(BilanTrigger.PROPOSE);
    }

    @Test
    @DisplayName("la décision rapporte la matière produite en chemin — pas seulement l'issue")
    void theDecisionCarriesItsMaterial() {
        SessionLedger ledger = ledger(25, "9.00");
        given(ledger, 2, 1);

        SessionBilanTriggerService.Decision decision =
                service.decide(userId, workspaceId, UserRole.ADMIN, FROM, TO);

        assertThat(decision.ledger()).isSameAs(ledger);
        assertThat(decision.verdict().suggestions()).hasSize(2);
        assertThat(decision.verdict().discarded()).isEqualTo(1);
    }
}
