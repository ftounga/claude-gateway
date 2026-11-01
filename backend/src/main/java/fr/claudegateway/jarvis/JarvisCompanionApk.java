package fr.claudegateway.jarvis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * <b>L'APK du compagnon Android de Jarvis</b> (F-232 / SF-232-02, D2) — servi par la gateway
 * <b>seulement s'il porte la bonne signature</b>.
 *
 * <p>La vérification lit la signature v1 (JAR) de l'APK avec le seul JDK : chaque entrée hors
 * {@code META-INF/} doit être signée, intacte, et par le certificat dont l'empreinte SHA-256 est
 * configurée. Un APK non signé, signé v2 seulement, signé par un autre certificat ou altéré n'est
 * jamais servi. Sans empreinte configurée, rien ne l'est non plus : on ne distribue pas un binaire
 * qu'on n'a pas su reconnaître.</p>
 *
 * <p>Le résultat est mis en cache par (taille, date de modification) : relire et vérifier quelques
 * mégaoctets à chaque affichage de l'écran Jarvis serait inutile.</p>
 *
 * <p><b>Version</b> (SF-232-04) : la variable {@code app.jarvis.companion.version-code} prime ; absente (0),
 * la gateway lit {@value #VERSION_FILE} à côté de l'APK, écrit par la construction
 * ({@code android/jarvis-companion/build-release.sh}) depuis la même source que Gradle — l'APK et la
 * version annoncée ne peuvent plus diverger.</p>
 */
@Service
public class JarvisCompanionApk {

    private static final Logger log = LoggerFactory.getLogger(JarvisCompanionApk.class);

    /** Écrit à côté de l'APK par la construction : {@code versionCode}, {@code versionName}. */
    static final String VERSION_FILE = "version.properties";
    private static final int MAX_VERSION_NAME = 40;

    /** Ce que la gateway sait de l'APK qu'elle sert. */
    public record Release(Path path, int versionCode, String versionName, String sha256,
            String signingCertSha256, long sizeBytes) {
    }

    private final String apkPath;
    private final int versionCode;
    private final String versionName;
    private final String expectedCert;

    private volatile CachedCheck cache;

    private record CachedCheck(long size, long modified, Version version, Release release) {
    }

    public JarvisCompanionApk(@Value("${app.jarvis.companion.apk-path:}") String apkPath,
            @Value("${app.jarvis.companion.version-code:0}") int versionCode,
            @Value("${app.jarvis.companion.version-name:}") String versionName,
            @Value("${app.jarvis.companion.signing-cert-sha256:}") String signingCertSha256) {
        this.apkPath = apkPath == null ? "" : apkPath.trim();
        this.versionCode = versionCode;
        this.versionName = versionName == null ? "" : versionName.trim();
        this.expectedCert = normalizeFingerprint(signingCertSha256);
    }

    /** L'APK servable, ou vide s'il est absent, mal configuré ou mal signé. */
    public Optional<Release> current() {
        if (apkPath.isEmpty() || expectedCert == null) {
            return Optional.empty();
        }
        Path path = Path.of(apkPath);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            return Optional.empty();
        }
        Version version = versionFor(path);
        if (version == null) {
            return Optional.empty();
        }
        long size;
        long modified;
        try {
            size = Files.size(path);
            modified = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            log.warn("Compagnon Jarvis : APK illisible ({})", path);
            return Optional.empty();
        }
        CachedCheck cached = cache;
        if (cached != null && cached.size() == size && cached.modified() == modified
                && version.equals(cached.version())) {
            return Optional.ofNullable(cached.release());
        }
        Release release = verify(path, size, version);
        cache = new CachedCheck(size, modified, version, release);
        return Optional.ofNullable(release);
    }

    private record Version(int code, String name) {
    }

    /** La variable si elle est posée ; sinon {@value #VERSION_FILE} à côté de l'APK ; {@code null} sinon. */
    private Version versionFor(Path apk) {
        if (versionCode >= 1) {
            return new Version(versionCode, versionName.isEmpty() ? String.valueOf(versionCode) : versionName);
        }
        Path parent = apk.toAbsolutePath().getParent();
        Path file = parent == null ? null : parent.resolve(VERSION_FILE);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Compagnon Jarvis : {} illisible ({})", VERSION_FILE, file);
            return null;
        }
        int code;
        try {
            code = Integer.parseInt(props.getProperty("versionCode", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (code < 1) {
            return null;
        }
        String name = props.getProperty("versionName", "").trim();
        if (name.isEmpty() || name.length() > MAX_VERSION_NAME) {
            name = String.valueOf(code);
        }
        return new Version(code, name);
    }

    private Release verify(Path path, long size, Version version) {
        try {
            if (!signedBy(path, expectedCert)) {
                log.warn("Compagnon Jarvis : APK non servi — signature absente, invalide ou d'un autre certificat ({})",
                        path);
                return null;
            }
            return new Release(path, version.code(), version.name(), sha256(path), expectedCert, size);
        } catch (IOException | SecurityException e) {
            log.warn("Compagnon Jarvis : APK non servi — vérification impossible ({}) : {}", path,
                    e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Vrai si chaque entrée hors {@code META-INF/} est signée (v1), intacte, et par un signataire dont
     * le certificat a l'empreinte attendue. Lire chaque entrée jusqu'au bout est ce qui déclenche la
     * vérification du condensat par {@link JarFile} ; une entrée altérée lève une
     * {@link SecurityException}.
     */
    static boolean signedBy(Path path, String expectedFingerprint) throws IOException {
        int checked = 0;
        try (JarFile jar = new JarFile(path.toFile(), true)) {
            byte[] sink = new byte[8192];
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) {
                    continue;
                }
                try (InputStream in = jar.getInputStream(entry)) {
                    while (in.read(sink) != -1) {
                        // Lecture complète : condition de la vérification.
                    }
                }
                if (!hasSigner(entry.getCodeSigners(), expectedFingerprint)) {
                    return false;
                }
                checked++;
            }
        }
        return checked > 0;
    }

    private static boolean hasSigner(CodeSigner[] signers, String expectedFingerprint) {
        if (signers == null) {
            return false;
        }
        for (CodeSigner signer : signers) {
            var certs = signer.getSignerCertPath().getCertificates();
            if (!certs.isEmpty() && expectedFingerprint.equals(fingerprint(certs.get(0)))) {
                return true;
            }
        }
        return false;
    }

    static String fingerprint(Certificate certificate) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (CertificateEncodingException | NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Empreinte normalisée : 64 caractères hexadécimaux minuscules ; le format de {@code keytool}
     * ({@code AA:BB:…}) est accepté. {@code null} si la valeur est absente ou invalide.
     */
    static String normalizeFingerprint(String raw) {
        if (raw == null) {
            return null;
        }
        String hex = raw.replace(":", "").replace(" ", "").trim().toLowerCase(Locale.ROOT);
        return hex.matches("[0-9a-f]{64}") ? hex : null;
    }
}
