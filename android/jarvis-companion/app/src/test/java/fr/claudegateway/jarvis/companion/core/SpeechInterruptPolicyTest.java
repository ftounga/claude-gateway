package fr.claudegateway.jarvis.companion.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Couper le compagnon en parlant (F-233 / SF-233-10). */
public class SpeechInterruptPolicyTest {

    @Test
    public void unMotPendantQuIlParleLeCoupeUneSeuleFois() {
        SpeechInterruptPolicy policy = new SpeechInterruptPolicy();
        policy.onUtteranceStart(1_000L);
        assertTrue(policy.shouldInterrupt(WakeEngine.MIN_WORD_FRAMES, 1_500L));
        assertFalse(policy.shouldInterrupt(WakeEngine.MIN_WORD_FRAMES, 1_600L));
    }

    @Test
    public void silencieuxUnMotTropCourtOuLEchoDuDebutNeCoupentPas() {
        SpeechInterruptPolicy policy = new SpeechInterruptPolicy();
        assertFalse(policy.shouldInterrupt(WakeEngine.MIN_WORD_FRAMES, 10L));
        policy.onUtteranceStart(1_000L);
        assertFalse(policy.shouldInterrupt(WakeEngine.MIN_WORD_FRAMES - 1, 2_000L));
        assertFalse(policy.shouldInterrupt(WakeEngine.MIN_WORD_FRAMES, 1_000L + SpeechInterruptPolicy.ATTACK_GRACE_MS - 1));
        assertTrue(policy.shouldInterrupt(WakeEngine.MIN_WORD_FRAMES, 1_000L + SpeechInterruptPolicy.ATTACK_GRACE_MS));
    }

    @Test
    public void apresLaFinDeLaParoleOnRepartDeZero() {
        SpeechInterruptPolicy policy = new SpeechInterruptPolicy();
        policy.onUtteranceStart(0L);
        assertTrue(policy.shouldInterrupt(WakeEngine.MIN_WORD_FRAMES, 500L));
        policy.onSpeakingEnd();
        assertFalse(policy.shouldInterrupt(WakeEngine.MIN_WORD_FRAMES, 600L));
        policy.onUtteranceStart(1_000L);
        assertTrue(policy.shouldInterrupt(WakeEngine.MIN_WORD_FRAMES, 1_400L));
    }
}
