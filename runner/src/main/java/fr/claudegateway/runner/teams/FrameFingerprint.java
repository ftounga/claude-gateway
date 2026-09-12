package fr.claudegateway.runner.teams;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * <b>L'empreinte perceptuelle d'une image</b> (F-90 / SF-90-01) — un {@code dHash} de 64 bits.
 *
 * <h2>Pourquoi une empreinte, alors qu'{@code ffmpeg} détecte déjà les changements de plan</h2>
 *
 * <p>Parce que le filtre de scène compare deux trames <b>successives</b> : une transition animée
 * franchit le seuil trois ou quatre fois de suite et donne une grappe de trames quasi identiques.
 * Sans dédoublonnage, une heure de réunion rend 150 images là où elle en vaut 40 — et
 * <b>chaque image se paie</b>. Le tri n'est pas une optimisation de confort : c'est ce qui rend la
 * fonctionnalité finançable.</p>
 *
 * <h2>Comment</h2>
 *
 * <p>L'image est ramenée à 9×8 en niveaux de gris, puis chaque pixel est comparé à son voisin de
 * droite : 8×8 = 64 comparaisons, 64 bits. Deux images dont la distance de Hamming est petite sont
 * <b>la même</b> image aux yeux d'un lecteur. C'est délibérément grossier — on ne cherche pas à
 * reconnaître une image, on cherche à savoir si elle <b>apprend quelque chose de nouveau</b>.</p>
 *
 * <p><b>Et c'est pour cela qu'un curseur qui bouge n'est pas un nouveau plan</b> : à 9×8, un curseur
 * ne pèse aucun bit.</p>
 *
 * <p>Aucune dépendance : {@code ImageIO} est dans le JDK.</p>
 */
public final class FrameFingerprint {

    /** Largeur de travail : 9 colonnes donnent 8 comparaisons par ligne. */
    private static final int WIDTH = 9;
    /** Hauteur de travail. */
    private static final int HEIGHT = 8;

    /**
     * <b>En deçà, c'est la même image.</b> Sur 64 bits, 6 bits d'écart, c'est un curseur, une
     * horloge, un pointeur de souris — pas une diapositive nouvelle. Constante <b>nommée</b> pour
     * qu'elle se règle sans se chercher.
     */
    public static final int SAME_IMAGE_DISTANCE = 6;

    private FrameFingerprint() {
    }

    /**
     * L'empreinte d'un fichier image.
     *
     * <p><b>Vide</b> quand le fichier n'est pas une image lisible — et vide, jamais une valeur de
     * repli : toute valeur du type {@code long} est une empreinte possible, si bien qu'un sentinelle
     * finirait un jour par se confondre avec une vraie image. Une image illisible est écartée
     * <b>et comptée</b> par {@link FrameSelection} ; un trou se voit, un trou silencieux ne se voit
     * jamais.</p>
     */
    public static java.util.OptionalLong of(Path file) {
        try {
            if (file == null || !Files.isRegularFile(file) || Files.size(file) == 0) {
                return java.util.OptionalLong.empty();
            }
            BufferedImage image = ImageIO.read(file.toFile());
            return image == null ? java.util.OptionalLong.empty()
                    : java.util.OptionalLong.of(of(image));
        } catch (IOException | RuntimeException e) {
            return java.util.OptionalLong.empty();
        }
    }

    /** L'empreinte d'une image déjà décodée. */
    public static long of(BufferedImage source) {
        int[][] gray = new int[HEIGHT][WIDTH];
        int sourceWidth = Math.max(1, source.getWidth());
        int sourceHeight = Math.max(1, source.getHeight());
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                // Échantillonnage au centre de chaque case : une moyenne de bloc serait plus fine,
                // mais le dHash n'a pas besoin de finesse — il a besoin d'être stable.
                int sx = Math.min(sourceWidth - 1, (int) ((x + 0.5) * sourceWidth / WIDTH));
                int sy = Math.min(sourceHeight - 1, (int) ((y + 0.5) * sourceHeight / HEIGHT));
                int rgb = source.getRGB(sx, sy);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                gray[y][x] = (r * 299 + g * 587 + b * 114) / 1000;
            }
        }
        long hash = 0L;
        int bit = 0;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH - 1; x++) {
                if (gray[y][x] > gray[y][x + 1]) {
                    hash |= 1L << bit;
                }
                bit++;
            }
        }
        return hash;
    }

    /** Nombre de bits qui diffèrent entre deux empreintes. */
    public static int distance(long left, long right) {
        return Long.bitCount(left ^ right);
    }

    /** Vrai si ces deux images n'apprennent pas deux choses différentes. */
    public static boolean sameImage(long left, long right) {
        return distance(left, right) <= SAME_IMAGE_DISTANCE;
    }
}
