package fr.claudegateway.jarvis.companion.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** F-232 / SF-232-08 — l'écran Jarvis dans le compagnon. */
public class JarvisScreenPolicyTest {

    private static final String GW = "https://portal.ng-itconsulting.com";

    @Test
    public void screenUrlStartsTheConversation() {
        assertEquals("https://portal.ng-itconsulting.com/jarvis?source=compagnon&ecoute=1&compagnon=2",
                JarvisScreenPolicy.screenUrl(GW, 2));
    }

    @Test
    public void sameOriginOnly() {
        assertTrue(JarvisScreenPolicy.sameOrigin(GW, GW + "/jarvis?source=compagnon"));
        assertTrue(JarvisScreenPolicy.sameOrigin(GW, "https://PORTAL.ng-itconsulting.com:443/api/jarvis/speech"));
        assertFalse(JarvisScreenPolicy.sameOrigin(GW, "http://portal.ng-itconsulting.com/jarvis"));
        assertFalse(JarvisScreenPolicy.sameOrigin(GW, "https://portal.ng-itconsulting.com.evil.fr/jarvis"));
        assertFalse(JarvisScreenPolicy.sameOrigin(GW, "https://portal.ng-itconsulting.com:8443/jarvis"));
        assertFalse(JarvisScreenPolicy.sameOrigin(GW, "https://accounts.google.com/o/oauth2"));
        assertFalse(JarvisScreenPolicy.sameOrigin(GW, "https://x@portal.ng-itconsulting.com/jarvis"));
        assertFalse(JarvisScreenPolicy.sameOrigin(GW, "javascript:alert(1)"));
        assertFalse(JarvisScreenPolicy.sameOrigin(GW, null));
        assertFalse(JarvisScreenPolicy.sameOrigin(null, GW));
        assertTrue(JarvisScreenPolicy.sameOrigin("http://10.0.2.2:8080", "http://10.0.2.2:8080/jarvis"));
    }

    @Test
    public void renewalBeforeExpiry() {
        long now = 1_000_000L;
        assertTrue(JarvisScreenPolicy.needsRenewal(now, 0));
        assertTrue(JarvisScreenPolicy.needsRenewal(now, now + 60_000));
        assertFalse(JarvisScreenPolicy.needsRenewal(now, now + 20 * 60_000));
    }

    @Test
    public void idleClose() {
        long now = 10_000_000L;
        assertFalse(JarvisScreenPolicy.idleExpired(now, now - 60_000));
        assertTrue(JarvisScreenPolicy.idleExpired(now, now - JarvisScreenPolicy.IDLE_CLOSE_MS));
    }

    @Test
    public void unlockCallbackCarriesOnlyABoolean() {
        assertEquals("window.jarvisCompanionUnlocked&&window.jarvisCompanionUnlocked(true)",
                JarvisScreenPolicy.unlockCallback(true));
        assertEquals("window.jarvisCompanionUnlocked&&window.jarvisCompanionUnlocked(false)",
                JarvisScreenPolicy.unlockCallback(false));
    }
}
