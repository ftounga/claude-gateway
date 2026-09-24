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

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = org.mockito.Mockito.mock(PushSubscriptionRepository.class);
        transport = org.mockito.Mockito.mock(WebPushTransport.class);
        service = new PushNotificationService(repository, transport, new ObjectMapper());
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
