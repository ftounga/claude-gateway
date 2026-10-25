package fr.claudegateway.push;

import java.nio.charset.StandardCharsets;
import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Urgency;

/**
 * Transport Web Push réel (F-153 / SF-153-02) : signe (VAPID / ES256) et chiffre (RFC 8291,
 * {@code aes128gcm}) la charge, puis la POST au service push du navigateur — <b>protocole W3C Web
 * Push standard</b>, provider-agnostique (D6), jamais un appel LLM.
 *
 * <p>Construit <b>uniquement</b> quand VAPID est configuré (voir {@code PushConfig}) : la clé privée
 * vient du secret d'environnement, jamais du code. Ne lève jamais : toute erreur est ramenée à
 * {@link Result#FAILED} ; un 404/410 (endpoint mort) devient {@link Result#EXPIRED}.</p>
 */
public final class MartijndwarsWebPushTransport implements WebPushTransport {

    private static final Logger log = LoggerFactory.getLogger(MartijndwarsWebPushTransport.class);

    private final PushService pushService;

    public MartijndwarsWebPushTransport(PushProperties properties) throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        this.pushService = new PushService()
                .setPublicKey(properties.vapidPublicKey())
                .setPrivateKey(properties.vapidPrivateKey())
                .setSubject(properties.subject());
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public Result send(PushSubscription subscription, String payloadJson) {
        return send(subscription, payloadJson, PushDelivery.DEFAULT);
    }

    @Override
    public Result send(PushSubscription subscription, String payloadJson, PushDelivery delivery) {
        try {
            Notification notification = notification(subscription, payloadJson, delivery);
            int status = pushService.send(notification).getStatusLine().getStatusCode();
            if (status == 404 || status == 410) {
                return Result.EXPIRED;
            }
            if (status >= 200 && status < 300) {
                return Result.DELIVERED;
            }
            log.warn("Web Push refusé par le service (statut {}) — endpoint conservé.", status);
            return Result.FAILED;
        } catch (Exception e) {
            // Le transport ne remonte jamais d'exception : l'émission est best-effort, jamais
            // bloquante pour le tour. On ne journalise pas la charge (titre neutre de toute façon).
            log.warn("Échec d'émission Web Push : {}", e.getMessage());
            return Result.FAILED;
        }
    }

    /**
     * La notification chiffrable, avec les en-têtes {@code Urgency} et {@code TTL} (F-217 / SF-217-01, D2) :
     * sans eux, la bibliothèque n'envoie aucune urgence (« normal », différable en veille) et garde la
     * charge 28 jours.
     */
    static Notification notification(PushSubscription subscription, String payloadJson, PushDelivery delivery)
            throws Exception {
        PushDelivery effective = delivery == null ? PushDelivery.DEFAULT : delivery;
        return Notification.builder()
                .endpoint(subscription.getEndpoint())
                .userPublicKey(subscription.getP256dh())
                .userAuth(subscription.getAuth())
                .payload(payloadJson.getBytes(StandardCharsets.UTF_8))
                .urgency(Urgency.valueOf(effective.urgency().name()))
                .ttl(effective.ttlSeconds())
                .build();
    }
}
