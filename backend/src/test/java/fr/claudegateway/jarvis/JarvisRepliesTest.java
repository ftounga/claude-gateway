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
    void SF_234_13_laLigneMontrerNEstJamaisLeResume() {
        assertThat(JarvisReplies.oralLine("🖥 montrer: clients\nVoici vos trois clients.")).isEqualTo("Voici vos trois clients.");
    }
}
