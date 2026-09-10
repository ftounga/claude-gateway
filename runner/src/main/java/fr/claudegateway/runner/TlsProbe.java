package fr.claudegateway.runner;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

/**
 * Sonde TLS (F-57 / SF-57-02) : elle <b>lit</b> la chaîne de certificats que la gateway présente, et
 * confie le verdict à {@link TlsInspection}.
 *
 * <p><b>Elle ne décide de rien.</b> Le contrôle de vol réseau (SF-38-25) décide si le runner
 * démarre ; cette sonde produit un diagnostic optionnel. Les mêler ferait qu'un diagnostic pourrait
 * faire échouer un démarrage (D4) — d'où une classe séparée, qui ne lève jamais et se tait au
 * moindre doute.</p>
 *
 * <p><b>Aucune vérification n'est relâchée.</b> La connexion sondée suit exactement les mêmes règles
 * que les autres : même magasin de confiance, même proxy, même vérification de nom d'hôte. On ne
 * pose ici aucun {@code TrustManager} permissif, aucun {@code HostnameVerifier} : si le certificat
 * n'est pas accepté, la sonde échoue et se tait — elle n'ouvre rien que la JVM aurait refusé.</p>
 *
 * <p>Pourquoi {@link HttpsURLConnection} et non le {@code HttpClient} du reste du runner (D5) :
 * ce dernier n'expose pas la chaîne du pair. {@code HttpsURLConnection} l'expose <i>et</i> sait
 * établir le {@code CONNECT} à travers un proxy — soit exactement le cas qui nous intéresse.</p>
 */
public final class TlsProbe {

    /** Aligné sur le contrôle de vol : au-delà, on fait attendre devant un terminal muet. */
    private static final int TIMEOUT_MS = 10_000;

    /** Chemin du magasin de racines livré avec le JDK, relatif à {@code java.home}. */
    private static final String CACERTS = "lib/security/cacerts";

    /** Lecture de la chaîne présentée par un serveur. Séparée pour être remplaçable en test. */
    @FunctionalInterface
    interface ChainReader {
        List<TlsInspection.ChainLink> read(URI target) throws Exception;
    }

    private final Set<String> publicRoots;
    private final ChainReader reader;

    TlsProbe(Set<String> publicRoots, ChainReader reader) {
        this.publicRoots = publicRoots;
        this.reader = reader;
    }

    /** Sonde réelle : racines du JDK, chaîne lue par une connexion qui emprunte le proxy du runner. */
    public static TlsProbe forRuntime(ProxySelector proxySelector) {
        return new TlsProbe(publicRootsFromJdk(), target -> readChain(target, proxySelector));
    }

    /**
     * Ce qu'il y a à dire de la chaîne TLS de cette gateway, s'il y a quelque chose à en dire.
     *
     * @param gatewayBaseUrl URL de la gateway, telle que la configuration l'a normalisée
     * @return le message à afficher, ou vide — le silence est le comportement par défaut
     */
    public Optional<String> inspect(String gatewayBaseUrl) {
        try {
            URI target = URI.create(gatewayBaseUrl + "/runner/download/formats");
            String scheme = target.getScheme() == null
                    ? "" : target.getScheme().toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme)) {
                // Gateway en clair (profil local) : il n'y a pas de chaîne à regarder, et rien à dire.
                return Optional.empty();
            }
            List<TlsInspection.ChainLink> chain = reader.read(target);
            return TlsInspection.interceptingRoot(chain, publicRoots)
                    .map(root -> TlsInspection.message(target.getHost(), root));
        } catch (Exception silence) {
            // Un diagnostic optionnel n'a le droit de rien casser : ni le démarrage, ni le code de
            // sortie, ni la lisibilité de la console (D6).
            return Optional.empty();
        }
    }

    /** Sujets des racines livrées avec le JDK. Vide si le magasin est illisible : alors on se tait. */
    static Set<String> publicRootsFromJdk() {
        Set<String> subjects = new HashSet<>();
        try {
            File cacerts = new File(System.getProperty("java.home", ""), CACERTS);
            if (!cacerts.isFile()) {
                return Set.of();
            }
            // Mot de passe nul : le contenu se lit sans contrôle d'intégrité, et le type du magasin
            // est déduit du fichier. On ne cherche pas à ouvrir un magasin protégé — on lit une
            // liste publique de racines.
            KeyStore store = KeyStore.getInstance(cacerts, (char[]) null);
            Enumeration<String> aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                Certificate certificate = store.getCertificate(aliases.nextElement());
                if (certificate instanceof X509Certificate x509) {
                    subjects.add(x509.getSubjectX500Principal().getName());
                }
            }
        } catch (Exception illisible) {
            return Set.of();
        }
        return Collections.unmodifiableSet(subjects);
    }

    /**
     * Chaîne présentée par le serveur, lue sur une connexion ordinaire.
     *
     * <p>Le proxy est celui du runner : sans lui, la sonde échouerait précisément sur les postes
     * qu'elle sert — ceux qui sont derrière un proxy d'inspection.</p>
     */
    private static List<TlsInspection.ChainLink> readChain(URI target, ProxySelector proxySelector)
            throws Exception {
        Proxy proxy = Proxy.NO_PROXY;
        if (proxySelector != null) {
            List<Proxy> proxies = proxySelector.select(target);
            if (proxies != null && !proxies.isEmpty() && proxies.get(0) != null) {
                proxy = proxies.get(0);
            }
        }
        URL url = target.toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection(proxy);
        if (!(connection instanceof HttpsURLConnection secure)) {
            return List.of();
        }
        try {
            secure.setRequestMethod("GET");
            secure.setConnectTimeout(TIMEOUT_MS);
            secure.setReadTimeout(TIMEOUT_MS);
            secure.connect();
            List<TlsInspection.ChainLink> chain = new ArrayList<>();
            for (Certificate certificate : secure.getServerCertificates()) {
                if (certificate instanceof X509Certificate x509) {
                    chain.add(new TlsInspection.ChainLink(
                            x509.getSubjectX500Principal().getName(),
                            x509.getIssuerX500Principal().getName()));
                }
            }
            return chain;
        } finally {
            secure.disconnect();
        }
    }
}
