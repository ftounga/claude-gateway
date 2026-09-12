package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fr.claudegateway.runner.OperatingSystem;

/**
 * <b>L'outillage téléchargé au premier usage</b> (F-90 / SF-90-01, décision <b>D3</b>).
 *
 * <p>Ce que ces tests prouvent : l'ordre de résolution (le {@code PATH} d'abord, donc aucun
 * téléchargement quand l'outil est déjà là), le fait que le téléchargement <b>se dit</b> avant et
 * après, qu'un rapatriement rompu ne laisse <b>aucune</b> copie partielle, et que tous les refus
 * portent un <b>remède</b>.</p>
 */
class LocalToolchainTest {

    @TempDir
    Path home;

    private final List<String> said = new ArrayList<>();

    @Test
    void le_path_du_poste_est_regarde_avant_tout_telechargement() throws IOException {
        Path installed = Files.createFile(home.resolve("ffmpeg"));
        installed.toFile().setExecutable(true);
        Downloads downloads = new Downloads();
        LocalToolchain toolchain = toolchain(OperatingSystem.LINUX,
                new FakeProcesses().answering(command -> FakeProcesses.version("ffmpeg")),
                downloads, name -> installed);

        Path resolved = toolchain.require(LocalTool.ffmpeg());

        assertEquals(installed, resolved);
        assertEquals(0, downloads.attempts, "aucun téléchargement quand l'outil est déjà là");
        assertTrue(said.isEmpty(), "rien à annoncer quand rien n'est rapatrié");
    }

    @Test
    void la_copie_deja_rapatriee_est_reutilisee_sans_retelecharger() throws IOException {
        TeamsWorkFolder folder = new TeamsWorkFolder(home);
        Path tools = folder.toolsDir().resolve("ffmpeg");
        Files.createDirectories(tools);
        Path binary = Files.createFile(tools.resolve("ffmpeg"));
        binary.toFile().setExecutable(true);
        Downloads downloads = new Downloads();
        LocalToolchain toolchain = new LocalToolchain(folder, OperatingSystem.LINUX,
                new FakeProcesses().answering(command -> FakeProcesses.version("ffmpeg")),
                downloads, said::add, name -> null);

        assertEquals(binary, toolchain.require(LocalTool.ffmpeg()));
        assertEquals(0, downloads.attempts);
        assertTrue(toolchain.availableWithoutDownload(LocalTool.ffmpeg()));
    }

    @Test
    void le_telechargement_se_voit_et_se_dit_avant_et_apres() {
        Downloads downloads = new Downloads().writing(zipContaining("ffmpeg"));
        LocalToolchain toolchain = toolchain(OperatingSystem.MACOS,
                new FakeProcesses().answering(command -> FakeProcesses.version("ffmpeg")),
                downloads, name -> null);

        toolchain.require(LocalTool.ffmpeg());

        assertEquals(2, said.size(), "une phrase avant, une phrase après");
        assertTrue(said.get(0).contains("Premier usage"));
        assertTrue(said.get(0).contains("evermeet.cx"), "on dit D'OÙ on le prend");
        assertTrue(said.get(0).contains("brew install ffmpeg"),
                "la voie sûre est proposée en même temps");
        assertTrue(said.get(1).contains("en place"), "on dit OÙ il a été posé");
        assertTrue(said.get(1).contains("Mo"), "on dit ce qu'il pèse");
    }

    @Test
    void un_telechargement_rompu_ne_laisse_aucune_copie_partielle() {
        Downloads downloads = new Downloads().broken();
        LocalToolchain toolchain = toolchain(OperatingSystem.MACOS, new FakeProcesses(), downloads,
                name -> null);

        ToolchainUnavailableException refusal =
                assertThrows(ToolchainUnavailableException.class,
                        () -> toolchain.require(LocalTool.ffmpeg()));

        assertTrue(refusal.sentence().contains("brew install ffmpeg"), "le refus porte le remède");
        assertTrue(refusal.remedy().contains("Rien n'a été laissé à moitié écrit"));
        Path installed = new TeamsWorkFolder(home).toolsDir().resolve("ffmpeg").resolve("ffmpeg");
        assertFalse(Files.exists(installed), "aucun binaire tronqué laissé en place");
    }

    @Test
    void un_systeme_sans_version_a_rapatrier_refuse_en_nommant_la_commande_a_lancer() {
        LocalToolchain toolchain = toolchain(OperatingSystem.OTHER, new FakeProcesses(),
                new Downloads(), name -> null);

        ToolchainUnavailableException refusal =
                assertThrows(ToolchainUnavailableException.class,
                        () -> toolchain.require(LocalTool.ffmpeg()));

        assertTrue(refusal.getMessage().contains("introuvable"));
        assertTrue(refusal.remedy().contains("gestionnaire de paquets"));
    }

    @Test
    void un_binaire_qui_ne_sidentifie_pas_nest_pas_utilise() throws IOException {
        Path installed = Files.createFile(home.resolve("ffmpeg"));
        installed.toFile().setExecutable(true);
        LocalToolchain toolchain = toolchain(OperatingSystem.LINUX,
                new FakeProcesses().answering(command ->
                        new ProcessRunner.ProcessResult(0, List.of("bash, version 5.2"), List.of(),
                                false)),
                new Downloads(), name -> installed);

        ToolchainUnavailableException refusal =
                assertThrows(ToolchainUnavailableException.class,
                        () -> toolchain.require(LocalTool.ffmpeg()));

        assertTrue(refusal.getMessage().contains("ne s'identifie pas"));
    }

    @Test
    void une_archive_qui_veut_sortir_de_son_dossier_est_refusee() {
        // Zip Slip : une entrée « ../evade » ne doit pas pouvoir écrire hors du bac de
        // décompression. Le refus est un refus de rapatriement, avec son remède.
        Downloads downloads = new Downloads().writing(zipContaining("../evade"));
        LocalToolchain toolchain = toolchain(OperatingSystem.MACOS, new FakeProcesses(), downloads,
                name -> null);

        ToolchainUnavailableException refusal =
                assertThrows(ToolchainUnavailableException.class,
                        () -> toolchain.require(LocalTool.ffmpeg()));

        assertTrue(refusal.getMessage().contains("n'a pas abouti"));
        assertFalse(Files.exists(new TeamsWorkFolder(home).toolsDir().resolve("ffmpeg")
                .resolve("ffmpeg")), "rien n'est installé quand l'archive est refusée");
    }

    @Test
    void aucun_binaire_nest_embarque_dans_le_paquet_runner() {
        // D3 : « jamais embarqués ». Si un jour quelqu'un glisse un ffmpeg dans les ressources,
        // ce test le dit.
        assertTrue(LocalTool.ffmpeg().downloadFor(OperatingSystem.LINUX).startsWith("https://"));
        org.junit.jupiter.api.Assertions.assertNull(
                LocalToolchain.class.getClassLoader().getResource("tools/ffmpeg"),
                "aucun binaire d'outil embarqué dans le jar du runner");
    }

    private LocalToolchain toolchain(OperatingSystem os, ProcessRunner processes,
            Downloads downloads, LocalToolchain.PathLookup lookup) {
        return new LocalToolchain(new TeamsWorkFolder(home), os, processes, downloads, said::add,
                lookup);
    }

    /** Une archive ZIP à une entrée, écrite en mémoire. */
    private static byte[] zipContaining(String entryName) {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write("#!/bin/sh\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return buffer.toByteArray();
    }

    /** Un rapatriement de papier : il compte les tentatives et n'ouvre aucune socket. */
    private static final class Downloads implements LocalToolchain.Downloader {

        int attempts;
        private byte[] payload;
        private boolean broken;

        Downloads writing(byte[] payload) {
            this.payload = payload;
            return this;
        }

        Downloads broken() {
            this.broken = true;
            return this;
        }

        @Override
        public long fetch(String url, Path into) throws IOException {
            attempts++;
            if (broken) {
                // Ce que fait un réseau qui coupe : des octets, puis plus rien.
                Files.write(into, new byte[] {0x50, 0x4B});
                throw new IOException("connexion interrompue");
            }
            Files.write(into, payload == null ? new byte[0] : payload);
            return payload == null ? 0 : payload.length;
        }
    }
}
