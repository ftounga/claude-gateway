package fr.claudegateway.jarvis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-240 / SF-240-01 — le comptage des mots dits (même règle que l'écran). */
class JarvisSpokenWordsTest {

    @Test
    @DisplayName("vide ou null : zéro mot")
    void empty() {
        assertThat(JarvisSpokenWords.count(null)).isZero();
        assertThat(JarvisSpokenWords.count("   ")).isZero();
    }

    @Test
    @DisplayName("la ponctuation isolée et les marques ne comptent pas ; « Monsieur » compte")
    void punctuationAndMarks() {
        assertThat(JarvisSpokenWords.count("🎙 Oui , Monsieur : c'est fait !")).isEqualTo(4);
        assertThat(JarvisSpokenWords.count("↪ — … ?")).isZero();
    }

    @Test
    @DisplayName("élision, trait d'union et apostrophe typographique ne coupent pas")
    void elisionAndHyphen() {
        assertThat(JarvisSpokenWords.count("l'exposition aujourd'hui dites-moi l’on-premise")).isEqualTo(4);
    }

    @Test
    @DisplayName("les chiffres sont des mots")
    void digits() {
        assertThat(JarvisSpokenWords.count("4 950 caractères le 01/11 à 18:44")).isEqualTo(7);
    }

    @Test
    @DisplayName("une ligne 🎙 réelle de prod (avant F-240) : 35 mots")
    void realLine() {
        assertThat(JarvisSpokenWords.count("Monsieur, tout le réseau tourne autour d'une transit gateway unique dans "
                + "AWS, reliée à l'on-premise CA-GIP par un Direct Connect, et l'on-premise ne répond toujours pas à "
                + "la plage ca-savings. Le détail est à l'écran.")).isEqualTo(35);
    }

    @Test
    @DisplayName("percentile au rang le plus proche ; null sans valeur")
    void percentile() {
        assertThat(JarvisSpokenWords.percentile(List.of(), 90)).isNull();
        assertThat(JarvisSpokenWords.percentile(List.of(10, 20, 30, 40, 50, 60, 70, 80, 90, 100), 90)).isEqualTo(90);
    }
}
