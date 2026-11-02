package fr.claudegateway.jarvis.pieces;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

import javax.imageio.ImageIO;

/**
 * <b>Une vignette</b> (F-237 / SF-237-02, cadrage §4.3) : l'image réduite à {@value #MAX_WIDTH} px de large, en PNG —
 * un redimensionnement, rien d'autre (aucune analyse d'image). Une image illisible (format inconnu) ne donne rien :
 * l'écran charge alors l'original.
 */
public final class PieceThumbnails {

    /** Largeur d'une vignette de la galerie. */
    public static final int MAX_WIDTH = 320;
    /** Au-delà, on ne décode pas (protection mémoire). */
    static final int MAX_SOURCE_BYTES = 25 * 1024 * 1024;

    private PieceThumbnails() {
    }

    /** La vignette PNG, ou vide si l'image est illisible ou trop lourde. */
    public static Optional<byte[]> of(byte[] source) {
        if (source == null || source.length == 0 || source.length > MAX_SOURCE_BYTES) {
            return Optional.empty();
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return Optional.empty();
            }
            int width = Math.min(MAX_WIDTH, image.getWidth());
            int height = Math.max(1, (int) Math.round(image.getHeight() * (width / (double) image.getWidth())));
            BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(image, 0, 0, width, height, null);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bytes);
            return Optional.of(bytes.toByteArray());
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
