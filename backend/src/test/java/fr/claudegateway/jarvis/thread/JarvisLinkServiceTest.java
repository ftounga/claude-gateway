package fr.claudegateway.jarvis.thread;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.AtelierMessage;

/** F-245 / SF-245-03 — le rendu pur de la ligne-lien et de son rejeu (D5, D6). */
class JarvisLinkServiceTest {

    @Test
    void displayRunningWithAndWithoutChantier() {
        assertThat(JarvisLinkService.display("Helios", 3, "relancer le déploiement", AtelierMessage.LINK_RUNNING,
                null, false)).isEqualTo("↗ Helios · chantier 3 — « relancer le déploiement » · en cours");
        assertThat(JarvisLinkService.display("Helios", null, "logs", AtelierMessage.LINK_RUNNING, null, false))
                .isEqualTo("↗ Helios — « logs » · en cours");
    }

    @Test
    void doneCarriesTheOralLineOnlyWhenDone() {
        assertThat(JarvisLinkService.display("Helios", 3, "x", AtelierMessage.LINK_DONE, "C'est relancé.", false))
                .isEqualTo("↗ Helios · chantier 3 — « x » · fait\n🎙 C'est relancé.");
        assertThat(JarvisLinkService.display("Helios", 3, "x", AtelierMessage.LINK_FAILED, "ignorée", true))
                .isEqualTo("↗ Helios · chantier 3 — « x » · interrompu");
    }

    @Test
    void labelIsCleanedAndCut() {
        String label = "« a »\n" + "b".repeat(200);
        String line = JarvisLinkService.display("S", null, label, AtelierMessage.LINK_RUNNING, null, false);
        assertThat(line).doesNotContain("\n").contains("\" a \"").contains("…");
        assertThat(JarvisLinkService.labelOf(line).length()).isLessThanOrEqualTo(JarvisLinkService.MAX_LABEL);
    }

    @Test
    void labelAndNameReadBack() {
        String line = JarvisLinkService.display("Helios", 3, "relancer", AtelierMessage.LINK_RUNNING, null, false);
        assertThat(JarvisLinkService.labelOf(line)).isEqualTo("relancer");
        assertThat(JarvisLinkService.nameOf(line)).isEqualTo("Helios");
        assertThat(JarvisLinkService.nameOf(JarvisLinkService.display("Doc", null, "x", AtelierMessage.LINK_RUNNING,
                null, false))).isEqualTo("Doc");
    }

    @Test
    void replayIsAShortBracketedText() {
        assertThat(JarvisLinkService.replay("↗ Helios · chantier 3 — « relancer » · fait\n🎙 Relancé."))
                .isEqualTo("[Transmis à Helios · chantier 3 — « relancer » · fait — 🎙 Relancé.]");
    }
}
