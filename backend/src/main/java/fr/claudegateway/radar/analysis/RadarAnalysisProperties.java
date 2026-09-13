package fr.claudegateway.radar.analysis;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Les réglages de la file d'analyse du Radar (F-101 / SF-101-01).
 *
 * <p><b>Un réglage aberrant retombe sur le défaut</b> plutôt que d'empêcher le démarrage, comme le
 * juge de F-94 : une faute de frappe dans une carte de configuration ne doit pas condamner le produit
 * pour une tâche de fond.</p>
 *
 * @param enabled        coupe-circuit du travailleur ; {@code false} : aucun lot n'est pris
 * @param maxAttempts    tentatives avant {@code FAILED}
 * @param rawRetention   durée de vie du texte brut d'un lot non analysé (cadrage §4.6 : 7 jours au plus)
 * @param leaseDuration  durée du bail d'un poste ; au-delà, un lot {@code PROCESSING} est repris
 * @param hostsPerRun    postes traités par passage
 * @param batchesPerHost lots traités par poste et par passage
 * @param deferDelay     échéance d'un report quand l'analyseur n'en donne pas
 */
@ConfigurationProperties(prefix = "app.radar.analysis")
public record RadarAnalysisProperties(Boolean enabled, Integer maxAttempts, Duration rawRetention,
        Duration leaseDuration, Integer hostsPerRun, Integer batchesPerHost, Duration deferDelay) {

    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    public static final Duration DEFAULT_RAW_RETENTION = Duration.ofDays(7);
    public static final Duration MAX_RAW_RETENTION = Duration.ofDays(7);
    public static final Duration DEFAULT_LEASE = Duration.ofMinutes(15);
    public static final int DEFAULT_HOSTS_PER_RUN = 4;
    public static final int DEFAULT_BATCHES_PER_HOST = 10;
    public static final Duration DEFAULT_DEFER_DELAY = Duration.ofHours(1);

    public RadarAnalysisProperties {
        enabled = enabled == null || enabled;
        maxAttempts = maxAttempts == null || maxAttempts < 1 || maxAttempts > 10 ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        // La rétention ne dépasse JAMAIS 7 jours : c'est une règle du cadrage, pas un réglage.
        rawRetention = rawRetention == null || rawRetention.isNegative() || rawRetention.isZero()
                || rawRetention.compareTo(MAX_RAW_RETENTION) > 0 ? DEFAULT_RAW_RETENTION : rawRetention;
        leaseDuration = leaseDuration == null || leaseDuration.compareTo(Duration.ofMinutes(1)) < 0
                || leaseDuration.compareTo(Duration.ofHours(2)) > 0 ? DEFAULT_LEASE : leaseDuration;
        hostsPerRun = hostsPerRun == null || hostsPerRun < 1 || hostsPerRun > 100 ? DEFAULT_HOSTS_PER_RUN : hostsPerRun;
        batchesPerHost = batchesPerHost == null || batchesPerHost < 1 || batchesPerHost > 200
                ? DEFAULT_BATCHES_PER_HOST : batchesPerHost;
        deferDelay = deferDelay == null || deferDelay.compareTo(Duration.ofMinutes(1)) < 0
                || deferDelay.compareTo(Duration.ofDays(1)) > 0 ? DEFAULT_DEFER_DELAY : deferDelay;
    }

    /** Les réglages d'une installation qui n'a rien configuré. */
    public static RadarAnalysisProperties defaults() {
        return new RadarAnalysisProperties(null, null, null, null, null, null, null);
    }

    /**
     * L'attente avant la tentative suivante : 1 min, 5 min, puis 30 min.
     *
     * @param attempts tentatives déjà faites (≥ 1)
     */
    public Duration backoff(int attempts) {
        return switch (Math.max(1, attempts)) {
            case 1 -> Duration.ofMinutes(1);
            case 2 -> Duration.ofMinutes(5);
            default -> Duration.ofMinutes(30);
        };
    }
}
