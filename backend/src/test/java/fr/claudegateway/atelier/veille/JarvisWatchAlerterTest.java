package fr.claudegateway.atelier.veille;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.AIProviderException;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.atelier.AtelierProperties;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.push.PushEvent;
import fr.claudegateway.push.PushNotificationService;
import fr.claudegateway.quota.ProviderCostCalculator;
import fr.claudegateway.quota.QuotaExceededException;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.quota.TurnCost;
import fr.claudegateway.quota.UsageLedgerService;

/** F-230 / SF-230-03 (D4) — sur changement : le diff seul au fournisseur, la phrase, l'alerte, le son, le fil. */
class JarvisWatchAlerterTest {

    private final UUID userId = UUID.randomUUID();
    private AIProvider ai;
    private JarvisWatchAlertRepository alerts;
    private JarvisWatchThreadNote note;
    private PushNotificationService push;
    private QuotaService quota;
    private UsageLedgerService ledger;
    private ProviderCostCalculator costs;
    private JarvisWatchAlerter alerter;
    private JarvisWatch watch;
    private final Workspace subject = Workspace.builder().id(UUID.randomUUID()).build();
    private final List<JarvisWatchDiff.Change> changes = List.of(
            new JarvisWatchDiff.Change(JarvisWatchDiff.Kind.STATUS, "AG-2", "Batch", "En cours", "Bloqué"));

    @BeforeEach
    void setUp() {
        ai = mock(AIProvider.class);
        alerts = mock(JarvisWatchAlertRepository.class);
        note = mock(JarvisWatchThreadNote.class);
        push = mock(PushNotificationService.class);
        quota = mock(QuotaService.class);
        ledger = mock(UsageLedgerService.class);
        costs = mock(ProviderCostCalculator.class);
        AtelierProperties atelier = mock(AtelierProperties.class);
        when(atelier.model()).thenReturn("modele-boucle");
        alerter = new JarvisWatchAlerter(ai, alerts, new JarvisAlertLinks(false), note,
                Clock.fixed(OffsetDateTime.parse("2026-11-02T08:00:00Z").toInstant(), ZoneOffset.UTC), atelier, "");
        ByokKeyService byok = mock(ByokKeyService.class);
        when(byok.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        alerter.setAccounting(byok, ledger, costs, quota);
        alerter.setPush(push);
        watch = JarvisWatch.builder().id(UUID.randomUUID()).userId(userId).workspaceId(subject.getId()).name("Agenor")
                .platform("Jira").scope("Tableau agenor").chantierNumber(2)
                .monthlyBudgetUsd(new BigDecimal("5.44")).build();
        when(alerts.spentSince(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(alerts.save(any())).thenAnswer(invocation -> {
            JarvisWatchAlert alert = invocation.getArgument(0);
            alert.setId(UUID.randomUUID());
            return alert;
        });
        when(costs.calculate(any(), any())).thenReturn(
                new TurnCost(new BigDecimal("0.04"), TurnCost.Source.CALCULATED, "m", "v", false));
    }

    private JarvisWatchAlert saved() {
        ArgumentCaptor<JarvisWatchAlert> alert = ArgumentCaptor.forClass(JarvisWatchAlert.class);
        verify(alerts).save(alert.capture());
        return alert.getValue();
    }

    @Test
    void theProviderReadsTheDiffOnlyAndWritesTheSentence() {
        when(ai.complete(any())).thenReturn(new ChatCompletionResult("Agenor : AG-2 est passé en bloqué.", "m", 400, 30));
        alerter.onChange(watch, subject, JarvisWatchRun.builder().id(UUID.randomUUID()).build(), changes);

        ArgumentCaptor<ChatCompletionRequest> request = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(ai).complete(request.capture());
        assertThat(request.getValue().system()).isEqualTo(JarvisWatchAlerter.CONSIGNE);
        assertThat(request.getValue().model()).isEqualTo("modele-boucle");
        String message = request.getValue().messages().get(0).content();
        assertThat(message).contains("Tableau agenor").contains("\"STATUS\"").contains("AG-2");

        JarvisWatchAlert alert = saved();
        assertThat(alert.getSentence()).isEqualTo("Agenor : AG-2 est passé en bloqué.");
        assertThat(alert.isAiWritten()).isTrue();
        assertThat(alert.getCostUsd()).isEqualByComparingTo("0.04");
        assertThat(alert.getChantierNumber()).isEqualTo(2);
        verify(ledger).recordTurn(eq(userId), eq(subject.getId()), any(), any(), any(), any());
        verify(note).post(watch, "Agenor : AG-2 est passé en bloqué.");
        verify(push).notify(eq(userId), eq(subject.getId()), eq(PushEvent.WATCH_CHANGED),
                eq("/atelier/" + subject.getId() + "?alerte=" + alert.getId()));
    }

    @Test
    void anExhaustedBudgetSpeaksWithoutTheProvider() {
        when(alerts.spentSince(any(), any(), any())).thenReturn(new BigDecimal("5.44"));
        alerter.onChange(watch, subject, null, changes);
        verify(ai, never()).complete(any());
        JarvisWatchAlert alert = saved();
        assertThat(alert.isAiWritten()).isFalse();
        assertThat(alert.getSentence()).isEqualTo("Agenor : 1 changement — 1 changement de statut.");
        verify(push).notify(eq(userId), eq(subject.getId()), eq(PushEvent.WATCH_CHANGED), any());
    }

    @Test
    void aReachedQuotaOrAFailingProviderFallsBackToThePlainSentence() {
        doThrow(new QuotaExceededException("quota")).when(quota).assertWithinQuota(userId);
        alerter.onChange(watch, subject, null, changes);
        verify(ai, never()).complete(any());

        org.mockito.Mockito.reset(quota);
        when(ai.complete(any())).thenThrow(new AIProviderException("panne"));
        assertThat(alerter.write(watch, subject, changes).ai()).isFalse();
    }

    @Test
    void theJarvisRouteIsUsedOnceAnnounced() {
        UUID alert = UUID.randomUUID();
        UUID ws = UUID.randomUUID();
        assertThat(new JarvisAlertLinks(true).linkFor(alert, ws)).isEqualTo("/jarvis?alerte=" + alert);
        assertThat(new JarvisAlertLinks(false).linkFor(alert, ws)).isEqualTo("/atelier/" + ws + "?alerte=" + alert);
    }
}
