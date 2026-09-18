package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Fonctions pures de la capture d'onglet (F-128 / SF-128-02) — testables sans navigateur. */
class MeetingTabCaptureTest {

    @Test
    @DisplayName("réassemble les tranches dans l'ordre")
    void reassembleConcatenatesInOrder() {
        assertEquals("abcdef", MeetingTabCapture.reassemble(List.of("ab", "cd", "ef")));
        assertEquals("", MeetingTabCapture.reassemble(List.of()));
    }

    @Test
    @DisplayName("décode le base64 (aller-retour) et tolère l'illisible")
    void decodeRoundTrip() {
        byte[] original = "webm-opus".getBytes(StandardCharsets.UTF_8);
        String base64 = Base64.getEncoder().encodeToString(original);
        assertArrayEquals(original, MeetingTabCapture.decode(base64));
        assertEquals(0, MeetingTabCapture.decode("").length);
        assertEquals(0, MeetingTabCapture.decode("!!not-base64!!").length);
    }

    @Test
    @DisplayName("le script de lecture porte le décalage et la longueur")
    void pullScriptCarriesOffsetAndLength() {
        String script = MeetingTabCapture.pullScript(4096, 2048);
        assertTrue(script.contains("4096"));
        assertTrue(script.contains("2048"));
        assertTrue(script.contains("__cgMeetingCapture"));
    }

    @Test
    @DisplayName("le script de démarrage capte l'onglet + le micro et enregistre")
    void startScriptUsesTheRightApis() {
        assertTrue(MeetingTabCapture.START_SCRIPT.contains("getDisplayMedia"));
        assertTrue(MeetingTabCapture.START_SCRIPT.contains("preferCurrentTab"));
        assertTrue(MeetingTabCapture.START_SCRIPT.contains("getUserMedia"));
        assertTrue(MeetingTabCapture.START_SCRIPT.contains("MediaRecorder"));
    }

    @Test
    @DisplayName("le script d'arrêt encode le média en base64")
    void stopScriptEncodesBase64() {
        assertTrue(MeetingTabCapture.STOP_SCRIPT.contains("btoa"));
        assertTrue(MeetingTabCapture.STOP_SCRIPT.contains("stopped"));
    }

    @Test
    @DisplayName("SF-128-03 : le script échantillonne les images clés (canvas/vidéo)")
    void startScriptSamplesFrames() {
        assertTrue(MeetingTabCapture.START_SCRIPT.contains("drawImage"));
        assertTrue(MeetingTabCapture.START_SCRIPT.contains("toDataURL"));
        assertTrue(MeetingTabCapture.START_SCRIPT.contains("setInterval"));
        assertTrue(MeetingTabCapture.START_SCRIPT.contains("frames"));
    }

    @Test
    @DisplayName("SF-128-03 : scripts de lecture des images clés (compte + tranche)")
    void frameScriptsCarryIndexAndOffset() {
        assertTrue(MeetingTabCapture.framesCountScript().contains("frames"));
        String pull = MeetingTabCapture.framePullScript(2, 100, 50);
        assertTrue(pull.contains("2"));
        assertTrue(pull.contains("100"));
        assertTrue(pull.contains("50"));
    }

    @Test
    @DisplayName("SF-128-03 : retrait du préfixe data URL")
    void stripDataUrlRemovesPrefix() {
        assertEquals("AAAB", MeetingTabCapture.stripDataUrl("data:image/jpeg;base64,AAAB"));
        assertEquals("AAAB", MeetingTabCapture.stripDataUrl("AAAB"));
        assertEquals("", MeetingTabCapture.stripDataUrl(null));
    }
}
