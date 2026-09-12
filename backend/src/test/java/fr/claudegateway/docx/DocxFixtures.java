package fr.claudegateway.docx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Fabrique des archives {@code .docx} — légitimes et hostiles — pour les tests de F-86.
 *
 * <p>Publique parce qu'elle sert aussi aux tests des chemins qui consomment l'extraction
 * (bibliothèque de documents, pièce jointe de conversation) : fabriquer deux fois les mêmes
 * archives hostiles, c'est risquer d'en durcir une et pas l'autre.
 *
 * <p>Les documents sont construits ici plutôt que déposés en ressources binaires : un test qui
 * montre le XML qu'il fabrique dit ce qu'il éprouve, alors qu'un fichier {@code .docx} opaque
 * oblige à le rouvrir dans Word pour savoir ce qu'il contenait.
 */
public final class DocxFixtures {

    private static final String DOCUMENT_HEADER = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""";

    private static final String DOCUMENT_FOOTER = "</w:body></w:document>";

    private DocxFixtures() {
    }

    /** Un {@code .docx} dont le corps est le XML donné (fragments de {@code w:body}). */
    public static byte[] docx(String bodyXml) {
        return archive(Map.of(DocxTextExtractor.DOCUMENT_PART, DOCUMENT_HEADER + bodyXml + DOCUMENT_FOOTER));
    }

    /** Un {@code .docx} avec un corps et des parties supplémentaires (notes, en-têtes, pieds). */
    public static byte[] docx(String bodyXml, Map<String, String> extraParts) {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put(DocxTextExtractor.DOCUMENT_PART, DOCUMENT_HEADER + bodyXml + DOCUMENT_FOOTER);
        parts.putAll(extraParts);
        return archive(parts);
    }

    /** Une archive zip quelconque : nom de partie → contenu texte. */
    public static byte[] archive(Map<String, String> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (Map.Entry<String, String> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }

    /** Une archive d'une seule entrée de {@code bytes} octets nuls : la charge d'une zip-bomb. */
    public static byte[] archiveWithZeroFilledEntry(String name, int bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(name));
            byte[] chunk = new byte[64 * 1024];
            int written = 0;
            while (written < bytes) {
                int size = Math.min(chunk.length, bytes - written);
                zip.write(chunk, 0, size);
                written += size;
            }
            zip.closeEntry();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }

    /** Une archive de {@code count} entrées minuscules : la charge d'une bombe « par le nombre ». */
    public static byte[] archiveWithManyEntries(int count) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < count; i++) {
                zip.putNextEntry(new ZipEntry("part-" + i + ".xml"));
                zip.write(new byte[] { 'x' });
                zip.closeEntry();
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }

    /** Un paragraphe de texte courant. */
    public static String paragraph(String text) {
        return "<w:p><w:r><w:t>" + text + "</w:t></w:r></w:p>";
    }

    /** Un tableau : chaque tableau de chaînes est une rangée. */
    public static String table(String[]... rows) {
        StringBuilder xml = new StringBuilder("<w:tbl>");
        for (String[] row : rows) {
            xml.append("<w:tr>");
            for (String cell : row) {
                xml.append("<w:tc>").append(paragraph(cell)).append("</w:tc>");
            }
            xml.append("</w:tr>");
        }
        return xml.append("</w:tbl>").toString();
    }
}
