package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** <b>L'empreinte perceptuelle</b> (F-90 / SF-90-01) : ce qui décide qu'une image est « la même ». */
class FrameFingerprintTest {

    @TempDir
    Path dir;

    @Test
    void deux_images_identiques_ont_la_meme_empreinte() {
        long left = FrameFingerprint.of(gradient(true));
        long right = FrameFingerprint.of(gradient(true));

        assertEquals(0, FrameFingerprint.distance(left, right));
        assertTrue(FrameFingerprint.sameImage(left, right));
    }

    @Test
    void un_curseur_qui_bouge_nest_pas_un_nouveau_plan() {
        BufferedImage before = gradient(true);
        BufferedImage after = gradient(true);
        Graphics2D graphics = after.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(31, 44, 3, 9);
        graphics.dispose();

        assertTrue(FrameFingerprint.sameImage(FrameFingerprint.of(before),
                FrameFingerprint.of(after)),
                "à 9×8 en niveaux de gris, un curseur ne pèse aucun bit");
    }

    @Test
    void deux_plans_differents_ont_des_empreintes_eloignees() {
        long left = FrameFingerprint.of(gradient(true));
        long right = FrameFingerprint.of(gradient(false));

        assertTrue(FrameFingerprint.distance(left, right) > FrameFingerprint.SAME_IMAGE_DISTANCE);
        assertFalse(FrameFingerprint.sameImage(left, right));
    }

    @Test
    void un_fichier_qui_nest_pas_une_image_ne_rend_aucune_empreinte() throws Exception {
        Path broken = dir.resolve("tronque.jpg");
        Files.writeString(broken, "�� pas un JPEG");

        assertEquals(OptionalLong.empty(), FrameFingerprint.of(broken));
        assertEquals(OptionalLong.empty(), FrameFingerprint.of(dir.resolve("absente.jpg")));
        assertEquals(OptionalLong.empty(), FrameFingerprint.of((Path) null));
    }

    /**
     * Un dégradé horizontal. Le {@code dHash} compare chaque pixel à son voisin de <b>droite</b> :
     * c'est donc la variation horizontale, et elle seule, qui porte les bits. Un aplat uniforme
     * rendrait la même empreinte quelle que soit sa couleur — ce qui est le comportement attendu
     * d'un {@code dHash}, et la raison pour laquelle ces images de test varient en x.
     */
    private static BufferedImage gradient(boolean rising) {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 64; x++) {
            int level = Math.min(255, x * 4);
            int gray = rising ? level : 255 - level;
            Color color = new Color(gray, gray, gray);
            for (int y = 0; y < 64; y++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        return image;
    }
}
