package fr.claudegateway.jarvis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** La phrase orale d'une réponse (F-231 / SF-231-06) : la ligne 🎙, sinon la première ligne de prose. */
class JarvisRepliesTest {

    @Test
    void laLigneOraleGagne() {
        assertThat(JarvisReplies.oralLine("Contexte d'abord.\n🎙 C'est fait : l'étape 3 est validée.\n\nDétail…"))
                .isEqualTo("C'est fait : l'étape 3 est validée.");
    }

    @Test
    void sinonLaPremiereLigneDeProseSansBalisage() {
        assertThat(JarvisReplies.oralLine("<<essentiel>>## **Le build passe.**<</essentiel>>\nSuite"))
                .isEqualTo("Le build passe.");
    }

    @Test
    void lesBlocsNeSontJamaisLus() {
        assertThat(JarvisReplies.oralLine("```mail\nObjet : x\n```\n")).isNull();
        assertThat(JarvisReplies.oralLine("  ")).isNull();
    }

    @Test
    void uneLigneTropLongueEstCoupee() {
        assertThat(JarvisReplies.oralLine("a".repeat(400))).hasSize(JarvisReplies.MAX_LINE).endsWith("…");
    }

    @Test
    void SF_240_02_lInvitationCibleeNEstJamaisLaLigneOrale() {
        assertThat(JarvisReplies.oralLine("↪ Il y a aussi un point sur Helios, je vous le dis ?\n🎙 Le build passe."))
                .isEqualTo("Le build passe.");
        assertThat(JarvisReplies.oralLine("↪ Il y a aussi un point sur Helios, je vous le dis ?\nVoici le point."))
                .isEqualTo("Voici le point.");
    }

    @Test
    void SF_240_02_laConsigneOraleV2EstFigeeEtBorneeEnMots() {
        String text = JarvisOralNote.TEXT;
        assertThat(JarvisOralNote.VERSION).isEqualTo("2");
        assertThat(text).contains("UNE phrase, UNE idée, d'environ 25 mots").contains("30 au plus")
                .contains("jamais le chemin parcouru").contains("3 à 5 lignes courtes").contains("« ↪ »")
                .contains("Jamais d'invitation générique").contains("12 mots au plus").contains("« Vous confirmez ? »")
                .contains("email_me").contains("🖥 montrer: <filtre>").contains("« Monsieur »");
        // Aucun blanc en rafale (une ligne de texte bloc non jointe), rien de volatil.
        assertThat(text).doesNotContain("  ").doesNotContainPattern("\\d{1,2}:\\d{2}").doesNotContainPattern("20\\d\\d");
        assertThat(JarvisOralNote.appendTo("a")).isEqualTo(JarvisOralNote.appendTo("a"));
        assertThat(JarvisOralNote.appendTo("parole")).isEqualTo("parole\n\n" + text);
    }

    @Test
    void SF_234_13_laLigneMontrerNEstJamaisLeResume() {
        assertThat(JarvisReplies.oralLine("🖥 montrer: clients\nVoici vos trois clients.")).isEqualTo("Voici vos trois clients.");
    }
}
