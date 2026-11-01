package fr.claudegateway.jarvis;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** L'APK du compagnon n'est servi que s'il porte la bonne signature (F-232 / SF-232-02, D2). */
class JarvisCompanionApkTest {

    @TempDir
    static Path dir;

    private static Path goodStore;
    private static Path otherStore;
    private static String goodFingerprint;

    @BeforeAll
    static void keys() throws Exception {
        goodStore = SignedApkFixture.keystore(dir, "jarvis");
        otherStore = SignedApkFixture.keystore(dir, "intrus");
        goodFingerprint = SignedApkFixture.fingerprint(goodStore, "jarvis");
    }

    private static JarvisCompanionApk apk(Path path, String fingerprint) {
        return new JarvisCompanionApk(path.toString(), 3, "0.3.0", fingerprint);
    }

    @Test
    void signeParLeBonCertificat_estServiAvecSesEmpreintes() throws Exception {
        Path signed = SignedApkFixture.signed(dir, "bon.apk", goodStore, "jarvis");

        Optional<JarvisCompanionApk.Release> release = apk(signed, goodFingerprint).current();

        assertThat(release).isPresent();
        assertThat(release.get().versionCode()).isEqualTo(3);
        assertThat(release.get().versionName()).isEqualTo("0.3.0");
        assertThat(release.get().sha256()).matches("[0-9a-f]{64}");
        assertThat(release.get().signingCertSha256()).isEqualTo(goodFingerprint);
        assertThat(release.get().sizeBytes()).isEqualTo(Files.size(signed));
    }

    @Test
    void empreinteAuFormatKeytool_estAcceptee() throws Exception {
        Path signed = SignedApkFixture.signed(dir, "keytool.apk", goodStore, "jarvis");
        String keytoolFormat = goodFingerprint.toUpperCase().replaceAll("(..)(?!$)", "$1:");

        assertThat(apk(signed, keytoolFormat).current()).isPresent();
    }

    @Test
    void signeParUnAutreCertificat_nEstJamaisServi() throws Exception {
        Path signed = SignedApkFixture.signed(dir, "autre.apk", otherStore, "intrus");

        assertThat(apk(signed, goodFingerprint).current()).isEmpty();
    }

    @Test
    void nonSigne_nEstJamaisServi() throws Exception {
        Path unsigned = SignedApkFixture.unsigned(dir, "nu.apk");

        assertThat(apk(unsigned, goodFingerprint).current()).isEmpty();
    }

    @Test
    void altereApresSignature_nEstJamaisServi() throws Exception {
        Path signed = SignedApkFixture.signed(dir, "avant.apk", goodStore, "jarvis");
        Path tampered = SignedApkFixture.tampered(signed, dir);

        assertThat(apk(tampered, goodFingerprint).current()).isEmpty();
    }

    @Test
    void sansEmpreinteOuSansVersion_rienNEstServi() throws Exception {
        Path signed = SignedApkFixture.signed(dir, "config.apk", goodStore, "jarvis");

        assertThat(apk(signed, "").current()).isEmpty();
        assertThat(apk(signed, "pas-une-empreinte").current()).isEmpty();
        assertThat(new JarvisCompanionApk(signed.toString(), 0, "", goodFingerprint).current()).isEmpty();
        assertThat(new JarvisCompanionApk("", 3, "", goodFingerprint).current()).isEmpty();
        assertThat(apk(dir.resolve("absent.apk"), goodFingerprint).current()).isEmpty();
    }

    @Test
    void leFichierRemplace_estReverifie() throws Exception {
        Path path = dir.resolve("remplace.apk");
        Files.copy(SignedApkFixture.signed(dir, "v1.apk", goodStore, "jarvis"), path);
        JarvisCompanionApk service = apk(path, goodFingerprint);
        assertThat(service.current()).isPresent();

        Files.copy(SignedApkFixture.signed(dir, "v2-intrus.apk", otherStore, "intrus"), path,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        assertThat(service.current()).isEmpty();
    }

    // ---- SF-232-04 : la version écrite par la construction, à côté de l'APK

    private static Path inOwnDir(String name, String versionFile) throws Exception {
        Path sub = Files.createDirectories(dir.resolve("v-" + name));
        Path apk = SignedApkFixture.signed(sub, name, goodStore, "jarvis");
        if (versionFile != null) {
            Files.writeString(sub.resolve(JarvisCompanionApk.VERSION_FILE), versionFile);
        }
        return apk;
    }

    @Test
    void sansVariable_laVersionVientDuFichierDeConstruction() throws Exception {
        Path apk = inOwnDir("fichier.apk", "versionCode=7\nversionName=0.7.0\n");

        Optional<JarvisCompanionApk.Release> release =
                new JarvisCompanionApk(apk.toString(), 0, "", goodFingerprint).current();

        assertThat(release).isPresent();
        assertThat(release.get().versionCode()).isEqualTo(7);
        assertThat(release.get().versionName()).isEqualTo("0.7.0");
    }

    @Test
    void laVariablePrimeSurLeFichier() throws Exception {
        Path apk = inOwnDir("variable.apk", "versionCode=7\nversionName=0.7.0\n");

        Optional<JarvisCompanionApk.Release> release =
                new JarvisCompanionApk(apk.toString(), 9, "0.9.0", goodFingerprint).current();

        assertThat(release.get().versionCode()).isEqualTo(9);
        assertThat(release.get().versionName()).isEqualTo("0.9.0");
    }

    @Test
    void fichierAbsentOuVersionInvalide_rienNEstServi() throws Exception {
        assertThat(new JarvisCompanionApk(inOwnDir("absent.apk", null).toString(), 0, "", goodFingerprint)
                .current()).isEmpty();
        assertThat(new JarvisCompanionApk(inOwnDir("zero.apk", "versionCode=0\n").toString(), 0, "",
                goodFingerprint).current()).isEmpty();
        assertThat(new JarvisCompanionApk(inOwnDir("texte.apk", "versionCode=abc\n").toString(), 0, "",
                goodFingerprint).current()).isEmpty();
    }

    @Test
    void nomDeVersionAbsent_leNumeroSert() throws Exception {
        Path apk = inOwnDir("sansnom.apk", "versionCode=4\n");

        assertThat(new JarvisCompanionApk(apk.toString(), 0, "", goodFingerprint).current().get().versionName())
                .isEqualTo("4");
    }

    /**
     * L'APK réellement construit et signé par la clé de prod (SF-232-04), quand on le fournit :
     * {@code -Djarvis.realApk=<chemin>} (avec {@code version.properties} à côté) et
     * {@code -Djarvis.realCert=<empreinte>}. Ignoré sinon — l'APK n'est pas dans le dépôt.
     */
    @Test
    void apkReelConstruit_estReconnu() {
        String real = System.getProperty("jarvis.realApk");
        String cert = System.getProperty("jarvis.realCert");
        org.junit.jupiter.api.Assumptions.assumeTrue(real != null && cert != null);

        Optional<JarvisCompanionApk.Release> release = new JarvisCompanionApk(real, 0, "", cert).current();

        assertThat(release).isPresent();
        assertThat(release.get().versionCode()).isGreaterThanOrEqualTo(1);
    }
}
