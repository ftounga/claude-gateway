package fr.claudegateway.jarvis;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Fabrique de faux APK pour les tests (F-232 / SF-232-02) : une archive ZIP signée v1 par
 * {@code keytool} + {@code jarsigner} du JDK qui exécute les tests — exactement le format que la
 * gateway vérifie.
 */
final class SignedApkFixture {

    static final String PASSWORD = "test-only-pass";

    private SignedApkFixture() {
    }

    /** Un keystore PKCS12 neuf avec une clé RSA auto-signée sous l'alias {@code alias}. */
    static Path keystore(Path dir, String alias) throws IOException, InterruptedException {
        Path store = dir.resolve(alias + ".p12");
        run(List.of(tool("keytool"), "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-dname", "CN=" + alias, "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD));
        return store;
    }

    /** Empreinte SHA-256 (hexadécimal minuscule) du certificat de {@code alias}. */
    static String fingerprint(Path store, String alias) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            ks.load(in, PASSWORD.toCharArray());
        }
        return JarvisCompanionApk.fingerprint(ks.getCertificate(alias));
    }

    /** Une archive non signée, avec deux entrées « applicatives ». */
    static Path unsigned(Path dir, String name) throws IOException {
        Path apk = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(apk); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write("<manifest/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("classes.dex"));
            zip.write("dex\n035 faux".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return apk;
    }

    /** La même archive, signée v1 par {@code alias}. */
    static Path signed(Path dir, String name, Path store, String alias) throws IOException, InterruptedException {
        Path apk = unsigned(dir, name);
        run(List.of(tool("jarsigner"), "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD,
                "-keypass", PASSWORD, "-digestalg", "SHA-256", "-sigalg", "SHA256withRSA", apk.toString(), alias));
        return apk;
    }

    /**
     * Réécrit l'archive en remplaçant le contenu de {@code classes.dex} tout en gardant les fichiers de
     * signature : une altération après signature.
     */
    static Path tampered(Path signedApk, Path dir) throws IOException {
        Path out = dir.resolve("altere.apk");
        try (java.util.zip.ZipFile src = new java.util.zip.ZipFile(signedApk.toFile());
                OutputStream os = Files.newOutputStream(out);
                ZipOutputStream zip = new ZipOutputStream(os)) {
            var entries = src.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                zip.putNextEntry(new ZipEntry(e.getName()));
                if (e.getName().equals("classes.dex")) {
                    zip.write("dex\n035 MODIFIE".getBytes(StandardCharsets.UTF_8));
                } else {
                    try (InputStream in = src.getInputStream(e)) {
                        in.transferTo(zip);
                    }
                }
                zip.closeEntry();
            }
        }
        return out;
    }

    private static String tool(String name) {
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }

    private static void run(List<String> command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException(command.get(0) + " a échoué : " + output);
        }
    }
}
