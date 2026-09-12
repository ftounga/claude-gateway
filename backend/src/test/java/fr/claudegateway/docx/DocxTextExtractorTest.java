package fr.claudegateway.docx;

import static fr.claudegateway.docx.DocxFixtures.archive;
import static fr.claudegateway.docx.DocxFixtures.docx;
import static fr.claudegateway.docx.DocxFixtures.paragraph;
import static fr.claudegateway.docx.DocxFixtures.table;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Ce que l'extraction Word rend, et ce qu'elle refuse (F-86 / SF-86-01).
 *
 * <p>Les trois décisions de contenu du cadrage sont éprouvées ici : tableaux en Markdown, images
 * ignorées <b>et dites</b>, en-têtes et pieds de page dehors mais notes de bas de page dedans.
 */
class DocxTextExtractorTest {

    private final DocxTextExtractor extractor = new DocxTextExtractor(new DocxProperties(null, null, null));

    @Test
    void extractsRunningTextInDocumentOrder() {
        byte[] content = docx(paragraph("Premier paragraphe.")
                + paragraph("Deuxième paragraphe.")
                + paragraph("Troisième paragraphe."));

        DocxExtraction extraction = extractor.extract(content);

        assertThat(extraction.text()).isEqualTo("""
                Premier paragraphe.
                Deuxième paragraphe.
                Troisième paragraphe.""");
        assertThat(extraction.ignoredImages()).isZero();
    }

    @Test
    void keepsLineBreaksAndTabsInsideAParagraph() {
        byte[] content = docx("<w:p><w:r><w:t>Avant</w:t><w:br/><w:t>Après</w:t>"
                + "<w:tab/><w:t>colonne</w:t></w:r></w:p>");

        assertThat(extractor.extract(content).text()).isEqualTo("Avant\nAprès\tcolonne");
    }

    @Test
    void collapsesRunsOfEmptyParagraphsIntoASingleBlankLine() {
        byte[] content = docx(paragraph("Titre") + "<w:p/><w:p/><w:p/>" + paragraph("Suite"));

        assertThat(extractor.extract(content).text()).isEqualTo("Titre\n\nSuite");
    }

    // ---------------------------------------------------------------- tableaux

    @Test
    void rendersATableInMarkdownSoTheRowColumnRelationSurvives() {
        byte[] content = docx(paragraph("Barème :")
                + table(new String[] { "Prestation", "Prix" },
                        new String[] { "Audit", "1 200 €" },
                        new String[] { "Formation", "800 €" }));

        String text = extractor.extract(content).text();

        assertThat(text).isEqualTo("""
                Barème :

                | Prestation | Prix |
                | --- | --- |
                | Audit | 1 200 € |
                | Formation | 800 € |""");
        // Ce que le Markdown préserve et qu'une mise à plat perdrait : « 800 € » est le prix de la
        // formation, et rien d'autre ne peut être lu de cette ligne.
        assertThat(text).contains("| Formation | 800 € |");
    }

    @Test
    void escapesAPipeInsideACellSoTheTableDoesNotBreak() {
        byte[] content = docx(table(new String[] { "Clé", "Valeur" },
                new String[] { "options", "a | b | c" }));

        String text = extractor.extract(content).text();

        assertThat(text).contains("| options | a \\| b \\| c |");
        // Toutes les lignes du tableau ont le même nombre de séparateurs non échappés.
        long cellBoundaries = Arrays.stream(text.split("\n"))
                .filter(line -> line.startsWith("|"))
                .map(line -> line.replace("\\|", ""))
                .mapToLong(line -> line.chars().filter(c -> c == '|').count())
                .distinct()
                .count();
        assertThat(cellBoundaries).isEqualTo(1);
    }

    @Test
    void padsShortRowsSoEveryRowHasTheSameNumberOfColumns() {
        byte[] content = docx(table(new String[] { "A", "B", "C" }, new String[] { "1" }));

        assertThat(extractor.extract(content).text()).isEqualTo("""
                | A | B | C |
                | --- | --- | --- |
                | 1 |  |  |""");
    }

    @Test
    void flattensANestedTableInsideItsCellRatherThanLosingIt() {
        String inner = table(new String[] { "x", "y" });
        byte[] content = docx("<w:tbl><w:tr><w:tc>" + paragraph("Détail") + inner
                + "</w:tc><w:tc>" + paragraph("Fin") + "</w:tc></w:tr></w:tbl>");

        String text = extractor.extract(content).text();

        assertThat(text).contains("Détail x / y");
        assertThat(text).contains("| Fin |");
    }

    // ---------------------------------------------------------------- images

    @Test
    void countsImagesAndSaysSoAtTheTopOfTheText() {
        byte[] content = docx(paragraph("Avant l'illustration")
                + "<w:p><w:r><w:drawing><w:inline><w:blip/></w:inline></w:drawing></w:r></w:p>"
                + "<w:p><w:r><w:pict><w:shape/></w:pict></w:r></w:p>"
                + paragraph("Après l'illustration"));

        DocxExtraction extraction = extractor.extract(content);

        assertThat(extraction.ignoredImages()).isEqualTo(2);
        assertThat(extraction.text())
                .startsWith("[2 images de ce document n'ont pas été lues.]")
                .contains("Avant l'illustration")
                .contains("Après l'illustration");
    }

    @Test
    void saysItInTheSingularForASingleImage() {
        byte[] content = docx("<w:p><w:r><w:drawing/></w:r></w:p>" + paragraph("Texte"));

        assertThat(extractor.extract(content).text())
                .startsWith("[1 image de ce document n'a pas été lue.]");
    }

    @Test
    void saysNothingWhenTheDocumentHasNoImage() {
        assertThat(extractor.extract(docx(paragraph("Texte seul"))).text())
                .isEqualTo("Texte seul");
    }

    // ---------------------------------------------------------------- notes, en-têtes, pieds

    @Test
    void keepsFootnotesAndDropsWordsTechnicalSeparators() {
        String footnotes = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:footnotes xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:footnote w:type="separator" w:id="-1">%s</w:footnote>
                  <w:footnote w:type="continuationSeparator" w:id="0">%s</w:footnote>
                  <w:footnote w:id="1">%s</w:footnote>
                  <w:footnote w:id="2">%s</w:footnote>
                </w:footnotes>
                """.formatted(
                paragraph("SEPARATEUR-TECHNIQUE"),
                paragraph("CONTINUATION-TECHNIQUE"),
                paragraph("Résiliable avec un préavis de trois mois."),
                paragraph("Hors taxes."));

        byte[] content = docx("<w:p><w:r><w:t>Le contrat prend effet au 1er janvier</w:t>"
                + "<w:footnoteReference w:id=\"1\"/><w:t>.</w:t></w:r></w:p>",
                Map.of(DocxTextExtractor.FOOTNOTES_PART, footnotes));

        String text = extractor.extract(content).text();

        assertThat(text).isEqualTo("""
                Le contrat prend effet au 1er janvier[1].

                Notes de bas de page
                [1] Résiliable avec un préavis de trois mois.
                [2] Hors taxes.""");
        assertThat(text).doesNotContain("SEPARATEUR-TECHNIQUE").doesNotContain("CONTINUATION-TECHNIQUE");
    }

    @Test
    void renumbersFootnotesInReadingOrderRatherThanEchoingWordsInternalIds() {
        // Word numérote en interne comme il veut : ici 7 puis 3. Rendre ces identifiants à
        // l'utilisateur afficherait « [7] » pour la première note du document.
        String footnotes = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:footnotes xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:footnote w:id="7">%s</w:footnote>
                  <w:footnote w:id="3">%s</w:footnote>
                </w:footnotes>
                """.formatted(paragraph("Première note."), paragraph("Seconde note."));

        byte[] content = docx("<w:p><w:r><w:t>Deux renvois</w:t>"
                + "<w:footnoteReference w:id=\"3\"/><w:footnoteReference w:id=\"7\"/></w:r></w:p>",
                Map.of(DocxTextExtractor.FOOTNOTES_PART, footnotes));

        assertThat(extractor.extract(content).text()).isEqualTo("""
                Deux renvois[2][1]

                Notes de bas de page
                [1] Première note.
                [2] Seconde note.""");
    }

    @Test
    void neverReadsHeadersOrFooters() {
        String header = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">%s</w:hdr>
                """.formatted(paragraph("CONFIDENTIEL — Cabinet Untel — page 1 sur 40"));
        String footer = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">%s</w:ftr>
                """.formatted(paragraph("PIED-DE-PAGE-REPETE"));

        byte[] content = docx(paragraph("Corps du document."),
                Map.of("word/header1.xml", header, "word/footer1.xml", footer));

        assertThat(extractor.extract(content).text()).isEqualTo("Corps du document.");
    }

    @Test
    void doesNotResurrectTextDeletedUnderTrackedChanges() {
        byte[] content = docx("<w:p><w:r><w:t>Gardé.</w:t></w:r>"
                + "<w:del><w:r><w:delText>SUPPRIME</w:delText></w:r></w:del></w:p>");

        assertThat(extractor.extract(content).text()).isEqualTo("Gardé.");
    }

    // ---------------------------------------------------------------- refus

    @Test
    void refusesAFileThatIsNotAnArchive() {
        byte[] renamed = "Ceci est un simple fichier texte renommé en .docx.".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> extractor.extract(renamed))
                .isInstanceOf(InvalidDocxException.class)
                .hasMessage("Ce fichier n'est pas un document Word (.docx) :"
                        + " son contenu n'est pas une archive Office.");
    }

    @Test
    void refusesAZipArchiveThatIsNotAWordDocument() {
        // Une archive parfaitement valide — mais pas un .docx. Le refus porte sur le CONTENU
        // (absence de word/document.xml), jamais sur le nom qu'on lui aurait donné.
        byte[] zip = archive(Map.of("notes.txt", "bonjour", "images/logo.svg", "<svg/>"));

        assertThatThrownBy(() -> extractor.extract(zip))
                .isInstanceOf(InvalidDocxException.class)
                .hasMessage("Ce fichier n'est pas un document Word (.docx) valide.");
    }

    @Test
    void refusesATruncatedArchiveWithoutLeakingATechnicalTrace() {
        byte[] whole = docx(paragraph("Un document parfaitement valide, puis coupé en deux."));
        byte[] truncated = Arrays.copyOf(whole, whole.length / 2);

        assertThatThrownBy(() -> extractor.extract(truncated))
                .isInstanceOf(InvalidDocxException.class)
                .hasMessage("Ce document Word est illisible : le fichier est corrompu ou incomplet.")
                .satisfies(thrown -> assertThat(thrown.getMessage())
                        .doesNotContain("Exception")
                        .doesNotContain("java.")
                        .doesNotContain("fr.claudegateway"));
    }

    @Test
    void refusesADocumentWhoseXmlIsMalformed() {
        byte[] content = archive(Map.of(DocxTextExtractor.DOCUMENT_PART,
                "<w:document><w:body><w:p>pas de fermeture"));

        assertThatThrownBy(() -> extractor.extract(content))
                .isInstanceOf(InvalidDocxException.class)
                .hasMessage("Ce document Word est illisible : son contenu interne est corrompu.");
    }

    @Test
    void refusesAnEmptyContent() {
        assertThatThrownBy(() -> extractor.extract(new byte[0]))
                .isInstanceOf(InvalidDocxException.class)
                .hasMessage("Ce fichier est vide.");
        assertThatThrownBy(() -> extractor.extract(null))
                .isInstanceOf(InvalidDocxException.class)
                .hasMessage("Ce fichier est vide.");
    }

    // ---------------------------------------------------------------- reconnaissance sur contenu

    @Test
    void recognisesADocxOnItsContentAlone() {
        assertThat(extractor.looksLikeDocx(docx(paragraph("Bonjour")))).isTrue();
        assertThat(extractor.looksLikeDocx(archive(Map.of("notes.txt", "bonjour")))).isFalse();
        assertThat(extractor.looksLikeDocx("PDF? non".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(extractor.looksLikeDocx(new byte[0])).isFalse();
        assertThat(extractor.looksLikeDocx(null)).isFalse();
    }
}
