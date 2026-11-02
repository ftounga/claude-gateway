package fr.claudegateway.jarvis.pieces;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-237 / SF-237-02 — lire un identifiant de pièce ; réduire une image en vignette. */
class PieceIdAndThumbnailsTest {

    static byte[] png(int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("identifiants : UUID avec ou sans tirets, version, rien d'autre")
    void parse() {
        UUID id = UUID.randomUUID();
        assertThat(PieceId.parse("proof:" + id.toString().replace("-", ""))).contains(new PieceId("proof", id, null));
        assertThat(PieceId.parse("video:" + id + "@v3")).contains(new PieceId("video", id, 3));
        assertThat(PieceId.parse("page:" + id + "@v12")).contains(new PieceId("page", id, 12));
        assertThat(PieceId.parse("../../etc/passwd")).isEmpty();
        assertThat(PieceId.parse("dep:" + id + "/x")).isEmpty();
        assertThat(PieceId.parse("autre:" + id)).isEmpty();
        assertThat(PieceId.parse("img:zz")).isEmpty();
        assertThat(PieceId.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("vignette : 320 px de large au plus, proportions gardées ; illisible ⇒ rien")
    void thumbnail() throws Exception {
        byte[] thumb = PieceThumbnails.of(png(1280, 640)).orElseThrow();
        BufferedImage read = ImageIO.read(new ByteArrayInputStream(thumb));
        assertThat(read.getWidth()).isEqualTo(320);
        assertThat(read.getHeight()).isEqualTo(160);
        BufferedImage small = ImageIO.read(new ByteArrayInputStream(PieceThumbnails.of(png(100, 50)).orElseThrow()));
        assertThat(small.getWidth()).isEqualTo(100);
        assertThat(PieceThumbnails.of("pas une image".getBytes())).isEmpty();
        assertThat(PieceThumbnails.of(new byte[0])).isEmpty();
        assertThat(PieceThumbnails.of(null)).isEmpty();
    }
}
