package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.runner.OperatingSystem;

/** F-122 / SF-122-01 — où le runner trouve Chrome, et où il range son profil dédié. */
class ChromePathsTest {

    private static Function<String, String> env(Map<String, String> values) {
        return values::get;
    }

    private static Predicate<Path> onlyExisting(String existing) {
        return path -> path.toString().equals(existing);
    }

    @Test
    @DisplayName("macOS : le chemin standard de Google Chrome est détecté")
    void macos_standard_path() {
        String chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";

        Optional<Path> found = ChromePaths.executable(OperatingSystem.MACOS, name -> null,
                onlyExisting(chrome));

        assertEquals(Optional.of(Path.of(chrome)), found);
    }

    @Test
    @DisplayName("Windows : chrome.exe sous %ProgramFiles% est détecté")
    void windows_program_files_path() {
        String chrome = "C:\\PF\\Google\\Chrome\\Application\\chrome.exe";
        Function<String, String> env = env(Map.of("ProgramFiles", "C:\\PF"));

        Optional<Path> found = ChromePaths.executable(OperatingSystem.WINDOWS, env,
                onlyExisting(chrome));

        assertEquals(Optional.of(Path.of(chrome)), found);
    }

    @Test
    @DisplayName("Linux : google-chrome standard est détecté")
    void linux_standard_path() {
        Optional<Path> found = ChromePaths.executable(OperatingSystem.LINUX, name -> null,
                onlyExisting("/usr/bin/google-chrome"));

        assertEquals(Optional.of(Path.of("/usr/bin/google-chrome")), found);
    }

    @Test
    @DisplayName("Une surcharge de chemin est honorée telle quelle, même sans détection")
    void path_override_is_honoured() {
        Function<String, String> env = env(Map.of(ChromePaths.PATH_ENV, "/custom/my-chrome"));

        Optional<Path> found = ChromePaths.executable(OperatingSystem.LINUX, env, path -> false);

        assertEquals(Optional.of(Path.of("/custom/my-chrome")), found);
    }

    @Test
    @DisplayName("Aucun candidat existant, aucune surcharge : rien n'est rendu")
    void nothing_found() {
        Optional<Path> found = ChromePaths.executable(OperatingSystem.LINUX, name -> null,
                path -> false);

        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("Le dossier de profil est par OS et distinct du profil par défaut")
    void profile_dir_per_os() {
        Path linux = ChromePaths.profileDir(OperatingSystem.LINUX, env(Map.of("HOME", "/home/u")));
        Path macos = ChromePaths.profileDir(OperatingSystem.MACOS, env(Map.of("HOME", "/Users/u")));
        Path windows = ChromePaths.profileDir(OperatingSystem.WINDOWS,
                env(Map.of("LOCALAPPDATA", "C:\\L")));

        assertTrue(linux.toString().contains(".config/claude-gateway/chrome-teams"),
                linux.toString());
        assertTrue(macos.toString().contains("Library/Application Support/ClaudeGateway/chrome-teams"),
                macos.toString());
        assertTrue(windows.toString().contains("ClaudeGateway") && windows.toString().contains("chrome-teams"),
                windows.toString());
    }

    @Test
    @DisplayName("Une surcharge de dossier de profil est honorée")
    void profile_dir_override() {
        Path dir = ChromePaths.profileDir(OperatingSystem.LINUX,
                env(Map.of(ChromePaths.PROFILE_ENV, "/data/managed-profile")));

        assertEquals(Path.of("/data/managed-profile"), dir);
    }
}
