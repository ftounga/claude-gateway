package fr.claudegateway.atelier.gallery;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-278 / SF-278-01 — l'aperçu borné : réduit, JPEG, poids plafonné, transparence sur fond blanc. */
class GalleryImagesTest {

    static byte[] png(int width, int height, boolean noisy, boolean transparent) throws Exception {
        BufferedImage image = new BufferedImage(width, height,
                transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        if (noisy) {
            Random random = new Random(42);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    image.setRGB(x, y, random.nextInt(0xFFFFFF) | 0xFF000000);
                }
            }
        } else if (!transparent) {
            Graphics2D g = image.createGraphics();
            g.setColor(new Color(0x0B1020));
            g.fillRect(0, 0, width, height);
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("une grande capture est réduite à 1600 px de large, en JPEG")
    void largeImageIsScaledDown() throws Exception {
        GalleryImages.Preview preview = GalleryImages.preview(png(3000, 2000, false, false)).orElseThrow();
        assertThat(preview.width()).isEqualTo(1600);
        assertThat(preview.height()).isEqualTo(1067);
        assertThat(preview.jpeg()[0]).isEqualTo((byte) 0xFF);
        assertThat(preview.jpeg()[1]).isEqualTo((byte) 0xD8);
        assertThat(ImageIO.read(new ByteArrayInputStream(preview.jpeg())).getWidth()).isEqualTo(1600);
    }

    @Test
    @DisplayName("une petite image garde sa taille")
    void smallImageKeepsItsSize() throws Exception {
        GalleryImages.Preview preview = GalleryImages.preview(png(640, 480, false, false)).orElseThrow();
        assertThat(preview.width()).isEqualTo(640);
        assertThat(preview.height()).isEqualTo(480);
    }

    @Test
    @DisplayName("une image très chargée tient sous 400 Kio (qualité puis taille dégradées)")
    void noisyImageFitsTheCap() throws Exception {
        GalleryImages.Preview preview = GalleryImages.preview(png(1600, 1200, true, false)).orElseThrow();
        assertThat(preview.jpeg().length).isLessThanOrEqualTo(GalleryImages.MAX_BYTES);
    }

    @Test
    @DisplayName("la transparence devient du blanc")
    void transparencyBecomesWhite() throws Exception {
        GalleryImages.Preview preview = GalleryImages.preview(png(100, 100, false, true)).orElseThrow();
        int rgb = ImageIO.read(new ByteArrayInputStream(preview.jpeg())).getRGB(50, 50) & 0xFFFFFF;
        assertThat((rgb >> 16) & 0xFF).isGreaterThan(240);
    }

    @Test
    @DisplayName("des octets illisibles, vides ou trop lourds ne donnent rien")
    void unreadableGivesNothing() {
        assertThat(GalleryImages.preview("pas une image".getBytes())).isEmpty();
        assertThat(GalleryImages.preview(new byte[0])).isEmpty();
        assertThat(GalleryImages.preview(null)).isEmpty();
        assertThat(GalleryImages.preview(new byte[GalleryImages.MAX_SOURCE_BYTES + 1])).isEmpty();
    }
}
