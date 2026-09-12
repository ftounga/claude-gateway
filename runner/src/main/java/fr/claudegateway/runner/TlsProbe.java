package fr.claudegateway.runner;

import java.io.File;
import java.net.ProxySelector;
import java.net.URI;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Sonde TLS (F-57 / SF-57-02) : elle <b>lit</b> la chaîne de certificats que la gateway présente, et
 * confie le verdict à {@link TlsInspection}.
 *
 * <p><b>Elle ne décide de rien.</b> Le contrôle de vol réseau (SF-38-25) décide si le runner
 * démarre ; cette sonde produit un diagnostic optionnel. Les mêler ferait qu'un diagnostic pourrait
 * faire échouer un démarrage (D4) — d'où une classe séparée, qui ne lève jamais et se tait au
 * moindre doute.</p>
 *
 * <p><b>La lecture est non validante depuis F-80 / SF-80-01</b>, et c'est le seul changement de
 * posture : la connexion sondée suivait jusqu'ici les règles de validation de la JVM, et échouait
 * donc exactement dans le cas qui la justifie — celui où la validation échoue. Elle passe désormais
 * par {@link TlsChainReader}, qui observe la poignée de main et s'arrête là.</p>
 *
 * <p><b>Rien du canal réel n'est relâché.</b> La lecture de diagnostic n'écrit aucun octet
 * applicatif, ne pose aucun réglage global, et le trafic du runner emprunte un autre client avec la
 * vérification ordinaire. Le runner <b>affiche</b>, il ne <b>contourne</b> pas.</p>
 */
public final class TlsProbe {

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

    /**
     * Sonde réelle : racines du JDK, chaîne lue <b>sans validation</b> par une lecture de diagnostic
     * qui emprunte le proxy du runner et n'écrit aucun octet applicatif (F-80 / SF-80-01).
     */
    public static TlsProbe forRuntime(ProxySelector proxySelector) {
        return new TlsProbe(publicRootsFromJdk(),
                target -> TlsChainReader.readWithoutValidating(target, proxySelector));
    }

    /**
     * Ce qu'il y a à dire de la chaîne TLS de cette gateway, s'il y a quelque chose à en dire.
     *
     * @param gatewayBaseUrl URL de la gateway, telle que la configuration l'a normalisée
     * @return le message à afficher, ou vide — le silence est le comportement par défaut
     */
    public Optional<String> inspect(String gatewayBaseUrl) {
        return observe(gatewayBaseUrl)
                .map(seen -> TlsInspection.message(seen.host(), seen.rootDn()));
    }

    /**
     * Ce que le runner <b>ajoute au message d'échec</b> quand le contrôle de vol a buté sur une
     * poignée de main TLS (F-80 / SF-80-01).
     *
     * <p>Même verdict que {@link #inspect(String)} — une racine absente des racines publiques —,
     * mais formulé comme la <b>cause</b> de l'échec et non comme une information de contexte. Le
     * silence reste le défaut : une poignée de main qui échoue sur une chaîne <b>publique</b>
     * (certificat expiré, nom d'hôte faux) ne produit aucune mention d'interception, faux positif
     * interdit (D2 de F-57).</p>
     *
     * @param gatewayBaseUrl URL de la gateway, telle que la configuration l'a normalisée
     * @return les lignes à afficher sous l'erreur, ou vide
     */
    public Optional<String> explainHandshakeFailure(String gatewayBaseUrl) {
        return observe(gatewayBaseUrl)
                .map(seen -> TlsInspection.handshakeFailure(
                        TlsInspection.presenter(seen.chain(), seen.rootDn())));
    }

    /** Ce que la sonde a vu : l'hôte, la chaîne présentée, et la racine qui la re-signe. */
    record Seen(String host, List<TlsInspection.ChainLink> chain, String rootDn) {
    }

    /**
     * Observation brute, partagée par les deux messages. Ne lève jamais : un diagnostic optionnel
     * n'a le droit de rien casser — ni le démarrage, ni le code de sortie, ni la lisibilité de la
     * console (D6 de F-57).
     */
    Optional<Seen> observe(String gatewayBaseUrl) {
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
                    .map(root -> new Seen(target.getHost(), chain, root));
        } catch (Exception silence) {
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
}
