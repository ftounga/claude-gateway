package fr.claudegateway.jarvis.companion.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** F-232 / SF-232-10 — ouvrir l'écran Jarvis au mot, et ne le fermer qu'à bon escient. */
public class WakeLaunchPolicyTest {

    @Test
    public void directWhenTheOverlayRightIsGranted() {
        assertEquals(WakeLaunchPolicy.Launch.DIRECT, WakeLaunchPolicy.launch(true, true));
        assertEquals(WakeLaunchPolicy.Launch.DIRECT, WakeLaunchPolicy.launch(true, false));
    }

    @Test
    public void notificationOtherwise() {
        assertEquals(WakeLaunchPolicy.Launch.FULL_SCREEN, WakeLaunchPolicy.launch(false, true));
        assertEquals(WakeLaunchPolicy.Launch.BANNER, WakeLaunchPolicy.launch(false, false));
    }

    @Test
    public void fallbackOnlyWhenTheScreenDidNotOpen() {
        assertFalse(WakeLaunchPolicy.needsFallback(true));
        assertTrue(WakeLaunchPolicy.needsFallback(false));
    }

    @Test
    public void aKeyguardTransitionNeverClosesTheScreen() {
        long stoppedAt = 1_000L;
        assertFalse(WakeLaunchPolicy.closeWhileStopped(stoppedAt + 800, stoppedAt, false));
        assertFalse(WakeLaunchPolicy.closeWhileStopped(stoppedAt + WakeLaunchPolicy.STOPPED_CLOSE_MS - 1, stoppedAt,
                false));
        assertTrue(WakeLaunchPolicy.closeWhileStopped(stoppedAt + WakeLaunchPolicy.STOPPED_CLOSE_MS, stoppedAt, false));
    }

    @Test
    public void neverClosedWhileUnlockingOrWhenNotStopped() {
        assertFalse(WakeLaunchPolicy.closeWhileStopped(60_000L, 1_000L, true));
        assertFalse(WakeLaunchPolicy.closeWhileStopped(60_000L, 0L, false));
        assertFalse(WakeLaunchPolicy.closeOnLeave(true));
        assertTrue(WakeLaunchPolicy.closeOnLeave(false));
    }
}
