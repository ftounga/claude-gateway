package fr.claudegateway.runner.video.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Le lecteur {@code .tar.gz} de la chaîne vidéo (F-215 / SF-215-06). */
class TarGzTest {

    @TempDir Path temp;

    private Path archive(TarFixtures fixtures) throws IOException {
        Path file = temp.resolve("paquet.tar.gz");
        Files.write(file, fixtures.tarGz());
        return file;
    }

    @Test
    void extraitFichiersDossiersEtCheminsLongs() throws IOException {
        String longName = "hyperframes/node_modules/@img/sharp-libvips-linux-x64/lib/glib-2.0/include/"
                + "un-nom-vraiment-tres-long-pour-depasser-cent-octets.h";
        Path out = temp.resolve("out");

        long written = TarGz.extract(archive(new TarFixtures().dir("node/").file("node/LICENSE", "MIT")
                .file(longName, "x")), out);

        assertEquals(4, written);
        assertEquals("MIT", Files.readString(out.resolve("node/LICENSE")));
        assertEquals("x", Files.readString(out.resolve(longName)));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void reporteLeBitDExecution() throws IOException {
        Path out = temp.resolve("out");
        TarGz.extract(archive(new TarFixtures().executable("node/bin/node", "#!/bin/sh").file("a.txt", "a")), out);
        assertTrue(Files.isExecutable(out.resolve("node/bin/node")));
        assertFalse(Files.isExecutable(out.resolve("a.txt")));
    }

    @Test
    void refuseUnLien() throws IOException {
        Path out = temp.resolve("out");
        Path file = archive(new TarFixtures().file("a", "a").symlink("node/bin/node", "/bin/sh"));
        IOException refusal = assertThrows(IOException.class, () -> TarGz.extract(file, out));
        assertTrue(refusal.getMessage().contains("lien refusé"), refusal.getMessage());
    }

    @Test
    void refuseUneTraversee() throws IOException {
        Path out = temp.resolve("out");
        Path file = archive(new TarFixtures().file("../evade", "x"));
        assertThrows(IOException.class, () -> TarGz.extract(file, out));
        assertFalse(Files.exists(temp.resolve("evade")));
        assertThrows(IOException.class, () -> TarGz.target(out.toAbsolutePath(), "/etc/passwd"));
        assertThrows(IOException.class, () -> TarGz.target(out.toAbsolutePath(), "C:\\Windows\\x"));
    }

    @Test
    void refuseUneArchiveTronquee() throws IOException {
        byte[] full = TarFixtures.linuxBundle("v").tarGz();
        Path file = temp.resolve("tronque.tar.gz");
        Files.write(file, java.util.Arrays.copyOf(full, full.length / 2));
        assertThrows(IOException.class, () -> TarGz.extract(file, temp.resolve("out")));
    }
}
