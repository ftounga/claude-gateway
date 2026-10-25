package fr.claudegateway.runner.toolchain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.storage.InMemoryWorkspaceStorage;

/** Les clés de la chaîne vidéo (F-215 / SF-215-06) : rien de reçu n'atteint le stockage sans validation. */
class VideoToolchainStoreTest {

    @Test
    @DisplayName("versions et plateformes valides ; chemins détournés refusés")
    void validation() {
        assertThat(VideoToolchainStore.validVersion("0.8.193-r1")).isTrue();
        assertThat(VideoToolchainStore.validVersion("../atelier")).isFalse();
        assertThat(VideoToolchainStore.validVersion("1..2")).isFalse();
        assertThat(VideoToolchainStore.validVersion("a/b")).isFalse();
        assertThat(VideoToolchainStore.validVersion("")).isFalse();
        assertThat(VideoToolchainStore.validVersion("x".repeat(41))).isFalse();
        assertThat(VideoToolchainStore.validPlatform("darwin-arm64")).isTrue();
        assertThat(VideoToolchainStore.validPlatform("linux-arm64")).isFalse();
        assertThat(VideoToolchainStore.bundleKey("0.8.193-r1", "win-x64"))
                .isEqualTo("toolchain/video/0.8.193-r1/win-x64.tar.gz");
    }

    @Test
    @DisplayName("une version invalide ne lit rien, même si une clé voisine existe")
    void invalidVersionReadsNothing() {
        InMemoryWorkspaceStorage storage = new InMemoryWorkspaceStorage();
        storage.putFile("toolchain/video/../manifest.json", new byte[] {1}, "application/json");
        VideoToolchainStore store = new VideoToolchainStore(storage);
        assertThat(store.manifest("..")).isEmpty();
        assertThat(store.bundleSize("0.8.193-r1", "linux-x64")).isEmpty();
    }

    @Test
    @DisplayName("un manifeste démesuré n'est pas servi")
    void oversizedManifestIsRefused() {
        InMemoryWorkspaceStorage storage = new InMemoryWorkspaceStorage();
        storage.putFile("toolchain/video/v1/manifest.json", new byte[VideoToolchainStore.MAX_MANIFEST_BYTES + 1],
                "application/json");
        assertThat(new VideoToolchainStore(storage).manifest("v1")).isEmpty();
    }
}
