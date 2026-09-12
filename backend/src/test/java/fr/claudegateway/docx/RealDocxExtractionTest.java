package fr.claudegateway.docx;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

/**
 * Le même extracteur, sur un <b>vrai</b> document Word (F-86 / SF-86-01).
 *
 * <p>Les autres tests fabriquent leurs archives : ils disent ce qu'ils éprouvent, mais ils
 * éprouvent le XML que le test a écrit. Celui-ci lit
 * {@code src/test/resources/docx/contrat-reel.docx}, produit par une suite bureautique — donc avec
 * ses vraies déclarations de namespaces, ses vraies propriétés de style entre les paragraphes, sa
 * vraie mise en tableau, son en-tête et son pied de page dans des parties séparées, sa note de bas
 * de page et son image dans {@code word/media/}. C'est le test qui empêche l'extraction de ne
 * marcher que sur des documents écrits par nous.
 */
class RealDocxExtractionTest {

    private final DocxTextExtractor extractor = new DocxTextExtractor(new DocxProperties(null, null, null));

    private static byte[] realDocument() throws IOException {
        try (InputStream stream = RealDocxExtractionTest.class
                .getResourceAsStream("/docx/contrat-reel.docx")) {
            assertThat(stream).as("le document de référence doit être présent dans les ressources").isNotNull();
            return stream.readAllBytes();
        }
    }

    @Test
    void readsRunningTextTableFootnoteAndImagesOfARealWordDocument() throws IOException {
        DocxExtraction extraction = extractor.extract(realDocument());
        String text = extraction.text();

        // Texte courant, dans l'ordre du document.
        assertThat(text).contains("Contrat de prestation de services.");
        assertThat(text).contains("Fait a Paris.");
        assertThat(text.indexOf("Contrat de prestation")).isLessThan(text.indexOf("Fait a Paris"));

        // Tableau : la relation ligne/colonne survit.
        assertThat(text).contains("| Prestation | Prix |");
        assertThat(text).contains("| --- | --- |");
        assertThat(text).contains("| Audit | 1200 EUR |");
        assertThat(text).contains("| Formation | 800 EUR |");

        // Note de bas de page : gardée, en fin de texte, et rattachée à sa place dans la phrase.
        assertThat(text).contains("Notes de bas de page");
        assertThat(text).contains("[1] Resiliable avec un preavis de trois mois.");
        assertThat(text).contains("effet au 1er janvier[1]");

        // En-tête et pied de page : jamais lus.
        assertThat(text).doesNotContain("EN-TETE-CONFIDENTIEL");
        assertThat(text).doesNotContain("PIED-DE-PAGE-REPETE");

        // Image : comptée, annoncée en tête, jamais lue.
        assertThat(extraction.ignoredImages()).isEqualTo(1);
        assertThat(text).startsWith("[1 image de ce document n'a pas été lue.]");
    }

    @Test
    void recognisesARealWordDocumentOnItsContent() throws IOException {
        assertThat(extractor.looksLikeDocx(realDocument())).isTrue();
    }
}
