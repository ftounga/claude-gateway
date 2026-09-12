package fr.claudegateway.runner;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Lecture <b>non validante</b> de la chaîne de certificats présentée par la gateway
 * (F-80 / SF-80-01).
 *
 * <p><b>Pourquoi elle existe.</b> Le diagnostic de F-57 lisait la chaîne par une connexion
 * <i>validante</i> : elle échouait donc exactement dans le seul cas qui la justifie — celui où la
 * validation échoue. Un échec TLS n'est pas un mur : il prouve au contraire que la connexion a
 * abouti et que le serveur a présenté un certificat. Ce certificat est lisible, et il explique
 * tout.</p>
 *
 * <p><b>Garde-fou, non négociable.</b> Cette lecture sert au <b>seul diagnostic</b> :</p>
 * <ul>
 *   <li>elle n'écrit <b>aucun octet applicatif</b> — pas de requête HTTP, pas d'en-tête, pas de
 *       jeton : la socket est refermée dès la poignée de main terminée ;</li>
 *   <li>le {@link TrustManager} permissif vit dans un {@link SSLContext} <b>local</b>, jamais posé
 *       en défaut de la JVM ({@code SSLContext.setDefault}) ni sur {@code HttpsURLConnection} ;</li>
 *   <li>elle ne relâche <b>rien</b> du canal réel : le trafic du runner passe par un autre client,
 *       avec la vérification ordinaire de la JVM.</li>
 * </ul>
 *
 * <p>Le principe de F-57 tient : le runner <b>affiche</b>, il ne <b>contourne</b> pas.</p>
 *
 * <p>Pourquoi une {@link SSLSocket} nue et non {@code HttpsURLConnection} : cette dernière
 * <b>envoie une requête</b> pour obtenir la chaîne. Une socket permet d'observer la poignée de main
 * et de s'arrêter là — ce qui est à la fois suffisant et la seule forme dans laquelle « aucun octet
 * de trafic » se démontre.</p>
 */
final class TlsChainReader {

    /** Aligné sur le contrôle de vol : au-delà, on fait attendre devant un terminal muet. */
    private static final int TIMEOUT_MS = 10_000;

    /** Port par défaut de {@code https}, quand l'URL n'en porte pas. */
    private static final int DEFAULT_HTTPS_PORT = 443;

    private TlsChainReader() {
    }

    /**
     * Chaîne présentée par le serveur, du certificat de site vers la racine — <b>sans validation</b>.
     *
     * @param target URL de la cible, en {@code https}
     * @param proxySelector sélecteur de proxy du runner ; sans lui, la lecture échouerait
     *     précisément sur les postes qu'elle sert
     * @return les maillons de la chaîne, jamais {@code null}
     * @throws Exception toute panne de connexion ; l'appelant se tait (le diagnostic est optionnel)
     */
    static List<TlsInspection.ChainLink> readWithoutValidating(URI target,
            ProxySelector proxySelector) throws Exception {
        String host = target.getHost();
        if (host == null || host.isBlank()) {
            return List.of();
        }
        int port = target.getPort() > 0 ? target.getPort() : DEFAULT_HTTPS_PORT;

        // Contexte LOCAL. Il n'est jamais posé en défaut de la JVM : rien d'autre dans ce processus
        // ne peut donc emprunter cette confiance, y compris par accident.
        SSLContext diagnostic = SSLContext.getInstance("TLS");
        diagnostic.init(null, new TrustManager[] { DiagnosticTrust.INSTANCE }, null);

        try (Socket transport = connect(host, port, proxySelector, target)) {
            transport.setSoTimeout(TIMEOUT_MS);
            try (SSLSocket secure = (SSLSocket) diagnostic.getSocketFactory()
                    .createSocket(transport, host, port, true)) {
                secure.setSoTimeout(TIMEOUT_MS);
                secure.setUseClientMode(true);
                // SNI explicite : sans lui, un serveur mutualisé présente le certificat par défaut,
                // qui n'est pas celui dont on cherche l'émetteur. Aucune vérification de nom d'hôte
                // n'est demandée ici — on lit, on ne conclut pas à la validité.
                SSLParameters parameters = secure.getSSLParameters();
                parameters.setServerNames(List.of(new SNIHostName(host)));
                secure.setSSLParameters(parameters);

                secure.startHandshake();
                List<TlsInspection.ChainLink> chain = new ArrayList<>();
                for (Certificate certificate : secure.getSession().getPeerCertificates()) {
                    if (certificate instanceof X509Certificate x509) {
                        chain.add(new TlsInspection.ChainLink(
                                x509.getSubjectX500Principal().getName(),
                                x509.getIssuerX500Principal().getName()));
                    }
                }
                // On sort ICI. Rien n'est écrit sur le flux applicatif : la socket se ferme.
                return List.copyOf(chain);
            }
        }
    }

    /**
     * Socket jusqu'au serveur : directe, ou tunnelée par le proxy du runner.
     *
     * <p>Le {@code CONNECT} est une négociation <b>de transport</b>, en clair, adressée au proxy :
     * ce n'est pas du trafic applicatif, et rien du produit n'y transite — ni jeton, ni chemin, ni
     * corps de requête. C'est le prix d'accès au serveur sur un poste derrière un proxy, c'est-à-dire
     * exactement le poste que ce diagnostic sert.</p>
     */
    private static Socket connect(String host, int port, ProxySelector proxySelector, URI target)
            throws IOException {
        Proxy proxy = firstProxy(proxySelector, target);
        if (proxy == null || proxy.type() != Proxy.Type.HTTP
                || !(proxy.address() instanceof InetSocketAddress relay)) {
            Socket direct = new Socket();
            direct.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            return direct;
        }
        Socket tunnel = new Socket();
        tunnel.connect(new InetSocketAddress(relay.getHostString(), relay.getPort()), TIMEOUT_MS);
        try {
            tunnel.setSoTimeout(TIMEOUT_MS);
            openTunnel(tunnel, host, port);
        } catch (IOException | RuntimeException failed) {
            tunnel.close();
            throw failed;
        }
        return tunnel;
    }

    /** Premier proxy proposé pour cette cible, ou {@code null} — les erreurs valent « direct ». */
    private static Proxy firstProxy(ProxySelector proxySelector, URI target) {
        if (proxySelector == null) {
            return null;
        }
        try {
            List<Proxy> proxies = proxySelector.select(target);
            return proxies == null || proxies.isEmpty() ? null : proxies.get(0);
        } catch (RuntimeException noRoute) {
            return null;
        }
    }

    /** {@code CONNECT hôte:port} et lecture de la réponse jusqu'à la ligne vide. */
    private static void openTunnel(Socket tunnel, String host, int port) throws IOException {
        String authority = host + ":" + port;
        OutputStream out = tunnel.getOutputStream();
        out.write(("CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority
                + "\r\nProxy-Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();

        InputStream in = tunnel.getInputStream();
        StringBuilder response = new StringBuilder();
        int consecutiveNewlines = 0;
        // Lecture octet par octet volontairement : un flux tamponné avalerait le début de la
        // poignée de main TLS, qui suit immédiatement l'en-tête du proxy sur la MÊME socket.
        while (consecutiveNewlines < 2 && response.length() < 4096) {
            int read = in.read();
            if (read < 0) {
                break;
            }
            char c = (char) read;
            response.append(c);
            if (c == '\n') {
                consecutiveNewlines++;
            } else if (c != '\r') {
                consecutiveNewlines = 0;
            }
        }
        String statusLine = response.toString().split("\\R", 2)[0].toUpperCase(Locale.ROOT);
        if (!statusLine.contains(" 200")) {
            throw new IOException("Le proxy a refusé le tunnel : " + statusLine.trim());
        }
    }

    /**
     * Confiance de <b>diagnostic</b> : elle accepte tout, et c'est sa raison d'être — on cherche à
     * <b>lire</b> un certificat que la JVM refuse, pas à décider s'il est digne de confiance.
     *
     * <p>Instance unique, package-private, et utilisée à un <b>seul</b> endroit : la socket de
     * lecture ci-dessus. Elle n'est jamais rendue à un appelant, jamais posée en défaut, et aucune
     * connexion de trafic ne peut la recevoir.</p>
     */
    static final class DiagnosticTrust implements X509TrustManager {

        static final DiagnosticTrust INSTANCE = new DiagnosticTrust();

        private DiagnosticTrust() {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // Sans objet : cette socket est cliente, et rien ne lui présentera de certificat client.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // Aucune décision n'est prise ici : la chaîne est LUE par l'appelant, puis la socket se
            // ferme sans qu'un octet applicatif circule.
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
