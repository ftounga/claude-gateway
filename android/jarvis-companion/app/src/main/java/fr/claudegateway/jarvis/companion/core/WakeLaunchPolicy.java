package fr.claudegateway.jarvis.companion.core;

/**
 * <b>Comment l'écran Jarvis s'ouvre au mot, et quand il se ferme</b> (F-232 / SF-232-10). Pur, testé seul.
 *
 * <ul>
 *   <li><b>Direct</b> : le PO a autorisé l'ouverture directe ({@code SYSTEM_ALERT_WINDOW}) — le service lance l'écran
 *   lui-même, téléphone verrouillé comme en main, sans notification ;</li>
 *   <li><b>Plein écran</b> : sinon, l'intention plein écran (Android ne la lance qu'écran éteint ou verrouillé ;
 *   téléphone en main, elle devient une bannière) ;</li>
 *   <li><b>Bannière</b> : sans le droit « plein écran » (Android 14+), il ne reste que la bannière.</li>
 * </ul>
 *
 * <p>Fermeture : l'écran ne se ferme plus quand il passe « arrêté » (la transition du keyguard et de l'allumage le
 * fait passer par {@code onStop}, ce qui le fermait aussitôt — constat du 2026-11-06). Seul un arrêt prolongé au-delà
 * de {@link #STOPPED_CLOSE_MS} le ferme, jamais pendant la demande de déverrouillage.</p>
 */
public final class WakeLaunchPolicy {

    /** Les trois façons d'ouvrir l'écran Jarvis. */
    public enum Launch { DIRECT, FULL_SCREEN, BANNER }

    /** Après un lancement direct, la notification de repli n'est publiée que si l'écran ne s'est pas ouvert d'ici là. */
    public static final long DIRECT_OPEN_CHECK_MS = 2_000L;
    /** Un écran Jarvis resté invisible (arrêté) plus longtemps se ferme et rend le micro. */
    public static final long STOPPED_CLOSE_MS = 10_000L;

    private WakeLaunchPolicy() {
    }

    /**
     * @param canDrawOverlays     l'ouverture directe est autorisée
     * @param fullScreenPermitted l'intention plein écran est permise (avant Android 14, ou droit accordé)
     */
    public static Launch launch(boolean canDrawOverlays, boolean fullScreenPermitted) {
        if (canDrawOverlays) {
            return Launch.DIRECT;
        }
        return fullScreenPermitted ? Launch.FULL_SCREEN : Launch.BANNER;
    }

    /** Faut-il publier la notification de repli ? Seulement si l'écran lancé directement ne s'est pas ouvert. */
    public static boolean needsFallback(boolean screenOpened) {
        return !screenOpened;
    }

    /**
     * L'écran arrêté depuis {@code stoppedAtMs} doit-il se fermer ? Jamais pendant la demande de déverrouillage, ni
     * s'il n'est pas arrêté ({@code stoppedAtMs <= 0}).
     */
    public static boolean closeWhileStopped(long nowMs, long stoppedAtMs, boolean unlocking) {
        return !unlocking && stoppedAtMs > 0 && nowMs - stoppedAtMs >= STOPPED_CLOSE_MS;
    }

    /** Écran éteint, bouton accueil : l'écran se ferme, sauf pendant la demande de déverrouillage. */
    public static boolean closeOnLeave(boolean unlocking) {
        return !unlocking;
    }
}
