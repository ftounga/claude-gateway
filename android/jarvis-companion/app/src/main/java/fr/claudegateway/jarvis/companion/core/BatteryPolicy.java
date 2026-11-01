package fr.claudegateway.jarvis.companion.core;

/**
 * <b>Arrêt sous seuil de batterie</b> (D3) — pur. Sous 15 % hors charge, l'écoute se met en pause ;
 * elle ne reprend qu'en charge ou à 20 % et plus. L'écart entre les deux seuils évite de basculer
 * sans cesse autour de 15 %.
 */
public final class BatteryPolicy {

    public static final int PAUSE_BELOW_PERCENT = 15;
    public static final int RESUME_FROM_PERCENT = 20;

    private boolean pausedForBattery = false;

    /**
     * Met à jour l'état avec la dernière mesure et dit si l'écoute est <b>permise</b>.
     *
     * @param percent niveau de batterie (0-100), négatif si inconnu (on laisse alors écouter)
     */
    public boolean update(int percent, boolean charging) {
        if (charging || percent < 0) {
            pausedForBattery = false;
        } else if (pausedForBattery) {
            pausedForBattery = percent < RESUME_FROM_PERCENT;
        } else {
            pausedForBattery = percent < PAUSE_BELOW_PERCENT;
        }
        return !pausedForBattery;
    }

    public boolean pausedForBattery() {
        return pausedForBattery;
    }
}
