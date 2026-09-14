package fr.claudegateway.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import org.junit.jupiter.api.Test;

import fr.claudegateway.docx.DocxFixtures;
import fr.claudegateway.docx.DocxProperties;
import fr.claudegateway.docx.DocxTextExtractor;

/**
 * L'inspection du texte des documents joints (F-110 / SF-110-05) : un secret manifeste dans un {@code .docx}, un
 * {@code .xlsx} ou un PDF est trouvé ; ce qui n'est pas un document, ou n'est pas lisible, est laissé passer
 * (fail-open).
 */
class DocumentSecretScannerTest {

    private final DocumentSecretScanner scanner =
            new DocumentSecretScanner(new DocxTextExtractor(new DocxProperties(null, null, null)));

    // ---------------------------------------------------------------- .docx

    @Test
    void findsASecretInADocxParagraph() {
        byte[] docx = DocxFixtures.docx(DocxFixtures.paragraph("Accès prod — mot de passe : Hunter2024!"));
        assertThat(scanner.secretIn(docx)).contains("un mot de passe");
    }

    @Test
    void aDocxWithoutASecretIsClean() {
        byte[] docx = DocxFixtures.docx(DocxFixtures.paragraph("Compte rendu de la réunion du 14 septembre 2026."));
        assertThat(scanner.secretIn(docx)).isEmpty();
    }

    // ---------------------------------------------------------------- .xlsx

    @Test
    void findsASecretInAnXlsxSharedString() {
        byte[] xlsx = DocxFixtures.archive(Map.of(
                "xl/workbook.xml", "<workbook/>",
                "xl/sharedStrings.xml", "<sst><si><t>password: sup3rSecretValue</t></si></sst>"));
        assertThat(scanner.secretIn(xlsx)).contains("un mot de passe");
    }

    @Test
    void findsASecretInAnXlsxInlineStringOnAWorksheet() {
        byte[] xlsx = DocxFixtures.archive(Map.of(
                "xl/workbook.xml", "<workbook/>",
                "xl/worksheets/sheet1.xml",
                "<worksheet><sheetData><row><c t=\"inlineStr\"><is><t>AKIAIOSFODNN7EXAMPLE</t></is></c>"
                        + "</row></sheetData></worksheet>"));
        assertThat(scanner.secretIn(xlsx)).contains("une clé d'accès AWS");
    }

    @Test
    void anXlsxWithoutASecretIsClean() {
        byte[] xlsx = DocxFixtures.archive(Map.of(
                "xl/workbook.xml", "<workbook/>",
                "xl/sharedStrings.xml", "<sst><si><t>Chiffre d'affaires</t></si><si><t>2026</t></si></sst>"));
        assertThat(scanner.secretIn(xlsx)).isEmpty();
    }

    @Test
    void anOoxmlArchiveThatIsNeitherDocxNorXlsxIsNotInspected() {
        byte[] zip = DocxFixtures.archive(Map.of("random/part.xml", "<x>mot de passe : hunter2</x>"));
        assertThat(scanner.secretIn(zip)).isEmpty();
    }

    // ---------------------------------------------------------------- PDF

    @Test
    void findsASecretInAnUncompressedPdfContentStream() {
        byte[] pdf = pdf("BT (mot de passe : hunter2) Tj ET".getBytes(StandardCharsets.ISO_8859_1), false);
        assertThat(scanner.secretIn(pdf)).contains("un mot de passe");
    }

    @Test
    void findsASecretInAFlateDecodedPdfContentStream() {
        byte[] pdf = pdf("BT (AKIAIOSFODNN7EXAMPLE) Tj ET".getBytes(StandardCharsets.ISO_8859_1), true);
        assertThat(scanner.secretIn(pdf)).contains("une clé d'accès AWS");
    }

    @Test
    void findsASecretInAPdfHexString() {
        // « password=hunter2 » en hexadécimal, montré par un opérateur de texte.
        String hex = toHex("password=hunter2");
        byte[] pdf = pdf(("BT <" + hex + "> Tj ET").getBytes(StandardCharsets.ISO_8859_1), false);
        assertThat(scanner.secretIn(pdf)).contains("un mot de passe");
    }

    @Test
    void aPdfWithoutASecretIsClean() {
        byte[] pdf = pdf("BT (Compte rendu du 14 septembre) Tj ET".getBytes(StandardCharsets.ISO_8859_1), true);
        assertThat(scanner.secretIn(pdf)).isEmpty();
    }

    // ---------------------------------------------------------------- fail-open

    @Test
    void aPlainImageBinaryIsNotInspected() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0,
                'p', 'a', 's', 's', 'w', 'o', 'r', 'd', '=', 'h', 'u', 'n', 't', 'e', 'r', '2'};
        assertThat(scanner.secretIn(png)).isEmpty();
    }

    @Test
    void aCorruptZipIsNotInspected() {
        byte[] brokenZip = {0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4, 5, 6, 7, 8};
        assertThat(scanner.secretIn(brokenZip)).isEmpty();
    }

    @Test
    void nullOrEmptyIsClean() {
        assertThat(scanner.secretIn(null)).isEmpty();
        assertThat(scanner.secretIn(new byte[0])).isEmpty();
    }

    // ---------------------------------------------------------------- fabrique de PDF

    /** Un PDF minimal : un objet flux portant le contenu donné, éventuellement compressé en {@code FlateDecode}. */
    private static byte[] pdf(byte[] content, boolean flate) {
        byte[] body = flate ? zlib(content) : content;
        String dict = flate
                ? "<< /Filter /FlateDecode /Length " + body.length + " >>"
                : "<< /Length " + body.length + " >>";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "%PDF-1.4\n1 0 obj " + dict + "\nstream\n");
        out.writeBytes(body);
        writeAscii(out, "\nendstream\nendobj\n%%EOF");
        return out.toByteArray();
    }

    private static byte[] zlib(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(out, new Deflater(Deflater.DEFAULT_COMPRESSION))) {
            deflater.write(data);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static String toHex(String text) {
        StringBuilder hex = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.ISO_8859_1)) {
            hex.append(String.format("%02x", b & 0xFF));
        }
        return hex.toString();
    }
}
