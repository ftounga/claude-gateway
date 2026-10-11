package fr.claudegateway.runner.video;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import javax.imageio.ImageIO;

import fr.claudegateway.runner.teams.SceneFrame;

/**
 * <b>La planche contact</b> (F-190 / SF-190-02, décision D4) : 16 vignettes horodatées par image.
 *
 * <p>Un humain qui découvre une vidéo ne regarde pas chaque image en grand : il survole, puis il
 * s'arrête. L'agent fait de même — quatre planches pour une heure de vidéo, puis l'image en pleine
 * taille de la case qui compte. Chaque image ouverte se paie ; la planche coûte à peu près une
 * image et en montre seize.</p>
 *
 * <p>Une planche fait <b>1536 × 864</b> : sous la limite au-delà de laquelle le fournisseur la
 * réduit, donc vue telle quelle. Elle se compose en Java seul (ImageIO), sans {@code ffmpeg} : rien
 * de plus à rapatrier.</p>
 */
final class ContactSheets {

    static final int COLUMNS = 4;
    static final int ROWS = 4;
    static final int PER_SHEET = COLUMNS * ROWS;
    static final int CELL_WIDTH = 384;
    static final int CELL_HEIGHT = 216;
    static final int SHEET_WIDTH = COLUMNS * CELL_WIDTH;
    static final int SHEET_HEIGHT = ROWS * CELL_HEIGHT;
    static final String SHEET_PREFIX = "planche-";

    /** Fond d'une case et du bandeau de repère. */
    static final Color BACKGROUND = new Color(0x1A, 0x1A, 0x1A);
    static final Color BANNER = new Color(0, 0, 0, 190);
    static final Color EMPTY = new Color(0x55, 0x55, 0x55);

    private ContactSheets() {
    }

    /**
     * Compose les planches des images retenues, dans l'ordre du temps.
     *
     * @param frames  images retenues, dans l'ordre du temps
     * @param dir     dossier du travail, où les planches sont écrites
     * @param display chemin tel que {@code read_file} le lira
     * @return les planches écrites, et ce qui n'a pas pu être fait — jamais une exception
     */
    static Result compose(List<SceneFrame> frames, Path dir, Function<Path, String> display) {
        List<VideoJob.Sheet> sheets = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        clear(dir);
        boolean[] textFailed = {false};
        int unreadable = 0;
        for (int start = 0, number = 1; start < frames.size(); start += PER_SHEET, number++) {
            List<SceneFrame> page = frames.subList(start, Math.min(frames.size(), start + PER_SHEET));
            BufferedImage sheet = new BufferedImage(SHEET_WIDTH, SHEET_HEIGHT,
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = sheet.createGraphics();
            List<VideoJob.Cell> cells = new ArrayList<>();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setColor(BACKGROUND);
                graphics.fillRect(0, 0, SHEET_WIDTH, SHEET_HEIGHT);
                for (int index = 0; index < page.size(); index++) {
                    SceneFrame frame = page.get(index);
                    int x = (index % COLUMNS) * CELL_WIDTH;
                    int y = (index / COLUMNS) * CELL_HEIGHT;
                    if (!drawFrame(graphics, frame.file(), x, y)) {
                        unreadable++;
                    }
                    label(graphics, index + 1, VideoJob.clock(frame.offsetSeconds()), x, y,
                            textFailed);
                    cells.add(new VideoJob.Cell(index + 1, frame.offsetSeconds(),
                            display.apply(frame.file())));
                }
            } finally {
                graphics.dispose();
            }
            Path file = dir.resolve(String.format(Locale.ROOT, "%s%02d.jpg", SHEET_PREFIX, number));
            try {
                if (!ImageIO.write(sheet, "jpg", file.toFile())) {
                    throw new IOException("aucun encodeur JPEG");
                }
                sheets.add(new VideoJob.Sheet(display.apply(file), cells));
            } catch (IOException | RuntimeException e) {
                gaps.add("planche " + number + " non écrite (" + e.getMessage()
                        + ") : ses images restent listées en pleine taille");
            }
        }
        if (unreadable > 0) {
            gaps.add(unreadable + " image" + (unreadable > 1 ? "s" : "")
                    + " illisible" + (unreadable > 1 ? "s" : "")
                    + " sur les planches (case grise) : ouvre l'image en pleine taille");
        }
        if (textFailed[0]) {
            gaps.add("repères des cases non dessinés (polices absentes sur ce poste) : la case n "
                    + "se lit de gauche à droite puis de haut en bas, voir l'index ci-dessous");
        }
        return new Result(sheets, gaps);
    }

    /** Pose l'image dans sa case, proportions gardées. Faux si l'image est illisible. */
    private static boolean drawFrame(Graphics2D graphics, Path file, int x, int y) {
        BufferedImage image;
        try {
            image = ImageIO.read(file.toFile());
        } catch (IOException | RuntimeException e) {
            image = null;
        }
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            graphics.setColor(EMPTY);
            graphics.fillRect(x + 2, y + 2, CELL_WIDTH - 4, CELL_HEIGHT - 4);
            return false;
        }
        double scale = Math.min((double) (CELL_WIDTH - 4) / image.getWidth(),
                (double) (CELL_HEIGHT - 4) / image.getHeight());
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        graphics.drawImage(image, x + (CELL_WIDTH - width) / 2, y + (CELL_HEIGHT - height) / 2,
                width, height, null);
        return true;
    }

    /**
     * Le repère de la case : numéro et instant. Le bandeau se dessine toujours ; le texte demande
     * des polices qu'une JVM minimale peut ne pas avoir — l'échec est alors retenu, dit une fois,
     * et la planche reste utile grâce à l'index.
     */
    private static void label(Graphics2D graphics, int number, String clock, int x, int y,
            boolean[] textFailed) {
        graphics.setColor(BANNER);
        graphics.fillRect(x, y, 118, 24);
        if (textFailed[0]) {
            return;
        }
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
            graphics.setColor(Color.WHITE);
            graphics.drawString(number + " · " + clock, x + 6, y + 17);
        } catch (Throwable unavailable) {
            // Polices absentes (headless sans fontconfig) : InternalError, UnsatisfiedLinkError…
            textFailed[0] = true;
        }
    }

    /** Les planches d'une composition précédente : une relance ne doit pas en laisser traîner. */
    private static void clear(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path path : stream.toList()) {
                if (path.getFileName().toString().startsWith(SHEET_PREFIX)) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException ignored) {
            // Une ancienne planche qui résiste sera écrasée si elle porte le même numéro.
        }
    }

    record Result(List<VideoJob.Sheet> sheets, List<String> gaps) {
    }
}
