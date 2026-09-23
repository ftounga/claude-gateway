package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Détection d'un média lisible par le fournisseur (F-121 / SF-121-15) : extension supportée
 * <b>et</b> octets d'en-tête reconnus. Une extension seule ne suffit jamais.
 */
class AtelierMediaReadTest {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
    private static final byte[] GIF_MAGIC = {'G', 'I', 'F', '8', '9', 'a', 0, 0};
    private static final byte[] WEBP_MAGIC =
            {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'E', 'B', 'P', 0, 0};
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-', '1', '.', '7'};

    // ---------------------------------------------------------------- extension

    @Test
    void recognisesSupportedExtensionsRegardlessOfCase() {
        assertThat(AtelierMediaRead.fromExtension("a/b/c.PNG")).contains(AtelierMediaRead.Kind.PNG);
        assertThat(AtelierMediaRead.fromExtension("photo.jpg")).contains(AtelierMediaRead.Kind.JPEG);
        assertThat(AtelierMediaRead.fromExtension("photo.jpeg")).contains(AtelierMediaRead.Kind.JPEG);
        assertThat(AtelierMediaRead.fromExtension("anim.gif")).contains(AtelierMediaRead.Kind.GIF);
        assertThat(AtelierMediaRead.fromExtension("pic.webp")).contains(AtelierMediaRead.Kind.WEBP);
        assertThat(AtelierMediaRead.fromExtension("doc.pdf")).contains(AtelierMediaRead.Kind.PDF);
    }

    @Test
    void ignoresNonMediaAndPathsWithoutExtension() {
        assertThat(AtelierMediaRead.fromExtension("src/App.java")).isEmpty();
        assertThat(AtelierMediaRead.fromExtension("README")).isEmpty();
        assertThat(AtelierMediaRead.fromExtension(null)).isEmpty();
        assertThat(AtelierMediaRead.fromExtension("notes.txt")).isEmpty();
    }

    // ---------------------------------------------------------------- magic bytes

    @Test
    void confirmsEachTypeByItsHeaderBytes() {
        assertThat(AtelierMediaRead.matchesMagic(AtelierMediaRead.Kind.PNG, PNG_MAGIC)).isTrue();
        assertThat(AtelierMediaRead.matchesMagic(AtelierMediaRead.Kind.JPEG, JPEG_MAGIC)).isTrue();
        assertThat(AtelierMediaRead.matchesMagic(AtelierMediaRead.Kind.GIF, GIF_MAGIC)).isTrue();
        assertThat(AtelierMediaRead.matchesMagic(AtelierMediaRead.Kind.WEBP, WEBP_MAGIC)).isTrue();
        assertThat(AtelierMediaRead.matchesMagic(AtelierMediaRead.Kind.PDF, PDF_MAGIC)).isTrue();
    }

    @Test
    void rejectsHeaderBytesThatDoNotMatch() {
        byte[] text = "hello world".getBytes();
        assertThat(AtelierMediaRead.matchesMagic(AtelierMediaRead.Kind.PNG, text)).isFalse();
        assertThat(AtelierMediaRead.matchesMagic(AtelierMediaRead.Kind.PDF, text)).isFalse();
        // "RIFF" sans "WEBP" (ex. un WAV) n'est pas une image WebP.
        byte[] wav = {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'A', 'V', 'E'};
        assertThat(AtelierMediaRead.matchesMagic(AtelierMediaRead.Kind.WEBP, wav)).isFalse();
        assertThat(AtelierMediaRead.matchesMagic(AtelierMediaRead.Kind.PNG, new byte[] {1, 2})).isFalse();
    }

    // ---------------------------------------------------------------- detect (extension + magic)

    @Test
    void detectsOnlyWhenExtensionAndMagicAgree() {
        assertThat(AtelierMediaRead.detect("logo.png", PNG_MAGIC)).contains(AtelierMediaRead.Kind.PNG);
        // Extension trompeuse : un .png qui n'en est pas retombe sur la lecture texte (vide).
        assertThat(AtelierMediaRead.detect("logo.png", "pas une image".getBytes())).isEmpty();
        // Bon contenu, mauvaise extension : pas de sniff hors allowlist d'extension.
        assertThat(AtelierMediaRead.detect("logo.txt", PNG_MAGIC)).isEmpty();
    }

    // ---------------------------------------------------------------- bornes

    @Test
    void carriesStrictPerTypeSizeBounds() {
        assertThat(AtelierMediaRead.Kind.PNG.maxBytes()).isEqualTo(AtelierMediaRead.IMAGE_MAX_BYTES);
        assertThat(AtelierMediaRead.Kind.PDF.maxBytes()).isEqualTo(AtelierMediaRead.PDF_MAX_BYTES);
        assertThat(AtelierMediaRead.IMAGE_MAX_BYTES).isEqualTo(5L * 1024 * 1024);
        assertThat(AtelierMediaRead.PDF_MAX_BYTES).isEqualTo(10L * 1024 * 1024);
    }

    @Test
    void tellsDocumentsFromImages() {
        assertThat(AtelierMediaRead.Kind.PDF.isDocument()).isTrue();
        assertThat(AtelierMediaRead.Kind.PNG.isDocument()).isFalse();
        assertThat(AtelierMediaRead.Kind.WEBP.isDocument()).isFalse();
    }

    @Test
    void headTrimsToTheSniffWindow() {
        byte[] big = new byte[100];
        assertThat(AtelierMediaRead.head(big)).hasSize(AtelierMediaRead.MAGIC_SNIFF_BYTES);
        assertThat(AtelierMediaRead.head(new byte[] {1, 2, 3})).hasSize(3);
        assertThat(AtelierMediaRead.head(null)).isEmpty();
    }

    @Test
    void kindMediaTypesAreTheApiTypes() {
        assertThat(AtelierMediaRead.Kind.PNG.mediaType()).isEqualTo("image/png");
        assertThat(AtelierMediaRead.Kind.JPEG.mediaType()).isEqualTo("image/jpeg");
        assertThat(AtelierMediaRead.Kind.GIF.mediaType()).isEqualTo("image/gif");
        assertThat(AtelierMediaRead.Kind.WEBP.mediaType()).isEqualTo("image/webp");
        assertThat(AtelierMediaRead.Kind.PDF.mediaType()).isEqualTo("application/pdf");
    }

    @Test
    void detectExampleFromOptionalIsPresentForRealPng() {
        Optional<AtelierMediaRead.Kind> kind =
                AtelierMediaRead.detect("assets/diagram.png", AtelierMediaRead.head(PNG_MAGIC));
        assertThat(kind).contains(AtelierMediaRead.Kind.PNG);
    }
}
