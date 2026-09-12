package fr.claudegateway.docx;

import static fr.claudegateway.docx.DocxFixtures.archive;
import static fr.claudegateway.docx.DocxFixtures.archiveWithManyEntries;
import static fr.claudegateway.docx.DocxFixtures.archiveWithZeroFilledEntry;
import static fr.claudegateway.docx.DocxFixtures.docx;
import static fr.claudegateway.docx.DocxFixtures.paragraph;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Les deux risques réels du format {@code .docx} — c'est une <b>archive</b> contenant du
 * <b>XML</b> — éprouvés sur de vraies charges hostiles (F-86 / SF-86-01).
 *
 * <p>Les bornes utilisées ici sont volontairement minuscules : ce qu'on éprouve est le
 * <i>mécanisme</i>, pas la valeur de production. Une charge de plusieurs centaines de mégaoctets
 * dans un test rendrait la suite lente sans rien prouver de plus.
 */
class DocxSecurityTest {

    /** 4 entrées, 4 Kio par entrée, 8 Kio au total : les bornes de production en réduction. */
    private final DocxTextExtractor extractor =
            new DocxTextExtractor(new DocxProperties(4, 4096L, 8192L));

    private static final String TOO_BIG = "Ce document Word est trop volumineux une fois décompressé.";

    // ---------------------------------------------------------------- zip-bomb

    @Test
    void refusesAnEntryThatInflatesBeyondItsBound() {
        byte[] bomb = archiveWithZeroFilledEntry(DocxTextExtractor.DOCUMENT_PART, 4 * 1024 * 1024);

        assertThatThrownBy(() -> extractor.extract(bomb))
                .isInstanceOf(InvalidDocxException.class)
                .hasMessage(TOO_BIG);
    }

    @Test
    void refusesAnArchiveWithTooManyEntries() {
        byte[] bomb = archiveWithManyEntries(50);

        assertThatThrownBy(() -> extractor.extract(bomb))
                .isInstanceOf(InvalidDocxException.class)
                .hasMessage(TOO_BIG);
    }

    @Test
    void refusesAnArchiveWhoseTotalInflatedSizeExceedsTheBound() {
        // Chaque entrée reste sous la borne par entrée (4 Kio) ; c'est leur SOMME qui dépasse.
        // Sans borne totale, une bombe se cacherait en la répartissant.
        byte[] bomb = archive(Map.of(
                "word/a.xml", "a".repeat(3000),
                "word/b.xml", "b".repeat(3000),
                "word/c.xml", "c".repeat(3000)));

        assertThatThrownBy(() -> extractor.extract(bomb))
                .isInstanceOf(InvalidDocxException.class)
                .hasMessage(TOO_BIG);
    }

    @Test
    void countsBytesOfEntriesItDoesNotEvenKeep() {
        // L'entrée qui explose n'est PAS une des deux parties retenues : la traverser suffirait à
        // faire le travail de la bombe si ses octets ne comptaient pas.
        byte[] bomb = archiveWithZeroFilledEntry("word/media/image1.png", 4 * 1024 * 1024);

        assertThatThrownBy(() -> extractor.extract(bomb))
                .isInstanceOf(InvalidDocxException.class)
                .hasMessage(TOO_BIG);
    }

    @Test
    void stopsReadingAtTheBoundInsteadOfInflatingTheWholeEntry() {
        // Preuve par construction : l'archive est VALIDE sur ses premiers octets puis tronquée.
        // Une lecture qui irait jusqu'au bout heurterait la troncature et rendrait « corrompu » ;
        // rendre « trop volumineux » prouve qu'elle s'est arrêtée AVANT, à la borne.
        byte[] whole = archiveWithZeroFilledEntry(DocxTextExtractor.DOCUMENT_PART, 4 * 1024 * 1024);
        byte[] truncated = Arrays.copyOf(whole, 512);

        assertThatThrownBy(() -> extractor.extract(truncated))
                .isInstanceOf(InvalidDocxException.class)
                .hasMessage(TOO_BIG);
    }

    @Test
    void acceptsALegitimateDocumentWellUnderTheBounds() {
        assertThat(extractor.extract(docx(paragraph("Document ordinaire."))).text())
                .isEqualTo("Document ordinaire.");
    }

    // ---------------------------------------------------------------- entités XML externes

    @Test
    void refusesAnExternalEntityAndNeverReadsTheTargetFile(@TempDir Path tempDir) throws IOException {
        Path secret = tempDir.resolve("secret.txt");
        Files.writeString(secret, "CANARI-XXE-NE-DOIT-JAMAIS-APPARAITRE", StandardCharsets.UTF_8);

        byte[] hostile = archive(Map.of(DocxTextExtractor.DOCUMENT_PART, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE w:document [ <!ENTITY xxe SYSTEM "file://%s"> ]>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:body>
                </w:document>
                """.formatted(secret.toAbsolutePath())));

        assertThatThrownBy(() -> extractor.extract(hostile))
                .isInstanceOf(InvalidDocxException.class)
                .hasMessage("Ce document Word est illisible : son contenu interne est corrompu.")
                .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("CANARI"));
    }

    @Test
    void refusesAnExternalEntityPointingOutwardSoNoConnectionIsEverOpened() {
        // Une adresse volontairement injoignable : si le refus reposait sur l'échec réseau plutôt
        // que sur la configuration du parseur, ce test passerait pour la mauvaise raison — mais il
        // partirait aussi en timeout. Il rend immédiatement, parce que le DTD est refusé d'entrée.
        byte[] hostile = archive(Map.of(DocxTextExtractor.DOCUMENT_PART, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE w:document [ <!ENTITY xxe SYSTEM "http://192.0.2.1/collecte"> ]>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:body>
                </w:document>
                """));

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> extractor.extract(hostile)).isInstanceOf(InvalidDocxException.class);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(2_000);
    }

    @Test
    void refusesRecursiveEntitiesWithoutExpandingThem() {
        // « Milliard de rires » : sans DTD refusé, l'expansion sature la mémoire du pod.
        byte[] hostile = archive(Map.of(DocxTextExtractor.DOCUMENT_PART, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE w:document [
                  <!ENTITY a "aaaaaaaaaa">
                  <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
                  <!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">
                  <!ENTITY d "&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;">
                ]>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>&d;</w:t></w:r></w:p></w:body>
                </w:document>
                """));

        assertThatThrownBy(() -> extractor.extract(hostile))
                .isInstanceOf(InvalidDocxException.class)
                .hasMessage("Ce document Word est illisible : son contenu interne est corrompu.");
    }
}
