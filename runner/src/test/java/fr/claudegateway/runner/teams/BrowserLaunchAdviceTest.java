package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import fr.claudegateway.runner.OperatingSystem;

/**
 * F-87 / SF-87-02 — <b>la leçon de F-80, tenue par un test</b>.
 *
 * <p>Le 2026-09-12, un message d'erreur nommait le remède — un magasin de confiance — sans donner le
 * moyen de le fabriquer, et il s'est révélé inutilisable. Ici, le remède est une <b>ligne de
 * commande complète</b>, et ce test vérifie qu'elle l'est vraiment : exécutable, port, dossier de
 * profil dédié, adresse de Teams. Il vérifie aussi la phrase qu'on oublierait le plus facilement —
 * <b>se connecter une fois</b> —, sans laquelle la commande « marche » et l'utilisateur tombe sur un
 * Teams déconnecté.</p>
 */
class BrowserLaunchAdviceTest {

    @ParameterizedTest
    @EnumSource(OperatingSystem.class)
    @DisplayName("Chaque système reçoit une commande complète, jamais un conseil vague")
    void every_system_gets_a_complete_command(OperatingSystem system) {
        String advice = BrowserLaunchAdvice.forSystem(system, 9222);

        assertTrue(advice.contains("--remote-debugging-port=9222"), advice);
        assertTrue(advice.contains("--user-data-dir"), advice);
        assertTrue(advice.contains(BrowserLaunchAdvice.TEAMS_URL), advice);
        assertTrue(advice.contains("profil par défaut"), advice);
        assertTrue(advice.toLowerCase(java.util.Locale.ROOT).contains("une fois"),
                "il faut dire qu'on se connecte UNE FOIS : " + advice);
    }

    @Test
    @DisplayName("Windows, macOS et Linux ont chacun leur exécutable, Chrome ET Edge")
    void names_the_real_executables() {
        String windows = BrowserLaunchAdvice.forSystem(OperatingSystem.WINDOWS, 9222);
        assertTrue(windows.contains("chrome.exe"), windows);
        assertTrue(windows.contains("msedge.exe"), windows);

        String macos = BrowserLaunchAdvice.forSystem(OperatingSystem.MACOS, 9222);
        assertTrue(macos.contains("/Applications/Google Chrome.app"), macos);
        assertTrue(macos.contains("Microsoft Edge.app"), macos);

        String linux = BrowserLaunchAdvice.forSystem(OperatingSystem.LINUX, 9222);
        assertTrue(linux.contains("google-chrome"), linux);
        assertTrue(linux.contains("microsoft-edge"), linux);
    }

    @Test
    @DisplayName("Le port réellement visé est celui écrit dans la commande")
    void uses_the_port_actually_probed() {
        assertTrue(BrowserLaunchAdvice.forSystem(OperatingSystem.LINUX, 9333)
                .contains("--remote-debugging-port=9333"));
    }

    @Test
    @DisplayName("Le message dit ce qui NE remonte pas — c'est la question que tout le monde pose")
    void says_what_never_leaves_the_machine() {
        String advice = BrowserLaunchAdvice.forSystem(OperatingSystem.LINUX, 9222);
        assertTrue(advice.contains("ni cookie, ni jeton"), advice);
    }

    @Test
    @DisplayName("Pas d'onglet Teams et pas de session sont deux remèdes différents")
    void two_different_remedies() {
        assertTrue(BrowserLaunchAdvice.openTeamsTab().contains("Ouvrez "));
        assertTrue(BrowserLaunchAdvice.openTeamsTab().contains("CETTE fenêtre"));
        assertTrue(BrowserLaunchAdvice.signIn().contains("Connectez-vous"));
        assertFalse(BrowserLaunchAdvice.signIn().contains("--remote-debugging-port"),
                "inutile de redonner la commande : le navigateur est déjà relié");
    }
}
