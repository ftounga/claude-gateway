package fr.claudegateway.jarvis.companion.core;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * L'adresse de la gateway et le lien profond vers l'écran Jarvis (F-231). Pur.
 *
 * <p>Seul {@code https://} est accepté : le lien ouvre une session authentifiée de la PWA. Les hôtes
 * de développement ({@code localhost}, {@code 127.0.0.1}, {@code 10.0.2.2} — l'hôte vu depuis
 * l'émulateur) tolèrent {@code http://}.</p>
 */
public final class JarvisLink {

    /** Le paramètre {@code source} est ignoré par l'écran Jarvis ; il ne sert qu'à lire les journaux. */
    public static final String JARVIS_PATH = "/jarvis?source=compagnon";

    // Set.of exige l'API 30 ; minSdk 26.
    private static final Set<String> DEV_HOSTS = new HashSet<>(Arrays.asList(
            "localhost", "127.0.0.1", "10.0.2.2"));

    private JarvisLink() {
    }

    /**
     * Normalise une adresse saisie : espaces et slash final retirés. {@code null} si elle n'est pas
     * acceptable.
     */
    public static String normalizeGateway(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (Exception e) {
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return null;
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return null;
        }
        boolean dev = DEV_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        if (!"https".equals(scheme) && !("http".equals(scheme) && dev)) {
            return null;
        }
        return value;
    }

    /** Le lien profond vers l'écran Jarvis de la gateway (déjà normalisée). */
    public static String jarvisUrl(String gateway) {
        return gateway + JARVIS_PATH;
    }

    /**
     * Le même lien, portant la version du compagnon (SF-232-02) : l'écran Jarvis la compare à celle que
     * la gateway sert et propose la mise à jour. Le compagnon n'interroge rien lui-même — il n'a pas la
     * permission {@code INTERNET}.
     */
    public static String jarvisUrl(String gateway, int versionCode) {
        return jarvisUrl(gateway) + "&compagnon=" + versionCode;
    }
}
