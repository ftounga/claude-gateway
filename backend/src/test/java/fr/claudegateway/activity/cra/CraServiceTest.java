package fr.claudegateway.activity.cra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.activity.CraEntry;
import fr.claudegateway.activity.CraEntryRepository;
import fr.claudegateway.activity.InvalidActivityConfigException;
import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ModelCatalog;
import fr.claudegateway.activity.cra.CraService.CraLineStatus;
import fr.claudegateway.activity.cra.CraService.CraOutcome;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostService;

/** Le CRA par message : rapprochement, validation, persistance (F-124 / SF-124-03). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CraServiceTest {

    @Mock private AIProvider aiProvider;
    @Mock private ModelCatalog modelCatalog;
    @Mock private QuotaService quotaService;
    @Mock private RunnerHostService hostService;
    @Mock private CraEntryRepository craRepository;

    private CraService service;

    private final UUID userId = UUID.randomUUID();
    private RunnerHost free;
    private RunnerHost kg;
    private final YearMonth month = YearMonth.of(2025, 9); // 22 jours ouvrés

    @BeforeEach
    void setUp() {
        service = new CraService(aiProvider, modelCatalog, quotaService, hostService, craRepository);
        free = host("Free");
        kg = host("KG");
        when(modelCatalog.fastModel()).thenReturn("fast");
        when(hostService.list(userId)).thenReturn(List.of(free, kg));
        when(craRepository.findByUserIdAndHostIdAndYearMonth(eq(userId), any(), any()))
                .thenReturn(Optional.empty());
    }

    private RunnerHost host(String name) {
        return RunnerHost.builder().id(UUID.randomUUID()).userId(userId).name(name).build();
    }

    private void modelReturns(String json) {
        when(aiProvider.complete(any(ChatCompletionRequest.class)))
                .thenReturn(new ChatCompletionResult(json, "fast", 10, 5));
    }

    @Test
    void writesEachRecognizedClient_forTheDefaultMonth() {
        modelReturns("[{\"client\":\"Free\",\"days\":20},{\"client\":\"KG\",\"days\":13}]");

        CraOutcome outcome = service.submit(userId, "Free 20j, KG 13j", month);

        assertThat(outcome.lines()).hasSize(2);
        assertThat(outcome.lines()).allMatch(l -> l.status() == CraLineStatus.WRITTEN);
        assertThat(outcome.lines().get(0).month()).isEqualTo("2025-09");
        verify(craRepository, org.mockito.Mockito.times(2)).save(any(CraEntry.class));
        // Provider-First + quota : quota vérifié AVANT, consommation enregistrée APRÈS.
        verify(quotaService).assertWithinQuota(userId);
        verify(quotaService).recordUsage(eq(userId), any(), eq(null), eq(null), eq(null));
    }

    @Test
    void unknownClientIsAsked_neverGuessed() {
        modelReturns("[{\"client\":\"Acme\",\"days\":5}]");

        CraOutcome outcome = service.submit(userId, "Acme 5j", month);

        assertThat(outcome.lines()).hasSize(1);
        assertThat(outcome.lines().get(0).status()).isEqualTo(CraLineStatus.UNKNOWN_HOST);
        assertThat(outcome.lines().get(0).cited()).isEqualTo("Acme");
        verify(craRepository, never()).save(any());
    }

    @Test
    void rejectsMoreDaysThanBusinessDays() {
        modelReturns("[{\"client\":\"Free\",\"days\":25}]"); // > 22 jours ouvrés en sept. 2025

        CraOutcome outcome = service.submit(userId, "Free 25j", month);

        assertThat(outcome.lines().get(0).status()).isEqualTo(CraLineStatus.REJECTED);
        verify(craRepository, never()).save(any());
    }

    @Test
    void acceptsHalfDays() {
        modelReturns("[{\"client\":\"Free\",\"days\":0.5}]");

        CraOutcome outcome = service.submit(userId, "Free une demi-journée", month);

        assertThat(outcome.lines().get(0).status()).isEqualTo(CraLineStatus.WRITTEN);
        assertThat(outcome.lines().get(0).days()).isEqualByComparingTo(new BigDecimal("0.5"));
    }

    @Test
    void overwritesAnExistingMonth_insteadOfDuplicating() {
        modelReturns("[{\"client\":\"Free\",\"days\":18}]");
        CraEntry existing = CraEntry.builder().userId(userId).hostId(free.getId())
                .yearMonth("2025-09").days(new BigDecimal("20")).build();
        when(craRepository.findByUserIdAndHostIdAndYearMonth(userId, free.getId(), "2025-09"))
                .thenReturn(Optional.of(existing));

        service.submit(userId, "Free 18j", month);

        assertThat(existing.getDays()).isEqualByComparingTo(new BigDecimal("18")); // écrasé
        verify(craRepository, never()).save(any());
    }

    @Test
    void usesTheMonthPrecisedInTheMessage() {
        modelReturns("[{\"client\":\"Free\",\"days\":10,\"month\":\"2025-07\"}]");

        CraOutcome outcome = service.submit(userId, "Free 10j en juillet", month);

        assertThat(outcome.lines().get(0).month()).isEqualTo("2025-07");
    }

    @Test
    void emptyMessageIsRefused_beforeAnyProviderCall() {
        assertThatThrownBy(() -> service.submit(userId, "   ", month))
                .isInstanceOf(InvalidActivityConfigException.class);
        verify(aiProvider, never()).complete(any());
        verify(quotaService, never()).assertWithinQuota(any());
    }

    @Test
    void nothingUnderstood_returnsAnEmptyRecap() {
        modelReturns("Je n'ai pas compris.");
        CraOutcome outcome = service.submit(userId, "bonjour", month);
        assertThat(outcome.lines()).isEmpty();
        verify(craRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ plages (SF-124-04)

    private final YearMonth august = YearMonth.of(2025, 8); // 20 jours ouvrés (15/08 férié)

    @Test
    void resolvesFromDayToEndOfMonth_serverSide() {
        // « du 10 à la fin du mois » : le serveur compte les jours ouvrés (14), le modèle ne compte pas.
        modelReturns("[{\"client\":\"Free\",\"range\":{\"fromDay\":10}}]");

        CraOutcome outcome = service.submit(userId, "du 10 à la fin du mois chez Free", august);

        assertThat(outcome.lines().get(0).status()).isEqualTo(CraLineStatus.WRITTEN);
        assertThat(outcome.lines().get(0).days()).isEqualByComparingTo(new BigDecimal("14"));
        assertThat(outcome.lines().get(0).month()).isEqualTo("2025-08");
        assertThat(outcome.lines().get(0).period()).isEqualTo("du 10 a la fin du mois");
    }

    @Test
    void resolvesFullMonth() {
        modelReturns("[{\"client\":\"Free\",\"range\":{\"preset\":\"FULL_MONTH\"}}]");

        CraOutcome outcome = service.submit(userId, "tout le mois chez Free", august);

        assertThat(outcome.lines().get(0).days()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(outcome.lines().get(0).period()).isEqualTo("tout le mois");
    }

    @Test
    void resolvesADayToDayRange() {
        modelReturns("[{\"client\":\"KG\",\"range\":{\"fromDay\":10,\"toDay\":20}}]");

        CraOutcome outcome = service.submit(userId, "du 10 au 20 chez KG", august);

        assertThat(outcome.lines().get(0).status()).isEqualTo(CraLineStatus.WRITTEN);
        assertThat(outcome.lines().get(0).days()).isEqualByComparingTo(new BigDecimal("7"));
        assertThat(outcome.lines().get(0).period()).isEqualTo("du 10 au 20");
    }

    @Test
    void expandsAllClients_oneLinePerOwnedHost() {
        // « chez tous mes clients » : le modèle (nourri de la liste) rend une ligne par poste.
        modelReturns("[{\"client\":\"Free\",\"range\":{\"fromDay\":10}},"
                + "{\"client\":\"KG\",\"range\":{\"fromDay\":10}}]");

        CraOutcome outcome = service.submit(userId, "du 10 à la fin du mois chez tous mes clients", august);

        assertThat(outcome.lines()).hasSize(2);
        assertThat(outcome.lines()).allMatch(l -> l.status() == CraLineStatus.WRITTEN);
        assertThat(outcome.lines()).allMatch(l -> l.days().compareTo(new BigDecimal("14")) == 0);
        verify(craRepository, org.mockito.Mockito.times(2)).save(any(CraEntry.class));
    }

    @Test
    void ownedHostNames_areGivenToTheModel_forIsolationAndMatching() {
        modelReturns("[]");

        service.submit(userId, "chez tous mes clients", august);

        org.mockito.ArgumentCaptor<ChatCompletionRequest> captor =
                org.mockito.ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(aiProvider).complete(captor.capture());
        String system = captor.getValue().system();
        assertThat(system).contains("Free").contains("KG");
    }

    @Test
    void aRangeWithNoBusinessDayIsRejected_notWritten() {
        // Du 16 au 17 août 2025 (samedi/dimanche) → 0 jour ouvré → refusé, rien écrit.
        modelReturns("[{\"client\":\"Free\",\"range\":{\"fromDay\":16,\"toDay\":17}}]");

        CraOutcome outcome = service.submit(userId, "le week-end du 16 chez Free", august);

        assertThat(outcome.lines().get(0).status()).isEqualTo(CraLineStatus.REJECTED);
        verify(craRepository, never()).save(any());
    }
}
