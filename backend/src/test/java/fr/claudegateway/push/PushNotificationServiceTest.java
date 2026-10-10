package fr.claudegateway.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.push.WebPushTransport.Result;

/**
 * L'émetteur Web Push (F-153 / SF-153-02) : charge par {@code user_id}, charge <b>neutre</b>, purge
 * des endpoints morts, et repli propre quand le transport est inactif. Transport et repository
 * bouchonnés (Mockito) — aucun réseau, aucune base.
 */
class PushNotificationServiceTest {

    private PushSubscriptionRepository repository;
    private WebPushTransport transport;
    private PushNotificationService service;
    private TerminalWatch watch;
    private MutableClock clock;

    /** Horloge réglable : la fenêtre anti-doublon se teste sans attendre. */
    static final class MutableClock extends java.time.Clock {
        private java.time.Instant now;

        MutableClock(java.time.Instant now) {
            this.now = now;
        }

        void advance(java.time.Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return now;
        }
    }

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = org.mockito.Mockito.mock(PushSubscriptionRepository.class);
        transport = org.mockito.Mockito.mock(WebPushTransport.class);
        watch = org.mockito.Mockito.mock(TerminalWatch.class);
        clock = new MutableClock(java.time.Instant.parse("2026-10-10T12:00:00Z"));
        service = new PushNotificationService(repository, transport, new ObjectMapper(), watch, clock);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private PushSubscription sub(String endpoint) {
        return PushSubscription.builder().id(UUID.randomUUID()).userId(userId).endpoint(endpoint)
                .p256dh("pub").auth("auth").createdAt(OffsetDateTime.now()).build();
    }

    @Test
    void turnDoneSendsANeutralPayloadToTheOwnersDevices() {
        when(transport.isEnabled()).thenReturn(true);
        when(repository.findByUserId(userId)).thenReturn(List.of(sub("https://push/a"), sub("https://push/b")));
        when(transport.send(any(), any())).thenReturn(Result.DELIVERED);

        service.notifyTurnDone(userId, workspaceId);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(transport, timeout(2000).times(2)).send(any(), payload.capture());
        String json = payload.getValue();
        assertThat(json).contains("Une réponse est prête");
        // Charge neutre : la route porte un identifiant opaque, jamais un nom de projet ni une commande.
        assertThat(json).contains("/atelier/" + workspaceId);
    }

    @Test
    void authorizationRequestedSendsTheCriticalNeutralTitle() {
        when(transport.isEnabled()).thenReturn(true);
        when(repository.findByUserId(userId)).thenReturn(List.of(sub("https://push/a")));
        when(transport.send(any(), any())).thenReturn(Result.DELIVERED);

        service.notifyAuthorizationRequested(userId, workspaceId);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(transport, timeout(2000)).send(any(), payload.capture());
        assertThat(payload.getValue()).contains("Une autorisation est demandée");
    }

    @Test
    void questionAskedSendsANeutralTitleAndBodyWithTheTerminalDeepLink() {
        when(transport.isEnabled()).thenReturn(true);
        when(repository.findByUserId(userId)).thenReturn(List.of(sub("https://push/a")));
        when(transport.send(any(), any())).thenReturn(Result.DELIVERED);

        service.notifyQuestionAsked(userId, workspaceId);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(transport, timeout(2000)).send(any(), payload.capture());
        assertThat(payload.getValue())
                .contains("Une question vous attend")
                .contains("Ouvrez l'application pour répondre.")
                .contains("/atelier/" + workspaceId)
                .contains("openWindow");
    }

    @Test
    void questionAskedIsSilentWithoutTransportOrUser() throws InterruptedException {
        when(transport.isEnabled()).thenReturn(false);
        service.notifyQuestionAsked(userId, workspaceId);

        when(transport.isEnabled()).thenReturn(true);
        service.notifyQuestionAsked(null, workspaceId);

        Thread.sleep(200);
        verify(repository, never()).findByUserId(any());
        verify(transport, never()).send(any(), any());
    }

    @Test
    void everyEventCarriesItsNeutralTitleItsCodeAndATagPerTerminal() throws Exception {
        // F-185 / SF-185-02 : le catalogue complet, charge neutre, code d'événement et tag par terminal.
        ObjectMapper mapper = new ObjectMapper();
        for (PushEvent event : PushEvent.values()) {
            com.fasterxml.jackson.databind.JsonNode notification =
                    mapper.readTree(service.buildPayload(event, workspaceId)).get("notification");
            assertThat(notification.get("title").asText()).isEqualTo(event.title());
            assertThat(notification.get("body").asText()).isEqualTo(event.body());
            assertThat(notification.get("tag").asText()).isEqualTo("cg-" + workspaceId);
            assertThat(notification.get("data").get("event").asText()).isEqualTo(event.name());
            assertThat(notification.get("data").get("url").asText()).isEqualTo("/atelier/" + workspaceId);
            // Neutre : aucun identifiant de compte ne voyage.
            assertThat(notification.toString()).doesNotContain(userId.toString());
        }
    }

    @Test
    void notifySendsTheCatalogEventToTheOwnersDevicesOnly() {
        when(transport.isEnabled()).thenReturn(true);
        when(repository.findByUserId(userId)).thenReturn(List.of(sub("https://push/a")));
        when(transport.send(any(), any())).thenReturn(Result.DELIVERED);

        service.notify(userId, workspaceId, PushEvent.PLAN_AWAITING);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(transport, timeout(2000)).send(any(), payload.capture());
        assertThat(payload.getValue()).contains("Un plan attend votre accord").contains("PLAN_AWAITING");
        verify(repository).findByUserId(userId);
    }

    @Test
    void aNullEventIsIgnored() throws InterruptedException {
        when(transport.isEnabled()).thenReturn(true);
        service.notify(userId, workspaceId, null);
        Thread.sleep(100);
        verify(transport, never()).send(any(), any());
    }

    // ------------------------------------------------ F-185 / SF-185-03 : D7 enfin tenue

    private void deliverable() {
        when(transport.isEnabled()).thenReturn(true);
        when(repository.findByUserId(userId)).thenReturn(List.of(sub("https://push/a")));
        when(transport.send(any(), any())).thenReturn(Result.DELIVERED);
    }

    @Test
    void aWatchedTerminalIsNotNotified() throws InterruptedException {
        deliverable();
        when(watch.watching(userId, workspaceId)).thenReturn(true);

        service.notify(userId, workspaceId, PushEvent.TURN_DONE);

        verify(watch, timeout(2000)).watching(userId, workspaceId);
        Thread.sleep(100);
        verify(transport, never()).send(any(), any());
    }

    @Test
    void anUnwatchedTerminalIsNotifiedAndThePresenceIsAskedForThisAccountAndThisTerminal() {
        deliverable();
        when(watch.watching(userId, workspaceId)).thenReturn(false);

        service.notify(userId, workspaceId, PushEvent.TURN_DONE);

        verify(transport, timeout(2000)).send(any(), any());
        verify(watch).watching(userId, workspaceId);
    }

    @Test
    void anUnreadablePresenceDoesNotSilenceTheNotification() {
        deliverable();
        when(watch.watching(userId, workspaceId)).thenThrow(new IllegalStateException("base"));

        service.notify(userId, workspaceId, PushEvent.QUESTION_ASKED);

        verify(transport, timeout(2000)).send(any(), any());
    }

    @Test
    void theSameEventForTheSameTerminalIsSentOnceWithinThirtySeconds() throws InterruptedException {
        deliverable();

        service.notify(userId, workspaceId, PushEvent.QUESTION_ASKED);
        service.notify(userId, workspaceId, PushEvent.QUESTION_ASKED);
        verify(transport, timeout(2000).times(1)).send(any(), any());
        Thread.sleep(100);
        verify(transport, times(1)).send(any(), any());

        // Un autre événement passe ; le même, après la fenêtre, aussi.
        service.notify(userId, workspaceId, PushEvent.TURN_DONE);
        verify(transport, timeout(2000).times(2)).send(any(), any());
        clock.advance(PushNotificationService.DEDUP_WINDOW.plusSeconds(1));
        service.notify(userId, workspaceId, PushEvent.QUESTION_ASKED);
        verify(transport, timeout(2000).times(3)).send(any(), any());
    }

    @Test
    void anotherTerminalIsNotADuplicate() {
        deliverable();
        UUID other = UUID.randomUUID();

        service.notify(userId, workspaceId, PushEvent.TURN_DONE);
        service.notify(userId, other, PushEvent.TURN_DONE);

        verify(transport, timeout(2000).times(2)).send(any(), any());
    }

    @Test
    void aDeadEndpointIsPurged() {
        when(transport.isEnabled()).thenReturn(true);
        PushSubscription alive = sub("https://push/alive");
        PushSubscription dead = sub("https://push/dead");
        when(repository.findByUserId(userId)).thenReturn(List.of(alive, dead));
        when(transport.send(eq(alive), any())).thenReturn(Result.DELIVERED);
        when(transport.send(eq(dead), any())).thenReturn(Result.EXPIRED);

        service.notifyTurnDone(userId, workspaceId);

        verify(repository, timeout(2000)).deleteByEndpoint("https://push/dead");
        verify(repository, never()).deleteByEndpoint("https://push/alive");
    }

    @Test
    void nothingIsSentWhenTheTransportIsDisabled() throws InterruptedException {
        when(transport.isEnabled()).thenReturn(false);

        service.notifyTurnDone(userId, workspaceId);
        service.notifyAuthorizationRequested(userId, workspaceId);

        // Laisse le temps à un éventuel envoi (il ne doit pas y en avoir).
        Thread.sleep(200);
        verify(repository, never()).findByUserId(any());
        verify(transport, never()).send(any(), any());
    }

    @Test
    void aNullUserIsIgnored() throws InterruptedException {
        when(transport.isEnabled()).thenReturn(true);

        service.notifyTurnDone(null, workspaceId);

        Thread.sleep(200);
        verify(repository, never()).findByUserId(any());
    }
}
