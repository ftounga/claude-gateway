package fr.claudegateway.runner.toolchain;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.storage.WorkspaceStorage;

/**
 * <b>Où vivent les paquets de la chaîne vidéo</b> (F-215 / SF-215-06) : {@code toolchain/video/<version>/} dans le
 * stockage existant (bucket applicatif en cluster, mémoire en dev et en test) — jamais dans l'image Docker.
 *
 * <pre>
 * toolchain/video/&lt;version&gt;/manifest.json       sha256 et taille de chaque paquet
 * toolchain/video/&lt;version&gt;/manifest.json.sig   signature Ed25519 (clé F-111 du runner), Base64
 * toolchain/video/&lt;version&gt;/&lt;plateforme&gt;.tar.gz
 * </pre>
 *
 * <p>Déposés par {@code tools/video-toolchain/upload-bundles.sh} au déploiement ; la gateway ne fait que les lire.
 * Version et plateforme sont validées ici, avant toute clé : un chemin reçu n'atteint jamais le stockage tel
 * quel.</p>
 */
@Component
public class VideoToolchainStore {

    static final String PREFIX = "toolchain/video/";
    /** Les plateformes servies — liste close, partagée avec le runner (contrat). */
    public static final List<String> PLATFORMS = List.of("darwin-arm64", "darwin-x64", "linux-x64", "win-x64");
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.-]{0,39}");
    /** Au-delà, ce n'est pas un manifeste. */
    static final int MAX_MANIFEST_BYTES = 64 * 1024;

    private final WorkspaceStorage storage;

    public VideoToolchainStore(WorkspaceStorage storage) {
        this.storage = storage;
    }

    public static boolean validVersion(String version) {
        return version != null && VERSION.matcher(version).matches() && !version.contains("..");
    }

    public static boolean validPlatform(String platform) {
        return platform != null && PLATFORMS.contains(platform);
    }

    /** Les octets EXACTS du manifeste (la signature porte sur eux), ou vide. */
    public Optional<byte[]> manifest(String version) {
        if (!validVersion(version)) {
            return Optional.empty();
        }
        return storage.getFile(PREFIX + version + "/manifest.json").filter(b -> b.length <= MAX_MANIFEST_BYTES);
    }

    /** La signature du manifeste (Base64), ou vide. */
    public Optional<String> signature(String version) {
        if (!validVersion(version)) {
            return Optional.empty();
        }
        return storage.getFile(PREFIX + version + "/manifest.json.sig").filter(b -> b.length <= 1024)
                .map(b -> new String(b, StandardCharsets.US_ASCII).strip()).filter(s -> !s.isEmpty());
    }

    /** La taille du paquet d'une plateforme, ou vide s'il n'est pas publié. */
    public OptionalLong bundleSize(String version, String platform) {
        if (!validVersion(version) || !validPlatform(platform)) {
            return OptionalLong.empty();
        }
        return storage.size(bundleKey(version, platform));
    }

    /** Une plage du paquet, en flux. */
    public Optional<InputStream> openBundle(String version, String platform, long start, long endInclusive) {
        if (!validVersion(version) || !validPlatform(platform)) {
            return Optional.empty();
        }
        return storage.openRange(bundleKey(version, platform), start, endInclusive);
    }

    static String bundleKey(String version, String platform) {
        return PREFIX + version + "/" + platform + ".tar.gz";
    }
}
