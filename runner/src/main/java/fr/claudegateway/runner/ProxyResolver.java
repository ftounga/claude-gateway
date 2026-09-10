package fr.claudegateway.runner;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sélecteur de proxy pour le runner (F-38 / SF-38-03), construit à partir des variables
 * d'environnement d'entreprise {@code HTTPS_PROXY}, {@code HTTP_PROXY} et {@code NO_PROXY}
 * (variantes minuscules acceptées). Le {@link ProxySelector} par défaut de la JVM ne lit que les
 * propriétés système {@code https.proxyHost} : dans un environnement d'entreprise, le proxy est le
 * plus souvent porté par ces variables d'environnement, d'où ce résolveur dédié.
 *
 * <p>Appliqué à la fois à l'appel HTTP d'appairage et à l'ouverture WSS (les deux passent par le même
 * {@link java.net.http.HttpClient}). Le truststore d'entreprise reste géré par la JVM
 * ({@code -Djavax.net.ssl.trustStore}).</p>
 */
public final class ProxyResolver extends ProxySelector {

    private final String httpsProxy;
    private final String httpProxy;
    private final List<String> noProxyHosts;
    /** Nom de la variable qui a décidé la route sortante, ou {@code null} en sortie directe. */
    private final String routeVariable;

    ProxyResolver(String httpsProxy, String httpProxy, List<String> noProxyHosts) {
        this(httpsProxy, httpProxy, noProxyHosts, null);
    }

    ProxyResolver(String httpsProxy, String httpProxy, List<String> noProxyHosts,
            String routeVariable) {
        this.httpsProxy = httpsProxy;
        this.httpProxy = httpProxy;
        this.noProxyHosts = noProxyHosts;
        this.routeVariable = routeVariable;
    }

    /** Construit le résolveur depuis l'environnement (clés majuscules puis minuscules). */
    public static ProxyResolver fromEnv(Map<String, String> env) {
        Declared https = declared(env, "HTTPS_PROXY", "https_proxy");
        Declared http = declared(env, "HTTP_PROXY", "http_proxy");
        String noProxy = firstNonBlank(env, "NO_PROXY", "no_proxy");
        List<String> noProxyHosts = new ArrayList<>();
        if (noProxy != null) {
            for (String part : noProxy.split(",")) {
                String host = part.trim().toLowerCase(Locale.ROOT);
                if (!host.isEmpty()) {
                    noProxyHosts.add(host);
                }
            }
        }
        // La route affichée suit la même préférence que `select` pour une cible HTTPS : HTTPS_PROXY
        // d'abord, HTTP_PROXY en repli. C'est la gateway qu'on joint, et elle est en HTTPS.
        Declared route = https.value() != null ? https : http;
        return new ProxyResolver(normalize(https.value()), normalize(http.value()), noProxyHosts,
                route.value() == null ? null : route.variable());
    }

    /** {@code true} si un proxy est configuré (HTTP ou HTTPS). */
    public boolean hasProxy() {
        return httpsProxy != null || httpProxy != null;
    }

    /**
     * Route sortante telle que le runner la connaît (F-57 / SF-57-01) — <b>jamais</b> telle que le
     * poste la configure.
     *
     * <p>Cette description sert la transparence de démarrage : dire à l'utilisateur par où sortent
     * <b>nos</b> connexions. Elle ne lit ni le registre, ni un fichier PAC, ni la configuration
     * système : ce serait inspecter le poste, ce que F-57 s'interdit (cadrage, décision 1).</p>
     *
     * <p>L'adresse est <b>expurgée</b> de son {@code userinfo} : un {@code HTTPS_PROXY} de la forme
     * {@code http://user:motdepasse@hote:3128} se décrit {@code hote:3128}. Un écran de transparence
     * qui divulguerait le mot de passe du proxy d'entreprise serait une régression de sécurité
     * (cadrage, décision 8).</p>
     */
    public Route route() {
        String proxy = httpsProxy != null ? httpsProxy : httpProxy;
        if (proxy == null) {
            return new Route(Route.Kind.DIRECT, null, null);
        }
        String address = sanitize(proxy);
        Route.Kind kind = isLoopback(address) ? Route.Kind.LOCAL_RELAY : Route.Kind.ENTERPRISE_PROXY;
        return new Route(kind, address, routeVariable);
    }

    /**
     * Par où sortent les connexions du runner.
     *
     * @param kind sortie directe, proxy d'entreprise, ou relais local d'authentification
     * @param address {@code hote:port} expurgé de tout identifiant, ou {@code null} en direct
     * @param variable variable d'environnement qui l'a décidé, ou {@code null} en direct
     */
    public record Route(Kind kind, String address, String variable) {

        /** Nature de la route. Le relais local est distingué : il n'est pas un proxy d'entreprise. */
        public enum Kind { DIRECT, ENTERPRISE_PROXY, LOCAL_RELAY }
    }

    /**
     * Adresse d'un proxy réduite à {@code hote:port}, sans schéma, sans identifiants, sans chemin.
     *
     * <p>Ne lève jamais : une valeur malformée est affichée telle qu'elle a été nettoyée. Une ligne
     * de transparence ne doit pas empêcher un runner de démarrer.</p>
     */
    static String sanitize(String value) {
        String v = value.trim();
        int schemeIdx = v.indexOf("://");
        if (schemeIdx >= 0) {
            v = v.substring(schemeIdx + 3);
        }
        int at = v.lastIndexOf('@');
        if (at >= 0) {
            v = v.substring(at + 1); // identifiants inline : jamais affichés
        }
        int slash = v.indexOf('/');
        if (slash >= 0) {
            v = v.substring(0, slash);
        }
        return v;
    }

    /** Vrai quand l'adresse désigne la machine elle-même — donc un relais local, pas l'entreprise. */
    static boolean isLoopback(String address) {
        String host = address.toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            host = close > 0 ? host.substring(1, close) : host.substring(1);
        } else {
            int colon = host.lastIndexOf(':');
            if (colon >= 0) {
                host = host.substring(0, colon);
            }
        }
        return host.equals("localhost") || host.equals("::1") || host.startsWith("127.");
    }

    @Override
    public List<Proxy> select(URI uri) {
        String host = uri.getHost();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (host != null && isNoProxy(host)) {
            return List.of(Proxy.NO_PROXY);
        }
        String proxy = switch (scheme) {
            case "https", "wss" -> httpsProxy != null ? httpsProxy : httpProxy;
            case "http", "ws" -> httpProxy != null ? httpProxy : httpsProxy;
            default -> httpsProxy != null ? httpsProxy : httpProxy;
        };
        if (proxy == null) {
            return List.of(Proxy.NO_PROXY);
        }
        return List.of(toProxy(proxy));
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, java.io.IOException ioe) {
        // Rien : pas de bascule automatique, on laisse l'appelant journaliser l'échec.
    }

    private boolean isNoProxy(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        for (String entry : noProxyHosts) {
            if (entry.equals("*")) {
                return true;
            }
            String suffix = entry.startsWith(".") ? entry.substring(1) : entry;
            if (h.equals(suffix) || h.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    private static Proxy toProxy(String value) {
        // Même nettoyage que la route affichée : schéma, identifiants inline et chemin retirés.
        String v = sanitize(value);
        int colon = v.lastIndexOf(':');
        String host;
        int port;
        if (colon >= 0) {
            host = v.substring(0, colon);
            port = Integer.parseInt(v.substring(colon + 1));
        } else {
            host = v;
            port = 8080;
        }
        return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port));
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    /** Valeur trouvée dans l'environnement, et <b>nom de la variable</b> qui la portait. */
    private record Declared(String value, String variable) {
    }

    /**
     * Première variable renseignée parmi {@code keys}, avec son nom.
     *
     * <p>Le nom est retenu pour la transparence de démarrage (SF-57-01) : dire « proxy déclaré par
     * {@code HTTPS_PROXY} » désigne exactement ce qu'il faut modifier pour changer la route.</p>
     */
    private static Declared declared(Map<String, String> env, String... keys) {
        if (env == null) {
            return new Declared(null, null);
        }
        for (String key : keys) {
            String v = env.get(key);
            if (v != null && !v.isBlank()) {
                return new Declared(v, key);
            }
        }
        return new Declared(null, null);
    }

    private static String firstNonBlank(Map<String, String> env, String... keys) {
        if (env == null) {
            return null;
        }
        for (String key : keys) {
            String v = env.get(key);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
