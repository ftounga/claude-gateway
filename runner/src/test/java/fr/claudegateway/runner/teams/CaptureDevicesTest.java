package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.runner.OperatingSystem;

/** <b>Par où ce poste capture</b> (F-91 / SF-91-01). */
@DisplayName("F-91 / SF-91-01 — les entrées du poste")
class CaptureDevicesTest {

    @Test
    @DisplayName("Linux : l'affichage vient de DISPLAY, et le son de pulse")
    void linuxUsesDisplay() {
        CaptureDevices devices = CaptureDevices.resolve(OperatingSystem.LINUX, true, "", "", ":1");

        assertEquals(":1", devices.screen());
        assertEquals("default", devices.audio());
    }

    @Test
    @DisplayName("Linux sans DISPLAY : un défaut, plutôt qu'un refus — X est presque toujours :0.0")
    void linuxFallsBackToDefaultDisplay() {
        assertEquals(CaptureDevices.DEFAULT_DISPLAY,
                CaptureDevices.resolve(OperatingSystem.LINUX, true, "", "", "").screen());
    }

    @Test
    @DisplayName("Windows : le son N'EST PAS deviné — refus nommé, avec la commande qui le liste")
    void windowsAudioIsNeverGuessed() {
        CaptureRefusedException refused = assertThrows(CaptureRefusedException.class,
                () -> CaptureDevices.resolve(OperatingSystem.WINDOWS, true, "", "", ""));

        assertEquals(CaptureRefusedException.NO_DEVICE, refused.code());
        assertTrue(refused.remedy().contains("-list_devices"), refused.remedy());
        // Et le refus offre l'autre issue, explicitement : capturer sans son, en le sachant.
        assertTrue(refused.remedy().contains("« audio »: false"), refused.remedy());
    }

    @Test
    @DisplayName("Windows avec le nom donné : accepté tel quel")
    void windowsWithGivenDevice() {
        CaptureDevices devices = CaptureDevices.resolve(OperatingSystem.WINDOWS, true, "",
                "Microphone (Realtek)", "");

        assertEquals("desktop", devices.screen());
        assertEquals("Microphone (Realtek)", devices.audio());
    }

    @Test
    @DisplayName("sans son demandé : aucun refus, et l'absence de transcription est DITE")
    void mutedCaptureSaysWhatItCosts() {
        CaptureDevices devices = CaptureDevices.resolve(OperatingSystem.WINDOWS, false, "", "", "");

        assertFalse(devices.hasAudio());
        assertTrue(devices.describe().contains("aucune transcription"), devices.describe());
    }

    @Test
    @DisplayName("les entrées imposées dans la demande l'emportent sur les défauts")
    void explicitDevicesWin() {
        CaptureDevices devices = CaptureDevices.resolve(OperatingSystem.MACOS, true, "2", "1", "");

        assertEquals("2", devices.screen());
        assertEquals("1", devices.audio());
    }
}
