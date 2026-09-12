package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>Le dossier de travail du volet</b> (F-90 / SF-90-01) : où vivent l'outillage et les images —
 * <b>sur la machine, et nulle part ailleurs</b>.
 */
class TeamsWorkFolderTest {

    @TempDir
    Path host;

    @Test
    void le_dossier_suit_la_convention_deja_posee_par_le_jeton() {
        TeamsWorkFolder folder = new TeamsWorkFolder(host);

        assertEquals(host.resolve(".claude-runner").resolve("teams"), folder.root());
        assertEquals(folder.root().resolve("tools"), folder.toolsDir());
    }

    @Test
    void sans_racine_inscriptible_on_se_replie_sur_le_dossier_du_compte() {
        TeamsWorkFolder folder = new TeamsWorkFolder(null);

        assertTrue(folder.root().toString()
                .endsWith(Path.of(".claude-runner", "teams").toString()));
        assertTrue(folder.root().isAbsolute());
    }

    @Test
    void un_nom_de_travail_est_un_dernier_segment_jamais_un_chemin() throws IOException {
        TeamsWorkFolder folder = new TeamsWorkFolder(host);

        Path dir = folder.workDir("../../evade/reunion 12");

        assertTrue(dir.normalize().startsWith(folder.root()),
                "aucun « .. » ne sort du dossier de travail");
        assertFalse(dir.toString().contains(".."));
        assertEquals("evadereunion12", dir.getFileName().toString());
    }

    @Test
    void un_nom_vide_ne_donne_jamais_un_dossier_sans_nom_de_dossier() {
        assertEquals("sans-nom", TeamsWorkFolder.safe("   "));
        assertEquals("sans-nom", TeamsWorkFolder.safe(null));
    }
}
