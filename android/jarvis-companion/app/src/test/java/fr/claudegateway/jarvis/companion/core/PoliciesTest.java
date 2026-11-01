package fr.claudegateway.jarvis.companion.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PoliciesTest {

    @Test
    public void batterieAvecHysteresis() {
        BatteryPolicy policy = new BatteryPolicy();
        assertTrue(policy.update(50, false));
        assertTrue(policy.update(15, false));
        assertFalse(policy.update(14, false));
        assertTrue(policy.pausedForBattery());
        assertFalse("pas de reprise entre les deux seuils", policy.update(17, false));
        assertFalse(policy.update(19, false));
        assertTrue(policy.update(20, false));
        assertFalse(policy.update(10, false));
        assertTrue("en charge, on écoute", policy.update(10, true));
        assertTrue("niveau inconnu, on écoute", policy.update(-1, false));
    }

    @Test
    public void segmenteurIgnoreUnePhraseTropLongueEtLeBruit() {
        WordSegmenter segmenter = new WordSegmenter();
        for (int i = 0; i < 50; i++) {
            assertNull(segmenter.push(0.003));
        }
        long[] found = null;
        for (int i = 0; i < 200; i++) {
            found = segmenter.push(0.2);
            assertNull(found);
        }
        for (int i = 0; i < 40; i++) {
            found = found == null ? segmenter.push(0.0) : found;
        }
        assertNull("une phrase de 2 s n'est pas un mot", found);

        for (int i = 0; i < 50; i++) {
            segmenter.push(0.2);
        }
        found = null;
        for (int i = 0; i < 40 && found == null; i++) {
            found = segmenter.push(0.0);
        }
        assertEquals(50, found[1] - found[0]);
    }

    @Test
    public void adresseDeLaGateway() {
        assertEquals("https://portal.example.com", JarvisLink.normalizeGateway(" https://portal.example.com/ "));
        assertEquals("https://portal.example.com/sous", JarvisLink.normalizeGateway("https://portal.example.com/sous//"));
        assertNull(JarvisLink.normalizeGateway("http://portal.example.com"));
        assertEquals("http://10.0.2.2:4200", JarvisLink.normalizeGateway("http://10.0.2.2:4200"));
        assertNull(JarvisLink.normalizeGateway("portal.example.com"));
        assertNull(JarvisLink.normalizeGateway("https://portal.example.com/?x=1"));
        assertNull(JarvisLink.normalizeGateway("javascript:alert(1)"));
        assertNull(JarvisLink.normalizeGateway(null));
        assertEquals("https://portal.example.com/jarvis?source=compagnon",
                JarvisLink.jarvisUrl("https://portal.example.com"));
    }
}
