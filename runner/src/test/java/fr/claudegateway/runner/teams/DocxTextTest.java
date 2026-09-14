package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F-108 / SF-108-06 — <b>le texte d'un {@code .docx}</b> (la transcription Word), contre des documents
 * fabriqués : paragraphes, tabulations, sauts, entités, et le refus nommé d'un fichier qui n'en est pas
 * un.
 */
class DocxTextTest {

    @TempDir
    Path dir;

    private Path docx(String name, String documentXml) throws Exception {
        return zip(name, Map.of("word/document.xml", documentXml,
                "[Content_Types].xml", "<Types/>"));
    }

    private Path zip(String name, Map<String, String> entries) throws Exception {
        Path file = dir.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    @Test
    @DisplayName("Paragraphes, tabulation, saut et entités sont rendus tels quels")
    void extracts_paragraphs_tabs_and_entities() throws Exception {
        Path file = docx("transcription.docx", "<?xml version=\"1.0\"?>"
                + "<w:document xmlns:w=\"x\"><w:body>"
                + "<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr>"
                + "<w:r><w:t>Bonjour</w:t></w:r><w:r><w:tab/><w:t>le monde</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t xml:space=\"preserve\">Deuxi&#232;me &amp; derni&#232;re</w:t>"
                + "<w:br/><w:t>ligne</w:t></w:r></w:p>"
                + "</w:body></w:document>");

        DocxText.Extracted extracted = DocxText.read(file);

        assertEquals("Bonjour\tle monde\nDeuxième & dernière\nligne", extracted.text());
        assertEquals(2, extracted.paragraphs());
        assertFalse(extracted.truncated());
    }

    @Test
    @DisplayName("Un ZIP sans word/document.xml n'est pas un .docx lisible")
    void a_zip_without_document_xml_is_refused() throws Exception {
        Path file = zip("faux.docx", Map.of("autre.xml", "<x/>"));

        DocxText.NotADocx thrown = assertThrows(DocxText.NotADocx.class, () -> DocxText.read(file));
        assertTrue(thrown.getMessage().contains("word/document.xml"), thrown.getMessage());
    }

    @Test
    @DisplayName("Un fichier qui n'est pas un ZIP est refusé, jamais lu de travers")
    void a_non_zip_file_is_refused() throws Exception {
        Path file = dir.resolve("texte.docx");
        Files.writeString(file, "ceci n'est pas un docx");

        assertThrows(DocxText.NotADocx.class, () -> DocxText.read(file));
    }

    @Test
    @DisplayName("Un document plus long que le plafond est tronqué, et le dit")
    void a_long_document_is_truncated() throws Exception {
        String body = "a".repeat(DocxText.MAX_TEXT_CHARS + 5_000);
        Path file = docx("long.docx", "<w:document xmlns:w=\"x\"><w:body>"
                + "<w:p><w:r><w:t>" + body + "</w:t></w:r></w:p></w:body></w:document>");

        DocxText.Extracted extracted = DocxText.read(file);

        assertTrue(extracted.truncated());
        assertEquals(DocxText.MAX_TEXT_CHARS, extracted.text().length());
    }
}
