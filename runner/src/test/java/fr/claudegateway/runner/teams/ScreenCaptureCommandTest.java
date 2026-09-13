package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.runner.OperatingSystem;

/**
 * <b>La ligne de commande de la capture</b> (F-91 / SF-91-01).
 *
 * <p><b>Provenance, écrite et non maquillée</b> : les modules d'entrée ({@code x11grab},
 * {@code gdigrab}, {@code avfoundation}, {@code pulse}, {@code dshow}) viennent de la documentation
 * d'{@code ffmpeg}. Ils n'ont été éprouvés sur <b>aucun</b> poste réel — le CI n'a ni écran ni son.
 * Ce que ces tests prouvent est l'<b>assemblage</b> : le filigrane y est toujours, rien ne passe par
 * un shell, et un système inconnu est refusé au lieu d'être deviné.</p>
 */
@DisplayName("F-91 / SF-91-01 — la ligne de commande de la capture")
class ScreenCaptureCommandTest {

    private static final Path FFMPEG = Path.of("/usr/bin/ffmpeg");
    private static final Path OUTPUT = Path.of("/home/moi/.claude-runner/teams/captures/a1/capture.mp4");
    private static final String WATERMARK = "drawtext=fontfile=/f.ttf:text=Enregistrement local";

    @Test
    @DisplayName("Linux : x11grab et pulse, avec le filigrane")
    void linux() {
        List<String> command = ScreenCaptureCommand.build(FFMPEG, OperatingSystem.LINUX,
                new CaptureDevices(":0.0", "default"), WATERMARK, OUTPUT);

        assertTrue(consecutive(command, "-f", "x11grab"), command.toString());
        assertTrue(consecutive(command, "-i", ":0.0"), command.toString());
        assertTrue(consecutive(command, "-f", "pulse"), command.toString());
        assertTrue(consecutive(command, "-i", "default"), command.toString());
        assertTrue(consecutive(command, "-vf", WATERMARK), command.toString());
        assertEquals(OUTPUT.toString(), command.get(command.size() - 1));
    }

    @Test
    @DisplayName("Windows : gdigrab et dshow, le nom de périphérique préfixé « audio= »")
    void windows() {
        List<String> command = ScreenCaptureCommand.build(FFMPEG, OperatingSystem.WINDOWS,
                new CaptureDevices("desktop", "Microphone (Realtek)"), WATERMARK, OUTPUT);

        assertTrue(consecutive(command, "-f", "gdigrab"), command.toString());
        assertTrue(consecutive(command, "-i", "desktop"), command.toString());
        assertTrue(consecutive(command, "-f", "dshow"), command.toString());
        assertTrue(consecutive(command, "-i", "audio=Microphone (Realtek)"), command.toString());
    }

    @Test
    @DisplayName("macOS : une seule entrée « écran:son », comme avfoundation l'exige")
    void macos() {
        List<String> command = ScreenCaptureCommand.build(FFMPEG, OperatingSystem.MACOS,
                new CaptureDevices("1", "0"), WATERMARK, OUTPUT);

        assertTrue(consecutive(command, "-f", "avfoundation"), command.toString());
        assertTrue(consecutive(command, "-i", "1:0"), command.toString());
        assertEquals(1, count(command, "-i"), "avfoundation ne prend pas deux entrées");
    }

    @Test
    @DisplayName("macOS sans son : « 1:none », et aucun encodeur audio")
    void macosWithoutAudio() {
        List<String> command = ScreenCaptureCommand.build(FFMPEG, OperatingSystem.MACOS,
                new CaptureDevices("1", ""), WATERMARK, OUTPUT);

        assertTrue(consecutive(command, "-i", "1:none"), command.toString());
        assertFalse(command.contains("-c:a"), command.toString());
    }

    @Test
    @DisplayName("sans son : aucune entrée audio, aucun encodeur audio")
    void withoutAudio() {
        List<String> command = ScreenCaptureCommand.build(FFMPEG, OperatingSystem.LINUX,
                new CaptureDevices(":0.0", ""), WATERMARK, OUTPUT);

        assertFalse(command.contains("pulse"), command.toString());
        assertFalse(command.contains("-c:a"), command.toString());
    }

    @Test
    @DisplayName("PAS de -nostdin : c'est par l'entrée standard qu'on demandera l'arrêt propre")
    void keepsStdinOpen() {
        List<String> command = ScreenCaptureCommand.build(FFMPEG, OperatingSystem.LINUX,
                new CaptureDevices(":0.0", "default"), WATERMARK, OUTPUT);

        assertFalse(command.contains("-nostdin"), command.toString());
    }

    @Test
    @DisplayName("aucun argument n'est une ligne à découper : rien ne passe par un shell")
    void neverAShellLine() {
        List<String> command = ScreenCaptureCommand.build(FFMPEG, OperatingSystem.LINUX,
                new CaptureDevices(":0.0", "default"),
                "drawtext=text=moi\\; rm -rf /", OUTPUT);

        // Le filtre biscornu reste UN argument, jamais deux : le « ; » n'est interprété par personne.
        assertEquals(1, count(command, "drawtext=text=moi\\; rm -rf /"), command.toString());
        assertTrue(command.stream().noneMatch(argument -> argument.contains(" -i ")),
                command.toString());
    }

    @Test
    @DisplayName("sans filigrane, aucune commande ne s'assemble : c'est la règle, pas un défaut")
    void refusesWithoutWatermark() {
        assertThrows(IllegalArgumentException.class,
                () -> ScreenCaptureCommand.build(FFMPEG, OperatingSystem.LINUX,
                        new CaptureDevices(":0.0", "default"), "", OUTPUT));
    }

    @Test
    @DisplayName("système inconnu : REFUS nommé, avec ce qu'on peut faire à la place")
    void unknownSystemIsRefused() {
        CaptureRefusedException refused = assertThrows(CaptureRefusedException.class,
                () -> ScreenCaptureCommand.build(FFMPEG, OperatingSystem.OTHER,
                        new CaptureDevices("", ""), WATERMARK, OUTPUT));

        assertEquals(CaptureRefusedException.NO_DEVICE, refused.code());
        assertTrue(refused.remedy().contains("donnez-moi le chemin du fichier"), refused.remedy());
    }

    private static boolean consecutive(List<String> command, String first, String second) {
        for (int index = 0; index + 1 < command.size(); index++) {
            if (command.get(index).equals(first) && command.get(index + 1).equals(second)) {
                return true;
            }
        }
        return false;
    }

    private static long count(List<String> command, String value) {
        return command.stream().filter(value::equals).count();
    }
}
