package fr.claudegateway.jarvis.companion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Ce que le téléphone dit, pur (F-232 / SF-232-03, D1b). */
class JarvisCompanionAnnouncementsTest {

    @Test
    void unMessagePrepareEstAnnonceAvecSonDestinataire() {
        String reply = "🎙 Le mail est prêt.\n\n```mail\nÀ : Paul Martin\nObjet : Relance\n\nBonjour Paul,\n```";

        assertThat(JarvisCompanionAnnouncements.turnText(reply)).isEqualTo("Le message pour Paul Martin est prêt.");
    }

    @Test
    void plusieursMessagesSontComptes() {
        String reply = "Voici.\n\n```mail\nÀ : Paul\nObjet : A\n\nx\n```\n\n```teams\nBonjour\n```";

        assertThat(JarvisCompanionAnnouncements.turnText(reply)).isEqualTo("2 messages sont prêts, dont celui pour Paul.");
    }

    @Test
    void sansMessageLaPhraseOraleEstDite() {
        assertThat(JarvisCompanionAnnouncements.turnText("🎙 Étape trois validée.\n\nDétails plus bas."))
                .isEqualTo("C'est fait : Étape trois validée.");
    }

    @Test
    void sansRienADireCEstFait() {
        assertThat(JarvisCompanionAnnouncements.turnText("")).isEqualTo("C'est fait.");
        assertThat(JarvisCompanionAnnouncements.turnText(null)).isEqualTo("C'est fait.");
    }

    @Test
    void leTexteEstCoupeA600Caracteres() {
        String cut = JarvisCompanionAnnouncements.cut("a".repeat(900));

        assertThat(cut).hasSize(600).endsWith("…");
    }

    @Test
    void seulUnCheminInterneEstGardeCommeLien() {
        assertThat(JarvisCompanionAnnouncements.safeLink("/jarvis?alerte=1")).isEqualTo("/jarvis?alerte=1");
        assertThat(JarvisCompanionAnnouncements.safeLink("https://ailleurs.example")).isEqualTo("/jarvis");
        assertThat(JarvisCompanionAnnouncements.safeLink("//ailleurs.example")).isEqualTo("/jarvis");
        assertThat(JarvisCompanionAnnouncements.safeLink("/a b")).isEqualTo("/jarvis");
        assertThat(JarvisCompanionAnnouncements.safeLink(null)).isEqualTo("/jarvis");
        assertThat(JarvisCompanionAnnouncements.safeLink("/" + "x".repeat(400))).isEqualTo("/jarvis");
    }

    @Test
    void codeEtNomSontNormalises() {
        assertThat(JarvisCompanionPairingService.normalizeCode(" abcd-efgh ")).isEqualTo("ABCDEFGH");
        assertThat(JarvisCompanionPairingService.normalizeName("  ")).isEqualTo("Téléphone Android");
        assertThat(JarvisCompanionPairingService.normalizeName("Pixel   8")).isEqualTo("Pixel 8");
        assertThat(JarvisCompanionPairingService.normalizeName("p".repeat(120))).hasSize(80);
    }
}
