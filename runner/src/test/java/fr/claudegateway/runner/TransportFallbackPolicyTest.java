package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Tests de la décision de repli de transport (F-38 / SF-38-09).
 *
 * <p>Le cas qui compte n'est pas « le handshake est refusé » — celui-là se voit. C'est « l'upgrade
 * passe et le proxy coupe la socket aussitôt » : sans le critère de session trop courte, le runner
 * boucle en reconnexion sans jamais basculer, soit exactement le blocage que cette subfeature doit
 * supprimer.</p>
 */
class TransportFallbackPolicyTest {

    @Test
    void twoConsecutiveTransportFailuresTriggerTheFallback() {
        TransportFallbackPolicy policy = new TransportFallbackPolicy(RunnerConfig.Transport.AUTO);

        policy.recordTransportFailure();
        assertFalse(policy.shouldFallBack(), "un incident isolé ne doit pas changer de transport");

        policy.recordTransportFailure();
        assertTrue(policy.shouldFallBack());
    }

    @Test
    void aSocketThatDiesImmediatelyCountsAsATransportFailure() {
        TransportFallbackPolicy policy = new TransportFallbackPolicy(RunnerConfig.Transport.AUTO);

        policy.recordSessionEnded(Duration.ofSeconds(1));
        policy.recordSessionEnded(Duration.ofMillis(200));

        assertTrue(policy.shouldFallBack());
    }

    @Test
    void aSessionThatReallyLivedResetsTheCounter() {
        TransportFallbackPolicy policy = new TransportFallbackPolicy(RunnerConfig.Transport.AUTO);
        policy.recordTransportFailure();

        policy.recordSessionEnded(Duration.ofMinutes(3));

        assertFalse(policy.shouldFallBack());
        policy.recordTransportFailure();
        assertFalse(policy.shouldFallBack(), "le compteur doit être reparti de zéro");
    }

    @Test
    void theFallbackIsIrreversibleOnceEngaged() {
        TransportFallbackPolicy policy = new TransportFallbackPolicy(RunnerConfig.Transport.AUTO);
        policy.recordTransportFailure();
        policy.recordTransportFailure();
        assertTrue(policy.shouldFallBack());

        policy.recordSessionEnded(Duration.ofHours(1));

        assertTrue(policy.shouldFallBack(), "revenir au WebSocket à chaud n'est pas prévu (D-09.5)");
    }

    @Test
    void websocketModeNeverFallsBack() {
        TransportFallbackPolicy policy = new TransportFallbackPolicy(RunnerConfig.Transport.WEBSOCKET);

        for (int i = 0; i < 10; i++) {
            policy.recordTransportFailure();
        }

        // Un opérateur qui a exigé la socket doit voir l'échec, pas un contournement silencieux.
        assertFalse(policy.shouldFallBack());
        assertFalse(policy.startsWithPolling());
    }

    @Test
    void pollingModeStartsDirectlyOnTheFallback() {
        TransportFallbackPolicy policy = new TransportFallbackPolicy(RunnerConfig.Transport.POLLING);

        assertTrue(policy.startsWithPolling());
        assertFalse(policy.shouldFallBack(), "aucune bascule à décider : on n'a jamais tenté la socket");
    }
}
