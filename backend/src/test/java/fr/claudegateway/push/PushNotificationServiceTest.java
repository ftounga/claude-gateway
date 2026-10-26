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
    private NotificationJournal journal;
    private PushPreferences preferences;
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
        journal = org.mockito.Mockito.mock(NotificationJournal.class);
        preferences = org.mockito.Mockito.mock(PushPreferences.class);
        when(preferences.allowsPush(any(), any())).thenReturn(true);
        service = new PushNotificationService(repository, transport, new ObjectMapper(), watch, journal,
                preferences, clock);
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
        when(transport.send(any(), any(), any())).thenReturn(Result.DELIVERED);

        service.notifyTurnDone(userId, workspaceId);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(transport, timeout(2000).times(2)).send(any(), payload.capture(), any());
        String json = payload.getValue();
        assertThat(json).contains("Une réponse est prête");
        // Charge neutre : la route porte un identifiant opaque, jamais un nom de projet ni une commande.
        assertThat(json).contains("/atelier/" + workspaceId);
    }

    @Test
    void authorizationRequestedSendsTheCriticalNeutralTitle() {
        when(transport.isEnabled()).thenReturn(true);
        when(repository.findByUserId(userId)).thenReturn(List.of(sub("https://push/a")));
        when(transport.send(any(), any(), any())).thenReturn(Result.DELIVERED);

        service.notifyAuthorizationRequested(userId, workspaceId);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(transport, timeout(2000)).send(any(), payload.capture(), any());
        assertThat(payload.getValue()).contains("Une autorisation est demandée");
    }

    @Test
    void questionAskedSendsANeutralTitleAndBodyWithTheTerminalDeepLink() {
        when(transport.isEnabled()).thenReturn(true);
        when(repository.findByUserId(userId)).thenReturn(List.of(sub("https://push/a")));
        when(transport.send(any(), any(), any())).thenReturn(Result.DELIVERED);

        service.notifyQuestionAsked(userId, workspaceId);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(transport, timeout(2000)).send(any(), payload.capture(), any());
        assertThat(payload.getValue())
                .contains("Une question vous attend")
                .contains("Ouvrez l'application pour répondre.")
                .contains("/atelier/" + workspaceId)
                .contains("openWindow");
    }

    @Test
    void questionAskedIsSilentWithoutTransport() throws InterruptedException {
        when(transport.isEnabled()).thenReturn(false);
        service.notifyQuestionAsked(userId, workspaceId);

        Thread.sleep(200);
        verify(repository, never()).findByUserId(any());
        verify(transport, never()).send(any(), any(), any());
    }

    @Test
    void questionAskedIsSilentWithoutUser() throws InterruptedException {
        when(transport.isEnabled()).thenReturn(true);
        service.notifyQuestionAsked(null, workspaceId);

        Thread.sleep(200);
        verify(repository, never()).findByUserId(any());
        verify(transport, never()).send(any(), any(), any());
        verify(journal, never()).record(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
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
            // F-217 / SF-217-01 : un tag par événement (et par occurrence pour ce qui attend une action).
            assertThat(notification.get("tag").asText()).startsWith("cg-" + workspaceId + "-" + event.name());
            assertThat(notification.get("data").get("event").asText()).isEqualTo(event.name());
            assertThat(notification.get("data").get("url").asText()).isEqualTo("/atelier/" + workspaceId);
            // Neutre : aucun identifiant de compte ne voyage.
            assertThat(notification.toString()).doesNotContain(userId.toString());
        }
    }

    // ------------------------------------------------------------------ F-217 / SF-217-01

    private com.fasterxml.jackson.databind.JsonNode notificationOf(PushEvent event) throws Exception {
        return new ObjectMapper().readTree(service.buildPayload(event, workspaceId)).get("notification");
    }

    @Test
    void everyPayloadAsksForASoundAVibrationATimestampAndCarriesTheTerminal() throws Exception {
        // CA1 : une notification qui sonne, quel que soit l'événement.
        for (PushEvent event : PushEvent.values()) {
            com.fasterxml.jackson.databind.JsonNode n = notificationOf(event);
            assertThat(n.get("silent").isBoolean()).isTrue();
            assertThat(n.get("silent").asBoolean()).isFalse();
            assertThat(n.get("renotify").asBoolean()).isTrue();
            assertThat(n.get("timestamp").asLong()).isEqualTo(clock.millis());
            assertThat(n.get("vibrate").isArray()).isTrue();
            assertThat(n.get("vibrate").size()).isPositive();
            assertThat(n.get("data").get("workspaceId").asText()).isEqualTo(workspaceId.toString());
        }
    }

    @Test
    void onlyWhatAwaitsAnActionStaysOnScreenUntilTheGesture() throws Exception {
        // CA2 : requireInteraction exactement pour accord, question, rappel, plan, validation.
        java.util.Set<PushEvent> action = java.util.EnumSet.of(PushEvent.AUTHORIZATION_REQUESTED,
                PushEvent.QUESTION_ASKED, PushEvent.QUESTION_REMINDER, PushEvent.PLAN_AWAITING,
                PushEvent.VALIDATION_AWAITING);
        for (PushEvent event : PushEvent.values()) {
            assertThat(event.requiresAction()).as(event.name()).isEqualTo(action.contains(event));
            com.fasterxml.jackson.databind.JsonNode n = notificationOf(event);
            assertThat(n.get("requireInteraction").asBoolean()).as(event.name()).isEqualTo(action.contains(event));
            assertThat(n.get("vibrate").size()).as(event.name()).isEqualTo(action.contains(event) ? 5 : 1);
        }
    }

    @Test
    void twoSuccessiveAuthorizationsGetTwoTagsSoBothRingWhileAnInformationReplacesItself() throws Exception {
        // CA3 : Safari et Firefox ignorent renotify — une occurrence d'action = un tag propre.
        String first = notificationOf(PushEvent.AUTHORIZATION_REQUESTED).get("tag").asText();
        clock.advance(java.time.Duration.ofSeconds(5));
        String second = notificationOf(PushEvent.AUTHORIZATION_REQUESTED).get("tag").asText();
        assertThat(first).isNotEqualTo(second);
        assertThat(first).isEqualTo("cg-" + workspaceId + "-AUTHORIZATION_REQUESTED-"
                + (clock.millis() - 5_000));
        String done = notificationOf(PushEvent.TURN_DONE).get("tag").asText();
        clock.advance(java.time.Duration.ofSeconds(5));
        assertThat(notificationOf(PushEvent.TURN_DONE).get("tag").asText()).isEqualTo(done)
                .isEqualTo("cg-" + workspaceId + "-TURN_DONE");
    }

    @Test
    void theForgeTagIsUsedWithoutTerminal() throws Exception {
        com.fasterxml.jackson.databind.JsonNode n = new ObjectMapper()
                .readTree(service.buildPayload(PushEvent.ATTENTES_TO_FOLLOW_UP, null)).get("notification");
        assertThat(n.get("tag").asText()).isEqualTo("cg-forge-ATTENTES_TO_FOLLOW_UP");
        assertThat(n.get("data").has("workspaceId")).isFalse();
    }

    @Test
    void decisionsLeaveInHighUrgencyWithAShortLifeAndTheRestNormally() {
        // CA4 : le transport reçoit l'urgence et la durée de vie de l'événement.
        when(transport.isEnabled()).thenReturn(true);
        when(repository.findByUserId(userId)).thenReturn(List.of(sub("https://push/a")));
        when(transport.send(any(), any(), any())).thenReturn(Result.DELIVERED);

        service.notifyAuthorizationRequested(userId, workspaceId);
        ArgumentCaptor<PushDelivery> delivery = ArgumentCaptor.forClass(PushDelivery.class);
        verify(transport, timeout(2000)).send(any(), any(), delivery.capture());
        assertThat(delivery.getValue()).isEqualTo(new PushDelivery(PushUrgency.HIGH, 900));

        service.notifyTurnDone(userId, workspaceId);
        verify(transport, timeout(2000).times(2)).send(any(), any(), delivery.capture());
        assertThat(delivery.getValue()).isEqualTo(new PushDelivery(PushUrgency.NORMAL, 86_400));
    }

    @Test
    void theTestNotificationGoesToTheAccountsDevicesOnlyOutsideTheBellAndTheDedup() throws Exception {
        // CA7 / CA8 : seuls les appareils du compte, deux tests de suite partent, rien dans la cloche.
        when(transport.isEnabled()).thenReturn(true);
        when(repository.findByUserId(userId)).thenReturn(List.of(sub("https://push/a"), sub("https://push/b")));
        when(transport.send(any(), any(), any())).thenReturn(Result.DELIVERED);

        assertThat(service.sendTest(userId)).isEqualTo(2);
        assertThat(service.sendTest(userId)).isEqualTo(2);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(transport, timeout(2000).times(4)).send(any(), payload.capture(), eq(PushDelivery.TEST));
        com.fasterxml.jackson.databind.JsonNode n = new ObjectMapper().readTree(payload.getValue()).get("notification");
        assertThat(n.get("title").asText()).isEqualTo("Notification de test");
        assertThat(n.get("silent").asBoolean()).isFalse();
        assertThat(n.get("requireInteraction").asBoolean()).isFalse();
        assertThat(n.get("tag").asText()).startsWith("cg-test-");
        assertThat(n.get("data").get("url").asText()).isEqualTo("/settings");
        verify(repository, times(2)).findByUserId(userId);
        verify(journal, never()).record(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void theTestNotificationIsNotSentWithoutPushConfiguredButStillCountsTheDevices() throws InterruptedException {
        when(transport.isEnabled()).thenReturn(false);
        when(repository.findByUserId(userId)).thenReturn(List.of(sub("https://push/a")));

        assertThat(service.sendTest(userId)).isEqualTo(1);
        assertThat(service.configured()).isFalse();
        Thread.sleep(200);
        verify(transport, never()).send(any(), any(), any());
        assertThat(service.sendTest(null)).isZero();
    }

    @Test
    void aDeadEndpointIsPurgedByTheTestToo() {
        when(transport.isEnabled()).thenReturn(true);
        when(repository.findByUserId(userId)).thenReturn(List.of(sub("https://push/dead")));
        when(transport.send(any(), any(), any())).thenReturn(Result.EXPIRED);

        service.sendTest(userId);

        verify(repository, timeout(2000)).deleteByEndpoint("https://push/dead");
    }

    @Test
    void notifySendsTheCatalogEventToTheOwnersDevicesOnly() {
        when(transport.isEnabled()).thenReturn(true);
        when(repository.findByUserId(userId)).thenReturn(List.of(sub("https://push/a")));
        when(transport.send(any(), any(), any())).thenReturn(Result.DELIVERED);

        service.notify(userId, workspaceId, PushEvent.PLAN_AWAITING);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(transport, timeout(2000)).send(any(), payload.capture(), any());
        assertThat(payload.getValue()).contains("Un plan attend votre accord").contains("PLAN_AWAITING");
        verify(repository).findByUserId(userId);
    }

    @Test
    void aNullEventIsIgnored() throws InterruptedException {
        when(transport.isEnabled()).thenReturn(true);
        service.notify(userId, workspaceId, null);
        Thread.sleep(100);
        verify(transport, never()).send(any(), any(), any());
    }

    // ------------------------------------------------ F-185 / SF-185-03 : D7 enfin tenue

    private void deliverable() {
        when(transport.isEnabled()).thenReturn(true);
        when(repository.findByUserId(userId)).thenReturn(List.of(sub("https://push/a")));
        when(transport.send(any(), any(), any())).thenReturn(Result.DELIVERED);
    }

    @Test
    void aWatchedTerminalIsNotNotified() throws InterruptedException {
        deliverable();
        when(watch.watching(userId, workspaceId)).thenReturn(true);

        service.notify(userId, workspaceId, PushEvent.TURN_DONE);

        verify(watch, timeout(2000)).watching(userId, workspaceId);
        Thread.sleep(100);
        verify(transport, never()).send(any(), any(), any());
    }

    @Test
    void theQuestionReminderIsSentEvenWhenTheTerminalIsWatched() {
        // F-185 / SF-185-05 : le dernier filet avant une décision par défaut passe toujours.
        deliverable();
        when(watch.watching(userId, workspaceId)).thenReturn(true);

        service.notify(userId, workspaceId, PushEvent.QUESTION_REMINDER);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(transport, timeout(2000)).send(any(), payload.capture(), any());
        assertThat(payload.getValue()).contains("Une question attend toujours");
        verify(journal).record(userId, workspaceId, PushEvent.QUESTION_REMINDER, false);
    }

    @Test
    void anUnwatchedTerminalIsNotifiedAndThePresenceIsAskedForThisAccountAndThisTerminal() {
        deliverable();
        when(watch.watching(userId, workspaceId)).thenReturn(false);

        service.notify(userId, workspaceId, PushEvent.TURN_DONE);

        verify(transport, timeout(2000)).send(any(), any(), any());
        verify(watch).watching(userId, workspaceId);
    }

    @Test
    void anUnreadablePresenceDoesNotSilenceTheNotification() {
        deliverable();
        when(watch.watching(userId, workspaceId)).thenThrow(new IllegalStateException("base"));

        service.notify(userId, workspaceId, PushEvent.QUESTION_ASKED);

        verify(transport, timeout(2000)).send(any(), any(), any());
    }

    @Test
    void theSameEventForTheSameTerminalIsSentOnceWithinThirtySeconds() throws InterruptedException {
        deliverable();

        service.notify(userId, workspaceId, PushEvent.QUESTION_ASKED);
        service.notify(userId, workspaceId, PushEvent.QUESTION_ASKED);
        verify(transport, timeout(2000).times(1)).send(any(), any(), any());
        Thread.sleep(100);
        verify(transport, times(1)).send(any(), any(), any());

        // Un autre événement passe ; le même, après la fenêtre, aussi.
        service.notify(userId, workspaceId, PushEvent.TURN_DONE);
        verify(transport, timeout(2000).times(2)).send(any(), any(), any());
        clock.advance(PushNotificationService.DEDUP_WINDOW.plusSeconds(1));
        service.notify(userId, workspaceId, PushEvent.QUESTION_ASKED);
        verify(transport, timeout(2000).times(3)).send(any(), any(), any());
    }

    @Test
    void anotherTerminalIsNotADuplicate() {
        deliverable();
        UUID other = UUID.randomUUID();

        service.notify(userId, workspaceId, PushEvent.TURN_DONE);
        service.notify(userId, other, PushEvent.TURN_DONE);

        verify(transport, timeout(2000).times(2)).send(any(), any(), any());
    }

    // ------------------------------------------------ F-185 / SF-185-04 : le centre de notifications

    @Test
    void theEventIsJournaledEvenWithoutPushConfigured() throws InterruptedException {
        when(transport.isEnabled()).thenReturn(false);

        service.notify(userId, workspaceId, PushEvent.TURN_DONE);

        verify(journal, timeout(2000)).record(userId, workspaceId, PushEvent.TURN_DONE, false);
        Thread.sleep(100);
        verify(transport, never()).send(any(), any(), any());
    }

    @Test
    void aWatchedTerminalIsJournaledAsAlreadySeen() {
        deliverable();
        when(watch.watching(userId, workspaceId)).thenReturn(true);

        service.notify(userId, workspaceId, PushEvent.PLAN_AWAITING);

        verify(journal, timeout(2000)).record(userId, workspaceId, PushEvent.PLAN_AWAITING, true);
    }

    @Test
    void aDuplicateIsNotJournaledTwice() throws InterruptedException {
        deliverable();

        service.notify(userId, workspaceId, PushEvent.TURN_DONE);
        service.notify(userId, workspaceId, PushEvent.TURN_DONE);

        verify(journal, timeout(2000)).record(userId, workspaceId, PushEvent.TURN_DONE, false);
        Thread.sleep(100);
        verify(journal, times(1)).record(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void aFailingJournalNeverStopsThePush() {
        deliverable();
        org.mockito.Mockito.doThrow(new IllegalStateException("base")).when(journal)
                .record(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());

        service.notify(userId, workspaceId, PushEvent.TURN_DONE);

        verify(transport, timeout(2000)).send(any(), any(), any());
    }

    // ------------------------------------------------ F-185 / SF-185-06 : préférences

    @Test
    void aMutedEventDoesNotRingButIsStillJournaled() throws InterruptedException {
        deliverable();
        when(preferences.allowsPush(userId, PushEvent.TURN_DONE)).thenReturn(false);

        service.notify(userId, workspaceId, PushEvent.TURN_DONE);

        verify(journal, timeout(2000)).record(userId, workspaceId, PushEvent.TURN_DONE, false);
        Thread.sleep(100);
        verify(transport, never()).send(any(), any(), any());
    }

    @Test
    void aCriticalEventRingsWhateverThePreferences() {
        deliverable();
        when(preferences.allowsPush(any(), any())).thenReturn(false);

        service.notify(userId, workspaceId, PushEvent.AUTHORIZATION_REQUESTED);

        verify(transport, timeout(2000)).send(any(), any(), any());
        // La règle D5 vit dans l'émetteur : on ne demande même pas.
        verify(preferences, never()).allowsPush(userId, PushEvent.AUTHORIZATION_REQUESTED);
    }

    @Test
    void unreadablePreferencesLetThePushThrough() {
        deliverable();
        when(preferences.allowsPush(any(), any())).thenThrow(new IllegalStateException("base"));

        service.notify(userId, workspaceId, PushEvent.TURN_DONE);

        verify(transport, timeout(2000)).send(any(), any(), any());
    }

    @Test
    void aDeadEndpointIsPurged() {
        when(transport.isEnabled()).thenReturn(true);
        PushSubscription alive = sub("https://push/alive");
        PushSubscription dead = sub("https://push/dead");
        when(repository.findByUserId(userId)).thenReturn(List.of(alive, dead));
        when(transport.send(eq(alive), any(), any())).thenReturn(Result.DELIVERED);
        when(transport.send(eq(dead), any(), any())).thenReturn(Result.EXPIRED);

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
        verify(transport, never()).send(any(), any(), any());
    }

    @Test
    void aNullUserIsIgnored() throws InterruptedException {
        when(transport.isEnabled()).thenReturn(true);

        service.notifyTurnDone(null, workspaceId);

        Thread.sleep(200);
        verify(repository, never()).findByUserId(any());
    }
}
