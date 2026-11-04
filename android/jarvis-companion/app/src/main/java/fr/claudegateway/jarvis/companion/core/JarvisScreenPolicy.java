package fr.claudegateway.jarvis.companion.core;

import java.net.URI;
import java.util.Locale;

/**
 * <b>L'écran Jarvis dans le compagnon</b> (F-232 / SF-232-08, amendement du 2026-11-04, D5 à D8). Pur, testé seul.
 *
 * <ul>
 *   <li>l'adresse chargée par la WebView : {@code /jarvis?source=compagnon&ecoute=1&compagnon=<version>} (D6) ;</li>
 *   <li>la WebView ne suit, ne donne le micro et n'expose le pont natif qu'à <b>l'origine de la gateway</b> ;</li>
 *   <li>la session courte (SF-232-05) est renouvelée quand il lui reste moins de {@link #RENEW_BEFORE_MS} ;</li>
 *   <li>l'écran se ferme seul après {@link #IDLE_CLOSE_MS} sans signe de vie (ni toucher, ni état de la page), pour
 *   rendre le micro au mot d'éveil et laisser l'écran s'éteindre.</li>
 * </ul>
 */
public final class JarvisScreenPolicy {

    /** Renouveler la session quand il lui reste moins que ceci (la session dure 30 min). */
    public static final long RENEW_BEFORE_MS = 5 * 60 * 1000L;
    /** Sans signe de vie de la page ni toucher pendant ceci, l'écran se ferme. */
    public static final long IDLE_CLOSE_MS = 3 * 60 * 1000L;
    /** Au-delà, la demande de déverrouillage est tenue pour abandonnée. */
    public static final long UNLOCK_WAIT_MS = 60 * 1000L;

    private JarvisScreenPolicy() {
    }

    /** L'écran Jarvis du compagnon : la conversation démarre aussitôt (D6), la version sert à la mise à jour. */
    public static String screenUrl(String gateway, int versionCode) {
        return gateway + "/jarvis?source=compagnon&ecoute=1&compagnon=" + versionCode;
    }

    /**
     * Vrai si {@code url} est de la même origine (schéma, hôte, port) que la gateway. Seules ces pages se chargent
     * dans la WebView, reçoivent le micro et voient le pont natif ; le reste est refusé.
     */
    public static boolean sameOrigin(String gateway, String url) {
        if (gateway == null || url == null) {
            return false;
        }
        try {
            URI g = new URI(gateway);
            URI u = new URI(url);
            return g.getScheme() != null && u.getScheme() != null && g.getHost() != null && u.getHost() != null
                    && g.getScheme().equalsIgnoreCase(u.getScheme())
                    && g.getHost().toLowerCase(Locale.ROOT).equals(u.getHost().toLowerCase(Locale.ROOT))
                    && port(g) == port(u)
                    && u.getRawUserInfo() == null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Faut-il demander une nouvelle session ? (aucune, ou bientôt échue). */
    public static boolean needsRenewal(long nowMs, long expiresAtMs) {
        return expiresAtMs <= 0 || expiresAtMs - nowMs < RENEW_BEFORE_MS;
    }

    /** L'écran est-il resté sans signe de vie assez longtemps pour se fermer ? */
    public static boolean idleExpired(long nowMs, long lastActivityMs) {
        return nowMs - lastActivityMs >= IDLE_CLOSE_MS;
    }

    /**
     * Le script qui rend à la page l'issue de la demande de déverrouillage (SF-232-07 l'attend sur
     * {@code window.jarvisCompanionUnlocked}). Jamais de donnée de la page dans ce script : un booléen seulement.
     */
    public static String unlockCallback(boolean unlocked) {
        return "window.jarvisCompanionUnlocked&&window.jarvisCompanionUnlocked(" + unlocked + ")";
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
    }
}
