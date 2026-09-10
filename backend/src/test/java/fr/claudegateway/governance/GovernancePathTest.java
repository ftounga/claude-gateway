package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * F-51 / SF-51-01 — le chemin d'un fichier apporté est la seule donnée d'un paquet qui décide
 * <b>où</b> quelque chose sera écrit sur la machine de quelqu'un d'autre. Ces tests fixent ce qui
 * passe et, surtout, ce qui ne passe pas.
 */
class GovernancePathTest {

    @Test
    @DisplayName("un chemin relatif ordinaire est conservé tel quel")
    void keepsRelativePath() {
        assertThat(GovernancePath.normalizeOrNull(".claude/skills/explique.md"))
                .isEqualTo(".claude/skills/explique.md");
    }

    @Test
    @DisplayName("les séparateurs Windows et les segments « . » sont normalisés")
    void normalizesSeparatorsAndDots() {
        assertThat(GovernancePath.normalizeOrNull("docs\\./notes//STATE.md"))
                .isEqualTo("docs/notes/STATE.md");
    }

    @ParameterizedTest
    @DisplayName("tout ce qui sort du projet, ou ne désigne rien, est refusé")
    @ValueSource(strings = {
        "../secrets.txt",          // traversée
        "a/../../b.md",            // traversée enfouie
        "/etc/passwd",             // racine POSIX
        "C:\\Windows\\notes.md",   // lecteur Windows
        "d:/data/x.md",            // lecteur Windows, minuscule
        "   ",                     // blanc
        "./",                      // ne désigne rien
        "//"                       // ne désigne rien
    })
    void rejectsDangerousPaths(String raw) {
        assertThat(GovernancePath.normalizeOrNull(raw)).isNull();
    }

    @Test
    @DisplayName("un chemin nul est refusé sans lever")
    void rejectsNull() {
        assertThat(GovernancePath.normalizeOrNull(null)).isNull();
    }

    @Test
    @DisplayName("un chemin plus long que la colonne qui le stocke est refusé")
    void rejectsTooLongPath() {
        String tooLong = "a".repeat(GovernancePath.MAX_LENGTH + 1);
        assertThat(GovernancePath.normalizeOrNull(tooLong)).isNull();
    }
}
