package fr.claudegateway.runner.update;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

/** F-111 / SF-111-03 — ce que la gateway accepte de servir comme mise à jour. */
public class RunnerUpdateArtifactsTest {

    public static final String ID = "1.1.0-202609200900-bbb2222";

    @TempDir
    Path dir;

    /** Dépose un jeu d'artefacts comme le fait la construction de l'image. */
    public static void deposit(Path dir, String id, boolean signed, String shaOverride) throws Exception {
        Files.createDirectories(dir);
        Path jar = Files.write(dir.resolve("runner.jar"), ("jar " + id).getBytes(StandardCharsets.UTF_8));
        String sha = shaOverride != null ? shaOverride : RunnerUpdateArtifacts.sha256(jar);
        Files.writeString(dir.resolve("runner.jar.sha256"), sha + "\n");
        if (signed) {
            Files.writeString(dir.resolve("runner.jar.sig"), "c2lnbmF0dXJl\n");
        }
        Files.writeString(dir.resolve("runner-manifest.json"), "{\"id\":\"" + id + "\",\"version\":\"1.1.0\","
                + "\"commit\":\"bbb2222\",\"builtAt\":\"2026-09-20T09:00:00Z\",\"contract\":1,\"minJava\":21,"
                + "\"sha256\":\"" + sha + "\",\"size\":10,\"signed\":" + signed + ",\"notes\":[\"Une note.\"],"
                + "\"champFutur\":true}");
    }

    private RunnerUpdateArtifacts artifacts() {
        return new RunnerUpdateArtifacts(dir.toString(), "", new ObjectMapper());
    }

    @Test
    @DisplayName("sert la version signée dont l'empreinte est cohérente")
    void servesSignedVersion() throws Exception {
        deposit(dir, ID, true, null);

        RunnerUpdateArtifacts artifacts = artifacts();

        assertThat(artifacts.signedUpdateAvailable()).isTrue();
        assertThat(artifacts.manifest()).get().extracting(RunnerUpdateArtifacts.Manifest::notes)
                .isEqualTo(java.util.List.of("Une note."));
        assertThat(artifacts.jar(ID)).contains(dir.resolve("runner.jar"));
        assertThat(artifacts.signature(ID)).contains("c2lnbmF0dXJl");
        assertThat(artifacts.sha256(ID)).isPresent();
        assertThat(artifacts.jar("1.0.0-202609130000-aaa")).as("une autre version n'est jamais servie").isEmpty();
    }

    @Test
    @DisplayName("une version non signée publie son manifeste, jamais son jar")
    void unsignedIsNotServed() throws Exception {
        deposit(dir, ID, false, null);

        RunnerUpdateArtifacts artifacts = artifacts();

        assertThat(artifacts.manifest()).isPresent();
        assertThat(artifacts.signedUpdateAvailable()).isFalse();
        assertThat(artifacts.jar(ID)).isEmpty();
        assertThat(artifacts.signature(ID)).isEmpty();
    }

    @Test
    @DisplayName("une empreinte qui ne correspond pas au jar écarte tout")
    void inconsistentShaServesNothing() throws Exception {
        deposit(dir, ID, true, "ab".repeat(32));

        assertThat(artifacts().manifest()).isEmpty();
        assertThat(artifacts().jar(ID)).isEmpty();
    }

    @Test
    @DisplayName("sans dossier, ou avec un manifeste illisible, rien n'est servi")
    void nothingWithoutArtifacts() throws Exception {
        assertThat(new RunnerUpdateArtifacts("", "", new ObjectMapper()).manifest()).isEmpty();
        assertThat(artifacts().manifest()).isEmpty();
        Files.writeString(dir.resolve("runner.jar"), "x");
        Files.writeString(dir.resolve("runner-manifest.json"), "{ pas du json");
        assertThat(artifacts().manifest()).isEmpty();
    }

    @Test
    @DisplayName("le dossier par défaut est runner-update à côté du jar servi")
    void defaultDirIsNextToTheJar() {
        assertThat(RunnerUpdateArtifacts.resolveDir("", "/app/claude-runner.jar"))
                .isEqualTo(Path.of("/app/runner-update"));
        assertThat(RunnerUpdateArtifacts.resolveDir("/ailleurs", "/app/claude-runner.jar"))
                .isEqualTo(Path.of("/ailleurs"));
        assertThat(RunnerUpdateArtifacts.resolveDir("", "")).isNull();
    }
}
