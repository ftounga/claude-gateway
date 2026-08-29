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

    ProxyResolver(String httpsProxy, String httpProxy, List<String> noProxyHosts) {
        this.httpsProxy = httpsProxy;
        this.httpProxy = httpProxy;
        this.noProxyHosts = noProxyHosts;
    }

    /** Construit le résolveur depuis l'environnement (clés majuscules puis minuscules). */
    public static ProxyResolver fromEnv(Map<String, String> env) {
        String https = firstNonBlank(env, "HTTPS_PROXY", "https_proxy");
        String http = firstNonBlank(env, "HTTP_PROXY", "http_proxy");
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
        return new ProxyResolver(normalize(https), normalize(http), noProxyHosts);
    }

    /** {@code true} si un proxy est configuré (HTTP ou HTTPS). */
    public boolean hasProxy() {
        return httpsProxy != null || httpProxy != null;
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
        String v = value;
        int schemeIdx = v.indexOf("://");
        if (schemeIdx >= 0) {
            v = v.substring(schemeIdx + 3);
        }
        int at = v.lastIndexOf('@');
        if (at >= 0) {
            v = v.substring(at + 1); // on ignore d'éventuels identifiants inline (non gérés ici)
        }
        int slash = v.indexOf('/');
        if (slash >= 0) {
            v = v.substring(0, slash);
        }
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
