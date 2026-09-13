package fr.claudegateway.radar.sync;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages de la synchro du soir (F-100), {@code app.radar.sync.*}. Une valeur aberrante retombe sur son
 * défaut : un planificateur mal réglé ne doit ni tourner à vide ni lancer une synchro par seconde.
 */
@ConfigurationProperties(prefix = "app.radar.sync")
public class RadarSyncProperties {

    static final Duration DEFAULT_STALE_AFTER = Duration.ofMinutes(15);
    static final Duration DEFAULT_FIRST_WINDOW = Duration.ofDays(30);
    static final Duration CATCH_UP_GRACE = Duration.ofMinutes(15);
    /** Recouvrement de la fenêtre incrémentale : ce qui arrive pendant une synchro est relu la suivante. */
    static final Duration WINDOW_OVERLAP = Duration.ofMinutes(10);

    /** Coupe-circuit du planificateur. */
    private boolean enabled = true;

    /** Au-delà, une synchro sans battement est abandonnée. */
    private Duration staleAfter = DEFAULT_STALE_AFTER;

    /** Fenêtre de la première synchro d'un poste. */
    private Duration firstWindow = DEFAULT_FIRST_WINDOW;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration staleAfter() {
        return staleAfter == null || staleAfter.compareTo(Duration.ofMinutes(2)) < 0
                || staleAfter.compareTo(Duration.ofHours(6)) > 0 ? DEFAULT_STALE_AFTER : staleAfter;
    }

    public Duration getStaleAfter() {
        return staleAfter;
    }

    public void setStaleAfter(Duration staleAfter) {
        this.staleAfter = staleAfter;
    }

    public Duration firstWindow() {
        return firstWindow == null || firstWindow.isNegative() || firstWindow.isZero()
                || firstWindow.compareTo(Duration.ofDays(90)) > 0 ? DEFAULT_FIRST_WINDOW : firstWindow;
    }

    public Duration getFirstWindow() {
        return firstWindow;
    }

    public void setFirstWindow(Duration firstWindow) {
        this.firstWindow = firstWindow;
    }
}
