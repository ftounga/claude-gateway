package fr.claudegateway.atelier.gallery;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * <b>L'aperçu borné d'une image du poste</b> (F-278 / SF-278-01, cadrage D4) : réduite à {@value #MAX_SIDE} px au
 * plus grand côté, en JPEG (fond blanc sous la transparence), au plus {@value #MAX_BYTES} octets — qualité puis taille
 * dégradées jusqu'à tenir. Un redimensionnement, rien d'autre (aucune analyse d'image). Fonction pure.
 */
public final class GalleryImages {

    /** Plus grand côté d'un aperçu : une capture d'écran reste lisible en grand. */
    public static final int MAX_SIDE = 1600;
    /** Poids maximal d'un aperçu (≈ 400 Kio). */
    public static final int MAX_BYTES = 400 * 1024;
    /** Au-delà, on ne décode pas (protection mémoire) — plafond de lecture du runner hors médias. */
    public static final int MAX_SOURCE_BYTES = 10 * 1024 * 1024;
    /** Au-delà (en pixels), on ne décode pas : une image géante coûterait des centaines de Mo en mémoire. */
    static final long MAX_SOURCE_PIXELS = 60_000_000L;

    private static final float[] QUALITIES = {0.85f, 0.75f, 0.65f};

    /** L'aperçu et ses dimensions. */
    public record Preview(byte[] jpeg, int width, int height) {
    }

    private GalleryImages() {
    }

    /** L'aperçu, ou vide si l'image est illisible (format non pris en charge), vide ou trop lourde. */
    public static Optional<Preview> preview(byte[] source) {
        if (source == null || source.length == 0 || source.length > MAX_SOURCE_BYTES) {
            return Optional.empty();
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0
                    || (long) image.getWidth() * image.getHeight() > MAX_SOURCE_PIXELS) {
                return Optional.empty();
            }
            double scale = Math.min(1.0, MAX_SIDE / (double) Math.max(image.getWidth(), image.getHeight()));
            for (int attempt = 0; attempt < 4; attempt++) {
                BufferedImage scaled = scaled(image, scale);
                for (float quality : QUALITIES) {
                    byte[] jpeg = jpeg(scaled, quality);
                    if (jpeg.length <= MAX_BYTES) {
                        return Optional.of(new Preview(jpeg, scaled.getWidth(), scaled.getHeight()));
                    }
                }
                scale *= 0.75;
            }
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static BufferedImage scaled(BufferedImage image, double scale) {
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(image, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("Aucun encodeur JPEG.");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
