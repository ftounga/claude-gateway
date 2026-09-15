package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.runner.OperatingSystem;

/** F-122 / SF-122-04 — chaque cas d'entreprise est nommé, avec sa marche à suivre. */
class VigieServiceDiagnosticTest {

    @Test
    @DisplayName("Chrome absent : CHROME_NOT_FOUND, nommé, avec la surcharge de chemin")
    void chromeNotFound() {
        VigieServiceDiagnostic.Diagnosis diagnosis =
                VigieServiceDiagnostic.fromChromeState(ManagedChrome.State.NO_BROWSER,
                        OperatingSystem.LINUX);

        assertEquals(VigieServiceDiagnostic.Fault.CHROME_NOT_FOUND, diagnosis.fault());
        assertTrue(diagnosis.isFault());
        assertFalse(diagnosis.title().isBlank());
        assertTrue(diagnosis.message().contains(ChromePaths.PATH_ENV), diagnosis.message());
        assertTrue(diagnosis.message().toLowerCase().contains("chrome"));
    }

    @Test
    @DisplayName("port qui ne s'ouvre pas : REMOTE_DEBUG_BLOCKED, la policy est nommée")
    void remoteDebugBlocked() {
        VigieServiceDiagnostic.Diagnosis diagnosis =
                VigieServiceDiagnostic.fromChromeState(ManagedChrome.State.UNREACHABLE,
                        OperatingSystem.WINDOWS);

        assertEquals(VigieServiceDiagnostic.Fault.REMOTE_DEBUG_BLOCKED, diagnosis.fault());
        assertTrue(diagnosis.message().toLowerCase().contains("policy"));
        assertFalse(diagnosis.message().isBlank());
    }

    @Test
    @DisplayName("états sains : aucun défaut")
    void healthyStatesHaveNoFault() {
        assertEquals(VigieServiceDiagnostic.Fault.NONE,
                VigieServiceDiagnostic.fromChromeState(ManagedChrome.State.REACHABLE,
                        OperatingSystem.MACOS).fault());
        assertFalse(VigieServiceDiagnostic.fromChromeState(ManagedChrome.State.LAUNCHED,
                OperatingSystem.LINUX).isFault());
    }

    @Test
    @DisplayName("proxy NTLM : PROXY_NTLM, nomme NTLM et renvoie à px")
    void proxyNtlm() {
        VigieServiceDiagnostic.Diagnosis diagnosis =
                VigieServiceDiagnostic.proxyNtlm(OperatingSystem.WINDOWS);

        assertEquals(VigieServiceDiagnostic.Fault.PROXY_NTLM, diagnosis.fault());
        assertTrue(diagnosis.message().toLowerCase().contains("ntlm"));
        assertTrue(diagnosis.message().contains("px"), diagnosis.message());
        assertTrue(diagnosis.message().contains("HTTPS_PROXY"), diagnosis.message());
    }

    @Test
    @DisplayName("les messages s'adaptent au système : PowerShell sous Windows, export ailleurs")
    void messagesAdaptToTheSystem() {
        String windows = VigieServiceDiagnostic
                .fromChromeState(ManagedChrome.State.NO_BROWSER, OperatingSystem.WINDOWS).message();
        String linux = VigieServiceDiagnostic
                .fromChromeState(ManagedChrome.State.NO_BROWSER, OperatingSystem.LINUX).message();

        assertTrue(windows.contains("$env:"), windows);
        assertTrue(linux.contains("export "), linux);
    }
}
