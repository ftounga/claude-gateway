package fr.claudegateway.runner.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fr.claudegateway.runner.teams.SceneFrame;
import fr.claudegateway.runner.teams.VideoFixtures;

/** <b>La planche contact</b> (F-190 / SF-190-02) : découpage, dimensions, ordre, échecs nommés. */
class ContactSheetsTest {

    @TempDir
    Path dir;

    @Test
    void seize_images_font_une_planche_dix_sept_en_font_deux_soixante_en_font_quatre()
            throws IOException {
        assertEquals(1, compose(1).sheets().size());
        assertEquals(1, compose(16).sheets().size());
        assertEquals(2, compose(17).sheets().size());
        ContactSheets.Result sixty = compose(60);
        assertEquals(4, sixty.sheets().size());
        assertEquals(12, sixty.sheets().get(3).cells().size(), "60 = 16 + 16 + 16 + 12");
    }

    @Test
    void une_planche_fait_1536_sur_864_et_porte_les_images_dans_lordre_du_temps() throws IOException {
        ContactSheets.Result result = compose(5);

        VideoJob.Sheet sheet = result.sheets().get(0);
        BufferedImage image = ImageIO.read(dir.resolve(sheet.path()).toFile());
        assertEquals(ContactSheets.SHEET_WIDTH, image.getWidth());
        assertEquals(ContactSheets.SHEET_HEIGHT, image.getHeight());
        assertEquals(1536, image.getWidth());
        assertEquals(864, image.getHeight());
        List<VideoJob.Cell> cells = sheet.cells();
        for (int index = 0; index < cells.size(); index++) {
            assertEquals(index + 1, cells.get(index).index());
            assertEquals(index * 10d, cells.get(index).offsetSeconds());
        }
        assertEquals("frames/frame-00001.jpg", cells.get(0).framePath());
        // Une case posée n'est pas restée du fond sombre : le centre de la case 1 porte l'image.
        assertNotEquals(ContactSheets.BACKGROUND.getRGB(),
                image.getRGB(ContactSheets.CELL_WIDTH / 2, ContactSheets.CELL_HEIGHT / 2 + 20));
    }

    @Test
    void une_image_illisible_laisse_une_case_grise_et_un_manque_nomme() throws IOException {
        Path frames = Files.createDirectories(dir.resolve("frames"));
        VideoFixtures.writeDistinctFrames(frames, 2);
        Files.writeString(frames.resolve("frame-00002.jpg"), "pas une image");
        List<SceneFrame> list = List.of(
                new SceneFrame(0d, frames.resolve("frame-00001.jpg"), 0L),
                new SceneFrame(10d, frames.resolve("frame-00002.jpg"), 0L));

        ContactSheets.Result result = ContactSheets.compose(list, dir, path -> dir.relativize(path).toString());

        assertEquals(1, result.sheets().size(), "une case ratée ne fait pas tomber la planche");
        assertEquals(2, result.sheets().get(0).cells().size());
        assertTrue(result.gaps().stream().anyMatch(gap -> gap.contains("illisible")), result.gaps().toString());
        BufferedImage image = ImageIO.read(dir.resolve(result.sheets().get(0).path()).toFile());
        int grey = image.getRGB(ContactSheets.CELL_WIDTH + ContactSheets.CELL_WIDTH / 2,
                ContactSheets.CELL_HEIGHT / 2 + 20) & 0xFFFFFF;
        int red = (grey >> 16) & 0xFF;
        assertTrue(Math.abs(red - ContactSheets.EMPTY.getRed()) < 12, "case grise : " + red);
    }

    @Test
    void le_bandeau_de_repere_est_toujours_pose() throws IOException {
        ContactSheets.Result result = compose(1);
        BufferedImage image = ImageIO.read(dir.resolve(result.sheets().get(0).path()).toFile());
        // Coin du bandeau, à l'écart du texte : sombre, translucide sur l'image.
        int pixel = image.getRGB(112, 21) & 0xFFFFFF;
        assertTrue(((pixel >> 16) & 0xFF) < 90, "bandeau sombre attendu : " + Integer.toHexString(pixel));
    }

    @Test
    void une_relance_ne_laisse_pas_traîner_danciennes_planches() throws IOException {
        compose(40);
        assertEquals(3, countSheets());
        compose(5);
        assertEquals(1, countSheets());
    }

    private ContactSheets.Result compose(int count) throws IOException {
        Path frames = dir.resolve("frames");
        VideoFixtures.writeDistinctFrames(frames, count);
        List<SceneFrame> list = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            list.add(new SceneFrame((index - 1) * 10d,
                    frames.resolve(String.format("frame-%05d.jpg", index)), 0L));
        }
        return ContactSheets.compose(list, dir, path -> dir.relativize(path).toString());
    }

    private long countSheets() throws IOException {
        try (var files = Files.list(dir)) {
            return files.filter(path -> path.getFileName().toString().startsWith("planche-")).count();
        }
    }
}
