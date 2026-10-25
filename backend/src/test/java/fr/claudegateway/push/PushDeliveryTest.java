package fr.claudegateway.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;

import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.Urgency;

/**
 * F-217 / SF-217-01 (D2) : l'urgence et la durée de vie de chaque événement, et leur pose par le transport
 * réel (en-têtes {@code Urgency} et {@code TTL}).
 */
class PushDeliveryTest {

    @Test
    void decisionsAreUrgentAndShortLived() {
        for (PushEvent event : new PushEvent[] {PushEvent.AUTHORIZATION_REQUESTED, PushEvent.QUESTION_ASKED,
                PushEvent.QUESTION_REMINDER}) {
            assertThat(PushDelivery.forEvent(event)).as(event.name())
                    .isEqualTo(new PushDelivery(PushUrgency.HIGH, 900));
        }
    }

    @Test
    void plansAndValidationsAreUrgentForADay() {
        assertThat(PushDelivery.forEvent(PushEvent.PLAN_AWAITING)).isEqualTo(new PushDelivery(PushUrgency.HIGH, 86_400));
        assertThat(PushDelivery.forEvent(PushEvent.VALIDATION_AWAITING))
                .isEqualTo(new PushDelivery(PushUrgency.HIGH, 86_400));
    }

    @Test
    void everythingElseIsNormalForADay() {
        for (PushEvent event : PushEvent.values()) {
            if (!event.requiresAction()) {
                assertThat(PushDelivery.forEvent(event)).as(event.name())
                        .isEqualTo(new PushDelivery(PushUrgency.NORMAL, 86_400));
            }
        }
        assertThat(PushDelivery.TEST).isEqualTo(new PushDelivery(PushUrgency.HIGH, 300));
    }

    @Test
    void theRealTransportPutsTheUrgencyAndTheTtlOnTheNotification() throws Exception {
        // CA5 : sans cela, la bibliothèque n'envoie aucune urgence et garde la charge 28 jours.
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME);
        generator.initialize(new ECGenParameterSpec("prime256v1"));
        ECPublicKey publicKey = (ECPublicKey) generator.generateKeyPair().getPublic();
        String p256dh = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.getQ().getEncoded(false));
        String auth = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
        PushSubscription subscription = PushSubscription.builder().id(UUID.randomUUID()).userId(UUID.randomUUID())
                .endpoint("https://push.example/abc").p256dh(p256dh).auth(auth)
                .createdAt(OffsetDateTime.now()).build();

        Notification urgent = MartijndwarsWebPushTransport.notification(subscription, "{}",
                new PushDelivery(PushUrgency.HIGH, 900));
        assertThat(urgent.getUrgency()).isEqualTo(Urgency.HIGH);
        assertThat(urgent.getTTL()).isEqualTo(900);
        assertThat(urgent.getEndpoint()).isEqualTo("https://push.example/abc");

        Notification fallback = MartijndwarsWebPushTransport.notification(subscription, "{}", null);
        assertThat(fallback.getUrgency()).isEqualTo(Urgency.NORMAL);
        assertThat(fallback.getTTL()).isEqualTo(86_400);
    }
}
