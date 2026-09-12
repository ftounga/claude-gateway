package fr.claudegateway.runner;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Le truststore du runner (F-80 / SF-80-02) : le {@code cacerts} de la JDK <b>plus</b> le magasin de
 * certificats du système d'exploitation.
 *
 * <p><b>Le fait observé.</b> Sur un poste sous Zscaler, la racine d'inspection est installée : le
 * navigateur la reconnaît, {@code curl} aussi. Seule la JVM échoue — parce qu'elle n'a jamais lu le
 * magasin du système, et ne fait confiance qu'à son propre {@code cacerts}. Il n'y avait rien à
 * demander à la DSI : tout était déjà sur la machine, et c'est le runner qui regardait au mauvais
 * endroit.</p>
 *
 * <p><b>Ce n'est pas un relâchement</b> (OQ-17, tranchée par le PO le 2026-09-12) : c'est
 * exactement la confiance que le navigateur et {@code curl} accordent déjà sur ce poste. Un runner
 * qui refuse ce que le système accepte n'est pas plus sûr, il est seulement inutilisable. Le
 * comportement est <b>automatique et annoncé</b> ; {@code --no-system-trust} rétablit la confiance
 * stricte.</p>
 *
 * <p><b>Additionner, jamais remplacer</b> (D1) : le {@code cacerts} est chargé en entier d'abord. Une
 * racine publique retirée du magasin d'un poste ne cesse donc pas d'être reconnue.</p>
 *
 * <p><b>Le runner lit, il n'écrit pas.</b> Rien n'est installé, rien n'est modifié : ni le magasin
 * du poste, ni le {@code cacerts} de la JDK.</p>
 *
 * <p><b>Repli silencieux</b> (D4) : un conteneur minimal sans magasin système est un cas normal, pas
 * une anomalie. Il ne produit ni erreur, ni avertissement, ni ligne — le runner retombe sur le
 * {@code cacerts} seul et démarre.</p>
 */
public final class TrustStores {

    /** Chemin du magasin de racines livré avec le JDK, relatif à {@code java.home}. */
    private static final String CACERTS = "lib/security/cacerts";

    /**
     * Faisceaux de racines des distributions Linux, dans l'ordre où on les essaie.
     *
     * <p>Le premier est celui de Debian/Ubuntu — donc de WSL, le poste réellement observé. Les
     * suivants couvrent les familles RHEL/Fedora et SUSE.</p>
     */
    private static final List<String> LINUX_BUNDLES = List.of(
            "/etc/ssl/certs/ca-certificates.crt",
            "/etc/pki/tls/certs/ca-bundle.crt",
            "/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem",
            "/etc/ssl/ca-bundle.pem");

    /**
     * Ce à quoi le runner fait confiance, et ce qu'il faut en dire.
     *
     * @param context contexte SSL à poser sur le client de trafic, ou {@code null} pour laisser
     *     celui de la JVM — c'est la forme du repli silencieux
     * @param manager le gestionnaire de confiance construit, ou {@code null} en repli ; c'est lui
     *     qui décide, et c'est donc lui que les tests éprouvent
     * @param systemStoreUsed vrai quand le magasin du système a réellement apporté quelque chose
     * @param addedRoots nombre de racines que le magasin du système ajoute au {@code cacerts}
     * @param source nom lisible de la source lue, ou chaîne vide
     */
    public record Trust(SSLContext context, X509TrustManager manager, boolean systemStoreUsed,
            int addedRoots, String source) {

        /** Confiance inchangée : celle de la JVM, et rien d'autre à dire. */
        static Trust jdkOnly() {
            return new Trust(null, null, false, 0, "");
        }
    }

    private TrustStores() {
    }

    /**
     * Construit le truststore du runner.
     *
     * @param systemTrust {@code false} quand {@code --no-system-trust} a été posé — la confiance
     *     stricte, c'est-à-dire le {@code cacerts} de la JDK seul
     */
    public static Trust resolve(boolean systemTrust) {
        if (!systemTrust) {
            // Confiance stricte demandée : le magasin du système n'est même pas ouvert.
            return Trust.jdkOnly();
        }
        return resolve(jdkRoots(), systemCertificates(), sourceLabel());
    }

    /**
     * Même construction, à partir de listes déjà lues. Séparée pour que les tests puissent éprouver
     * la <b>fusion</b> sans dépendre du poste qui les exécute.
     *
     * @param jdkRoots racines livrées avec le JDK — la base, jamais remplacée (D1)
     * @param systemCertificates certificats du magasin du système d'exploitation
     * @param source nom lisible de la source lue, pour ce qui est affiché au démarrage
     */
    static Trust resolve(List<X509Certificate> jdkRoots, List<X509Certificate> systemCertificates,
            String source) {
        try {
            if (jdkRoots == null || jdkRoots.isEmpty()) {
                // Sans le cacerts, on ne peut pas ADDITIONNER — on ne ferait que remplacer, ce qui
                // est précisément ce que D1 interdit. On laisse la JVM décider seule.
                return Trust.jdkOnly();
            }
            KeyStore merged = KeyStore.getInstance("PKCS12");
            merged.load(null, null);

            Set<String> known = new HashSet<>();
            int index = 0;
            for (X509Certificate root : jdkRoots) {
                if (root != null && known.add(fingerprint(root))) {
                    merged.setCertificateEntry("jdk-" + index++, root);
                }
            }
            int added = 0;
            for (X509Certificate certificate : systemCertificates == null ? List.<X509Certificate>of()
                    : systemCertificates) {
                if (certificate != null && known.add(fingerprint(certificate))) {
                    merged.setCertificateEntry("systeme-" + added++, certificate);
                }
            }
            if (added == 0) {
                // Le magasin du système n'apporte rien : la confiance de la JVM suffit, et il n'y a
                // rien à annoncer. Poser un contexte identique au défaut ne ferait qu'ajouter du
                // risque pour aucun bénéfice.
                return Trust.jdkOnly();
            }

            TrustManagerFactory factory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(merged);
            X509TrustManager manager = null;
            for (TrustManager candidate : factory.getTrustManagers()) {
                if (candidate instanceof X509TrustManager x509) {
                    manager = x509;
                    break;
                }
            }
            if (manager == null) {
                return Trust.jdkOnly();
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] { manager }, null);
            return new Trust(context, manager, true, added, source == null ? "" : source);
        } catch (Exception repliSilencieux) {
            // D4 : rien de tout cela n'a le droit d'empêcher un démarrage. Un magasin illisible, un
            // fournisseur absent d'une image jlink réduite, un fichier corrompu : on retombe sur le
            // cacerts, et on se tait.
            return Trust.jdkOnly();
        }
    }

    /** Racines livrées avec le JDK. Vide si le magasin est illisible. */
    static List<X509Certificate> jdkRoots() {
        List<X509Certificate> roots = new ArrayList<>();
        try {
            File cacerts = new File(System.getProperty("java.home", ""), CACERTS);
            if (!cacerts.isFile()) {
                return List.of();
            }
            // Mot de passe nul : on lit une liste publique de racines, sans contrôle d'intégrité.
            KeyStore store = KeyStore.getInstance(cacerts, (char[]) null);
            Enumeration<String> aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                Certificate certificate = store.getCertificate(aliases.nextElement());
                if (certificate instanceof X509Certificate x509) {
                    roots.add(x509);
                }
            }
        } catch (Exception illisible) {
            return List.of();
        }
        return roots;
    }

    /**
     * Certificats du magasin du système d'exploitation. Liste vide quand il n'y en a pas, ou qu'il
     * n'est pas lisible — jamais d'exception.
     */
    static List<X509Certificate> systemCertificates() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            // Fourni par le module jdk.crypto.mscapi — que les paquets jlink doivent embarquer,
            // sans quoi cette lecture lève et le repli silencieux s'applique (D5).
            return fromKeyStore("Windows-ROOT");
        }
        if (os.contains("mac") || os.contains("darwin")) {
            // KeychainStore-ROOT n'existe que sur les JDK récents ; KeychainStore est le repli.
            List<X509Certificate> roots = fromKeyStore("KeychainStore-ROOT");
            return roots.isEmpty() ? fromKeyStore("KeychainStore") : roots;
        }
        for (String bundle : LINUX_BUNDLES) {
            List<X509Certificate> roots = fromPemBundle(Path.of(bundle));
            if (!roots.isEmpty()) {
                return roots;
            }
        }
        return List.of();
    }

    /** Nom lisible de la source lue, pour ce qui est affiché au démarrage. */
    private static String sourceLabel() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "Windows-ROOT";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "trousseau macOS";
        }
        for (String bundle : LINUX_BUNDLES) {
            if (Files.isReadable(Path.of(bundle))) {
                return bundle;
            }
        }
        return "";
    }

    /** Certificats d'un magasin nommé du système ({@code Windows-ROOT}, {@code KeychainStore}). */
    private static List<X509Certificate> fromKeyStore(String type) {
        List<X509Certificate> roots = new ArrayList<>();
        try {
            KeyStore store = KeyStore.getInstance(type);
            store.load(null, null);
            Enumeration<String> aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                Certificate certificate = store.getCertificate(aliases.nextElement());
                if (certificate instanceof X509Certificate x509) {
                    roots.add(x509);
                }
            }
        } catch (Exception absentOuIllisible) {
            return List.of();
        }
        return roots;
    }

    /** Certificats d'un faisceau PEM concaténé, tel que les distributions Linux en publient. */
    static List<X509Certificate> fromPemBundle(Path bundle) {
        if (bundle == null || !Files.isReadable(bundle)) {
            return List.of();
        }
        List<X509Certificate> roots = new ArrayList<>();
        try (InputStream in = Files.newInputStream(bundle)) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            for (Certificate certificate : factory.generateCertificates(in)) {
                if (certificate instanceof X509Certificate x509) {
                    roots.add(x509);
                }
            }
        } catch (Exception illisible) {
            // Un fichier tronqué, du bruit, des droits refusés : on se tait et on n'ajoute rien.
            return List.of();
        }
        return roots;
    }

    /** Empreinte d'un certificat : sa forme encodée, seule façon sûre de reconnaître un doublon. */
    private static String fingerprint(X509Certificate certificate) throws Exception {
        return Base64.getEncoder().encodeToString(certificate.getEncoded());
    }
}
