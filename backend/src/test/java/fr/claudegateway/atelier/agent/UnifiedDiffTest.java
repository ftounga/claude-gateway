package fr.claudegateway.atelier.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests du calcul de diff unifié (F-37 / SF-37-01). Le format est écrit à la main : ces tests sont
 * la seule chose qui garantisse qu'il reste lisible et exact.
 */
class UnifiedDiffTest {

    private static final int NO_BOUND = 10_000;

    /** Octet nul : ce qui fait dire d'un contenu qu'il n'est pas du texte. */
    private static final String BINARY_MARKER = "\0";

    /** Diff d'un fichier modifié, sans borne gênante. */
    private FileDiff diff(String previous, String current) {
        return UnifiedDiff.between("src/app.ts", previous, current, NO_BOUND);
    }

    @Test
    @DisplayName("contenus identiques : aucun diff produit")
    void identicalContentProducesNothing() {
        FileDiff result = diff("a\nb\nc\n", "a\nb\nc\n");

        assertThat(result.diff()).isEmpty();
        assertThat(result.addedLines()).isZero();
        assertThat(result.removedLines()).isZero();
        assertThat(result.omittedLines()).isZero();
        assertThat(result.unreadable()).isFalse();
        assertThat(result.added()).isFalse();
    }

    @Test
    @DisplayName("une ligne modifiée : un retrait, un ajout, du contexte et un en-tête")
    void singleLineChangeIsRendered() {
        FileDiff result = diff("un\ndeux\ntrois\nquatre\n", "un\nDEUX\ntrois\nquatre\n");

        assertThat(result.diff().lines()).containsExactly(
                "@@ -1,4 +1,4 @@",
                " un",
                "-deux",
                "+DEUX",
                " trois",
                " quatre");
        assertThat(result.addedLines()).isEqualTo(1);
        assertThat(result.removedLines()).isEqualTo(1);
        assertThat(result.path()).isEqualTo("src/app.ts");
    }

    @Test
    @DisplayName("fichier nouveau : ajout intégral, marqué comme tel")
    void newFileIsRenderedAsFullAddition() {
        FileDiff result = UnifiedDiff.between("src/new.ts", null, "un\ndeux\n", NO_BOUND);

        assertThat(result.added()).isTrue();
        assertThat(result.diff().lines()).containsExactly("@@ -0,0 +1,2 @@", "+un", "+deux");
        assertThat(result.addedLines()).isEqualTo(2);
        assertThat(result.removedLines()).isZero();
    }

    @Test
    @DisplayName("fichier vidé : toutes les lignes en retrait")
    void emptiedFileIsRenderedAsFullRemoval() {
        FileDiff result = diff("un\ndeux\n", "");

        assertThat(result.diff().lines()).containsExactly("@@ -1,2 +0,0 @@", "-un", "-deux");
        assertThat(result.removedLines()).isEqualTo(2);
        assertThat(result.addedLines()).isZero();
    }

    @Test
    @DisplayName("ajout en fin de fichier : pas de contexte fantôme après la dernière ligne")
    void appendAtEndOfFile() {
        FileDiff result = diff("un\ndeux\n", "un\ndeux\ntrois\n");

        assertThat(result.diff().lines()).containsExactly("@@ -1,2 +1,3 @@", " un", " deux", "+trois");
        assertThat(result.addedLines()).isEqualTo(1);
    }

    @Test
    @DisplayName("le saut de ligne final n'invente pas de ligne vide")
    void trailingNewlineIsATerminator() {
        FileDiff result = diff("un\n", "un\ndeux\n");

        assertThat(result.diff()).doesNotContain("+\n");
        assertThat(result.addedLines()).isEqualTo(1);
    }

    @Test
    @DisplayName("deux changements éloignés : deux sections")
    void distantChangesProduceTwoSections() {
        StringBuilder previous = new StringBuilder();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            previous.append("ligne ").append(i).append('\n');
            current.append(i == 2 || i == 25 ? "CHANGÉ " + i + "\n" : "ligne " + i + "\n");
        }

        FileDiff result = diff(previous.toString(), current.toString());

        assertThat(result.diff().lines().filter(l -> l.startsWith("@@")).count()).isEqualTo(2);
        assertThat(result.addedLines()).isEqualTo(2);
        assertThat(result.removedLines()).isEqualTo(2);
    }

    @Test
    @DisplayName("deux changements proches : une seule section")
    void nearbyChangesAreMerged() {
        StringBuilder previous = new StringBuilder();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            previous.append("ligne ").append(i).append('\n');
            current.append(i == 10 || i == 12 ? "CHANGÉ " + i + "\n" : "ligne " + i + "\n");
        }

        FileDiff result = diff(previous.toString(), current.toString());

        assertThat(result.diff().lines().filter(l -> l.startsWith("@@")).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("diff plus long que la borne : tronqué sur une frontière de ligne, volume omis rapporté")
    void diffIsBoundedPerFile() {
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            current.append("ligne ").append(i).append('\n');
        }

        FileDiff result = UnifiedDiff.between("src/big.ts", null, current.toString(), 20);

        assertThat(result.diff().lines()).hasSize(20);
        assertThat(result.diff()).doesNotEndWith("\n");
        assertThat(result.omittedLines()).isEqualTo(201 - 20);
        // Les compteurs décrivent le diff RENDU : 20 lignes dont l'en-tête.
        assertThat(result.addedLines()).isEqualTo(19);
    }

    @Test
    @DisplayName("contenu binaire : signalé, jamais comparé, jamais d'exception")
    void binaryContentIsReportedNotCompared() {
        FileDiff result = diff("texte\n", "PK" + BINARY_MARKER + "quelque chose\n");

        assertThat(result.unreadable()).isTrue();
        assertThat(result.diff()).isEmpty();
        assertThat(result.addedLines()).isZero();
    }

    @Test
    @DisplayName("ancien contenu binaire : signalé aussi")
    void binaryPreviousContentIsReported() {
        FileDiff result = diff(BINARY_MARKER + "\n", "texte\n");

        assertThat(result.unreadable()).isTrue();
    }

    @Test
    @DisplayName("région remuée hors borne mémoire : repli en blocs, sans exception")
    void oversizedRegionFallsBackToBlocks() {
        // 1 500 lignes toutes différentes de part et d'autre : 1 501 × 1 501 > 2 000 000 cellules.
        StringBuilder previous = new StringBuilder();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < 1_500; i++) {
            previous.append("ancien ").append(i).append('\n');
            current.append("nouveau ").append(i).append('\n');
        }

        FileDiff result = UnifiedDiff.between("src/huge.ts", previous.toString(), current.toString(),
                NO_BOUND);

        assertThat(result.unreadable()).isFalse();
        assertThat(result.removedLines()).isEqualTo(1_500);
        assertThat(result.addedLines()).isEqualTo(1_500);
        assertThat(result.diff()).startsWith("@@ -1,1500 +1,1500 @@\n-ancien 0");
    }

    @Test
    @DisplayName("un gros fichier dont deux lignes changent reste comparable finement")
    void commonPrefixAndSuffixAreTrimmedBeforeComparing() {
        StringBuilder previous = new StringBuilder();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < 5_000; i++) {
            previous.append("ligne ").append(i).append('\n');
            current.append(i == 2_500 ? "CHANGÉ\n" : "ligne " + i + "\n");
        }

        FileDiff result = diff(previous.toString(), current.toString());

        assertThat(result.addedLines()).isEqualTo(1);
        assertThat(result.removedLines()).isEqualTo(1);
        assertThat(result.diff()).contains("@@ -2498,7 +2498,7 @@");
    }

    @Test
    @DisplayName("insertion pure au milieu : aucune ligne retirée")
    void pureInsertionRemovesNothing() {
        FileDiff result = diff("un\ndeux\ntrois\n", "un\ndeux\ndeux et demi\ntrois\n");

        assertThat(result.removedLines()).isZero();
        assertThat(result.addedLines()).isEqualTo(1);
        assertThat(result.diff()).contains("+deux et demi");
    }

    @Test
    @DisplayName("borne dégénérée : rien n'est rendu, tout est compté comme omis")
    void degenerateBoundRendersNothing() {
        FileDiff result = UnifiedDiff.between("src/app.ts", "un\n", "deux\n", 0);

        assertThat(result.diff()).isEmpty();
        assertThat(result.omittedLines()).isEqualTo(3);
    }
}
