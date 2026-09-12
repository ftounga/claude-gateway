package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>Le tri qui rend la fonctionnalité finançable</b> (F-90 / SF-90-01).
 *
 * <p>Ce qui est prouvé : une grappe de trames quasi identiques ne rend qu'<b>une</b> image, le
 * plafond garde les plus <b>espacées</b> et <b>dit</b> ce qu'il écarte, et une image illisible est
 * écartée <b>et comptée</b>.</p>
 */
class FrameSelectionTest {

    @TempDir
    Path dir;

    @Test
    void une_grappe_de_trames_quasi_identiques_ne_rend_quune_image() throws IOException {
        // Ce que fait une transition animée : le filtre de scène franchit son seuil trois fois de
        // suite sur trois images que personne ne distinguerait.
        List<SceneFrame> extracted = List.of(
                frame(0, slide(true, 0)),
                frame(1, slide(true, 1)),
                frame(2, slide(true, 2)),
                frame(30, slide(false, 0)));

        FramesHarvest harvest = FrameSelection.select(extracted, List.of());

        assertEquals(2, harvest.frames().size(), "trois quasi-identiques → une");
        assertEquals(2, harvest.duplicates());
        assertTrue(harvest.describe().contains("n'apprenaient rien de nouveau"));
    }

    @Test
    void une_diapositive_qui_revient_apres_un_detour_ressort() throws IOException {
        // Comparaison à la DERNIÈRE gardée, jamais à toutes : revenir sur un tableau est un moment.
        List<SceneFrame> extracted = List.of(
                frame(0, slide(true, 0)),
                frame(10, slide(false, 0)),
                frame(20, slide(true, 0)));

        FramesHarvest harvest = FrameSelection.select(extracted, List.of());

        assertEquals(3, harvest.frames().size());
        assertEquals(0, harvest.duplicates());
    }

    @Test
    void le_plafond_garde_les_plus_espacees_et_dit_combien_il_ecarte() throws IOException {
        List<SceneFrame> extracted = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            extracted.add(frame(index * 10, noise(index)));
        }

        FramesHarvest harvest = FrameSelection.select(extracted, List.of());

        assertEquals(FrameSelection.MAX_FRAMES, harvest.frames().size());
        assertEquals(200, harvest.examined(), "le compte examiné est celui d'avant tout tri");
        // Le compte écarté par le PLAFOND est ce qui reste après dédoublonnage : les deux tris se
        // déclarent séparément, pour qu'on sache lequel a mordu.
        assertEquals(200 - harvest.duplicates() - FrameSelection.MAX_FRAMES, harvest.overCap());
        assertEquals(0d, harvest.frames().get(0).offsetSeconds(), "la première est gardée");
        assertEquals(1990d, harvest.frames().get(harvest.frames().size() - 1).offsetSeconds(),
                "la dernière aussi : un compte rendu tronqué au début aurait l'air complet");
        assertTrue(harvest.gaps().stream()
                .anyMatch(gap -> gap.kind() == TeamsGapKind.CAP_REACHED));
        assertTrue(harvest.describe().contains("au-delà du plafond"));
    }

    @Test
    void une_image_illisible_est_ecartee_et_comptee() throws IOException {
        Path broken = dir.resolve("broken.jpg");
        Files.writeString(broken, "ceci n'est pas une image");
        List<SceneFrame> extracted = List.of(
                frame(0, slide(true, 0)),
                new SceneFrame(5, broken, 0L));

        FramesHarvest harvest = FrameSelection.select(extracted, List.of());

        assertEquals(1, harvest.frames().size());
        assertEquals(1, harvest.unreadable());
        assertTrue(harvest.gaps().stream()
                .anyMatch(gap -> gap.kind() == TeamsGapKind.FRAME_UNREADABLE));
        assertTrue(harvest.describe().contains("illisible"));
    }

    @Test
    void les_manques_deja_constates_en_amont_sont_repris_tels_quels() throws IOException {
        TeamsGap carried = TeamsGap.of(TeamsGapKind.FRAME_UNDATED, "extraction", "deux images");

        FramesHarvest harvest = FrameSelection.select(
                List.of(frame(0, slide(true, 0))), List.of(carried));

        assertTrue(harvest.gaps().contains(carried), "un manque amont ne se perd pas en route");
    }

    // ------------------------------------------------------------------ images de papier

    private SceneFrame frame(double offset, BufferedImage image) throws IOException {
        Path file = dir.resolve("frame-" + (int) offset + "-" + System.nanoTime() + ".jpg");
        ImageIO.write(image, "jpg", file.toFile());
        return new SceneFrame(offset, file, 0L);
    }

    /**
     * Une « diapositive » : un dégradé horizontal, plus un curseur de quelques pixels déplacé d'une
     * image à l'autre. Le {@code dHash} compare chaque pixel à son voisin de droite, donc c'est la
     * variation horizontale qui porte les bits — et le curseur, lui, ne doit PAS faire une image
     * nouvelle : c'est exactement la règle du cadrage.
     */
    private static BufferedImage slide(boolean rising, int cursorOffset) {
        BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 320; x++) {
            int level = Math.min(255, x * 255 / 319);
            int gray = rising ? level : 255 - level;
            int rgb = new Color(gray, gray, gray).getRGB();
            for (int y = 0; y < 180; y++) {
                image.setRGB(x, y, rgb);
            }
        }
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(10 + cursorOffset * 4, 40, 3, 8);
        graphics.dispose();
        return image;
    }

    /**
     * Une image franchement différente de la précédente : une dent de scie dont la position du
     * décrochement se déplace avec l'indice. Déterministe, et l'écart entre deux indices voisins
     * dépasse largement le seuil de « même image ».
     */
    private static BufferedImage noise(int index) {
        BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 320; x++) {
            int gray = (x * 3 + index * 29) % 256;
            int rgb = new Color(gray, gray, gray).getRGB();
            for (int y = 0; y < 180; y++) {
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }
}
