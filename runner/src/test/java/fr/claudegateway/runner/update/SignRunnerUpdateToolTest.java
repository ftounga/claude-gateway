package fr.claudegateway.runner.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.launcher.LauncherHome;

/**
 * F-111 / SF-111-03 — le <b>vrai</b> programme de signature de {@code backend/Dockerfile}, exécuté comme
 * l'image l'exécute ({@code java build-tools/SignRunnerUpdate.java}), avec des clés générées à la volée.
 */
class SignRunnerUpdateToolTest {

    private static final Path TOOL = Path.of("build-tools", "SignRunnerUpdate.java");

    @TempDir
    Path dir;

    @Test
    void signeCeQueLeRunnerAccepteEtEcritLeManifeste() throws Exception {
        KeyPair keys = TestSigning.keyPair();
        Path jar = Files.write(dir.resolve("claude-runner.jar"), TestSigning.jarOf("1.2.0", "202609211000", "ccc3333"));
        Path priv = Files.writeString(dir.resolve("prive.pem"), TestSigning.privatePem(keys.getPrivate()));
        Path pub = Files.writeString(dir.resolve("public.pem"), TestSigning.publicPem(keys.getPublic()));
        Path notes = Files.writeString(dir.resolve("notes.txt"), "# commentaire\nPremière note.\n\nSeconde note.\n");
        Path out = dir.resolve("update");

        Run run = tool("sign", priv.toString(), jar.toString(), pub.toString(), notes.toString(), out.toString());

        assertEquals(0, run.code(), run.output());
        byte[] servi = Files.readAllBytes(out.resolve("runner.jar"));
        String sha = Files.readString(out.resolve("runner.jar.sha256")).trim();
        String signature = Files.readString(out.resolve("runner.jar.sig")).trim();
        assertEquals(LauncherHome.sha256(Files.readAllBytes(jar)), sha);
        assertEquals("1.2.0-202609211000-ccc3333",
                new UpdateVerifier(keys.getPublic()).verify(servi, sha, signature, "1.2.0-202609211000-ccc3333").id());

        JsonNode manifest = new ObjectMapper().readTree(out.resolve("runner-manifest.json").toFile());
        assertEquals("1.2.0-202609211000-ccc3333", manifest.path("id").asText());
        assertEquals("1.2.0", manifest.path("version").asText());
        assertEquals("ccc3333", manifest.path("commit").asText());
        assertEquals("2026-09-21T10:00:00Z", manifest.path("builtAt").asText());
        assertEquals(1, manifest.path("contract").asInt());
        assertEquals(21, manifest.path("minJava").asInt());
        assertEquals(sha, manifest.path("sha256").asText());
        assertEquals(servi.length, manifest.path("size").asLong());
        assertTrue(manifest.path("signed").asBoolean());
        assertEquals(List.of("Première note.", "Seconde note."),
                List.of(manifest.path("notes").get(0).asText(), manifest.path("notes").get(1).asText()));
    }

    @Test
    void uneClePriveeQuiNeCorrespondPasALaClePubliqueArreteLaConstruction() throws Exception {
        KeyPair keys = TestSigning.keyPair();
        KeyPair autre = TestSigning.keyPair();
        Path jar = Files.write(dir.resolve("claude-runner.jar"), TestSigning.jarOf("1.2.0", "202609211000", "ccc3333"));
        Path priv = Files.writeString(dir.resolve("prive.pem"), TestSigning.privatePem(autre.getPrivate()));
        Path pub = Files.writeString(dir.resolve("public.pem"), TestSigning.publicPem(keys.getPublic()));
        Path out = dir.resolve("update");

        Run run = tool("sign", priv.toString(), jar.toString(), pub.toString(), "-", out.toString());

        assertNotEquals(0, run.code());
        assertTrue(run.output().contains("ne correspond pas à la clé publique"), run.output());
        assertFalse(Files.exists(out.resolve("runner.jar.sig")), "aucune signature publiée");
    }

    @Test
    void sansSignatureRienNEstSigne() throws Exception {
        Path jar = Files.write(dir.resolve("claude-runner.jar"), TestSigning.jarOf("1.2.0", "202609211000", "ccc3333"));
        Path out = dir.resolve("update");

        Run run = tool("unsigned", jar.toString(), "-", out.toString());

        assertEquals(0, run.code(), run.output());
        assertTrue(Files.exists(out.resolve("runner.jar")));
        assertTrue(Files.exists(out.resolve("runner.jar.sha256")));
        assertFalse(Files.exists(out.resolve("runner.jar.sig")));
        assertFalse(new ObjectMapper().readTree(out.resolve("runner-manifest.json").toFile()).path("signed").asBoolean());
    }

    @Test
    void unJarSansVersionFiltreeNEstPasSigne() throws Exception {
        Path jar = Files.write(dir.resolve("claude-runner.jar"), TestSigning.jarOf("${project.version}", "x", "y"));
        Run run = tool("unsigned", jar.toString(), "-", dir.resolve("update").toString());
        assertNotEquals(0, run.code());
    }

    private record Run(int code, String output) {
    }

    private Run tool(String... args) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new java.util.ArrayList<>(List.of(java.toString(), TOOL.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS));
        return new Run(process.exitValue(), output);
    }
}
