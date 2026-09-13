package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.runner.OperatingSystem;

/**
 * <b>Le filigrane</b> (F-91 / SF-91-01, garde-fou n° 1) : ce qui empêche un enregistrement d'être
 * anonyme.
 *
 * <p>Deux choses sont vérifiées ici, et elles n'ont rien de cosmétique. La première : le filigrane
 * <b>nomme la personne</b> — un filigrane qui dirait seulement « enregistré » raterait tout son
 * objet. La seconde : un nom d'utilisateur biscornu ne <b>casse pas</b> le graphe de filtres, faute
 * de quoi la capture ne marcherait pas « chez ce client-là » sans que personne comprenne pourquoi.</p>
 */
@DisplayName("F-91 / SF-91-01 — le filigrane")
class WatermarkTest {

    private static final Instant AT = Instant.parse("2026-09-13T14:32:00Z");

    @Test
    @DisplayName("la ligne incrustée nomme QUI, QUAND, et que c'est un enregistrement local")
    void lineNamesWhoWhenWhat() {
        String line = new Watermark("francky@poste-42", AT, CapturePurpose.MEETING_WITH_OTHERS)
                .line();

        assertTrue(line.contains("francky@poste-42"), line);
        assertTrue(line.contains("13/09/2026 14:32"), line);
        assertTrue(line.contains("Enregistrement local"), line);
        assertTrue(line.contains("Claude Gateway"), line);
    }

    @Test
    @DisplayName("le filigrane distingue les deux usages")
    void kindIsWritten() {
        assertTrue(new Watermark("moi", AT, CapturePurpose.MEETING_WITH_OTHERS).line()
                .contains("(réunion)"));
        assertTrue(new Watermark("moi", AT, CapturePurpose.SELF_SCREEN).line()
                .contains("(écran)"));
    }

    @Test
    @DisplayName("une identité vide devient « poste inconnu » plutôt qu'un filigrane vide")
    void identityNeverEmpty() {
        assertTrue(new Watermark("  ", AT, CapturePurpose.SELF_SCREEN).line()
                .contains("poste inconnu"));
    }

    @Test
    @DisplayName("la mention du compte rendu dit que Teams n'a averti personne")
    void mentionSaysTeamsDidNotWarn() {
        String mention = new Watermark("francky", AT, CapturePurpose.MEETING_WITH_OTHERS).mention();

        assertTrue(mention.contains("ENREGISTREMENT LOCAL"), mention);
        assertTrue(mention.contains("francky"), mention);
        assertTrue(mention.contains("n'en ont pas été avertis par Teams"), mention);
    }

    @Test
    @DisplayName("un « : » ou une apostrophe dans le nom n'ouvre pas une seconde option du filtre")
    void filterGraphIsEscaped() {
        String filter = new Watermark("DOMAINE:l'utilisateur", AT, CapturePurpose.SELF_SCREEN)
                .drawtextFilter(Path.of("/usr/share/fonts/DejaVuSans.ttf"));

        assertTrue(filter.contains("\\:l\\'utilisateur"), filter);
        // Le « : » du nom ne doit PAS être lu comme le séparateur d'options de drawtext : après
        // échappement, le seul « : » non échappé restant est celui des options du filtre.
        assertFalse(filter.contains("DOMAINE:l"), filter);
    }

    @Test
    @DisplayName("les séparateurs du graphe sont échappés, pas seulement les deux-points")
    void escapesGraphSeparators() {
        assertEquals("a\\,b\\;c\\[d\\]e\\=f\\%g\\\\h",
                Watermark.escape("a,b;c[d]e=f%g\\h"));
    }

    @Test
    @DisplayName("le chemin de la police passe en séparateurs avant : c'est la forme qu'ffmpeg lit")
    void fontPathUsesForwardSlashes() {
        String filter = new Watermark("moi", AT, CapturePurpose.SELF_SCREEN)
                .drawtextFilter(Path.of("/usr/share/fonts/DejaVuSans.ttf"));

        assertTrue(filter.contains("fontfile=/usr/share/fonts/DejaVuSans.ttf"), filter);
    }

    @Test
    @DisplayName("sans police, on ne fabrique pas un filigrane : le type dit la règle")
    void noFontNoFilter() {
        assertThrows(IllegalArgumentException.class,
                () -> new Watermark("moi", AT, CapturePurpose.SELF_SCREEN).drawtextFilter(null));
    }

    @Test
    @DisplayName("aucune police connue sur ce poste : rend null, ce qui FERA refuser la capture")
    void noFontFoundYieldsNull() {
        WatermarkFont fonts = new WatermarkFont(OperatingSystem.LINUX, path -> false);

        assertNull(fonts.find());
        assertTrue(fonts.remedy().contains("fonts-dejavu"), fonts.remedy());
    }

    @Test
    @DisplayName("la première police présente est retenue, dans l'ordre de la liste")
    void firstPresentFontWins() {
        List<String> candidates = WatermarkFont.LINUX_CANDIDATES;
        Path wanted = Path.of(candidates.get(1));
        WatermarkFont fonts = new WatermarkFont(OperatingSystem.LINUX, wanted::equals);

        assertEquals(wanted, fonts.find());
    }

    @Test
    @DisplayName("un système sans emplacement connu ne trouve rien, et le dit avec un remède")
    void unknownSystemHasNoCandidates() {
        WatermarkFont fonts = new WatermarkFont(OperatingSystem.OTHER, path -> true);

        assertNull(fonts.find());
        assertFalse(fonts.remedy().isBlank());
    }
}
