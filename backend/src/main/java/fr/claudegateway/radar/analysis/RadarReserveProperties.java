package fr.claudegateway.radar.analysis;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * La <b>réserve de synchro</b> configurée (F-101 / SF-101-05), portée par le droit Vigie depuis F-107 /
 * SF-107-04 ({@link VigieRadarReserve}).
 *
 * <p>Valeur de départ : la grille décidée par le PO — <b>3 M jetons par client suivi et par mois</b>,
 * à revalider après l'essai. Une valeur aberrante retombe sur le défaut, sans empêcher le démarrage.</p>
 *
 * @param monthlyTokens jetons d'analyse par poste et par mois civil (UTC)
 * @param perSyncTokens plafond d'une synchro ; 0 = aucun
 * @param trialTokens   réserve d'<b>essai Vigie</b> (F-107 / SF-107-04) : enveloppe unique pour tout le compte
 *                      sur la durée de l'essai par code ; défaut 3 M
 */
@ConfigurationProperties(prefix = "app.radar.reserve")
public record RadarReserveProperties(Long monthlyTokens, Long perSyncTokens, Long trialTokens) {

    /** Configuration d'avant F-107 / SF-107-04 : réserve d'essai par défaut. */
    public RadarReserveProperties(Long monthlyTokens, Long perSyncTokens) {
        this(monthlyTokens, perSyncTokens, null);
    }

    public static final long DEFAULT_MONTHLY_TOKENS = 3_000_000L;
    public static final long DEFAULT_TRIAL_TOKENS = 3_000_000L;
    public static final long MIN_TOKENS = 10_000L;
    public static final long MAX_TOKENS = 1_000_000_000L;

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public RadarReserveProperties {
        monthlyTokens = monthlyTokens == null || monthlyTokens < MIN_TOKENS || monthlyTokens > MAX_TOKENS
                ? DEFAULT_MONTHLY_TOKENS : monthlyTokens;
        perSyncTokens = perSyncTokens == null || perSyncTokens < MIN_TOKENS || perSyncTokens > MAX_TOKENS
                ? 0L : perSyncTokens;
        trialTokens = trialTokens == null || trialTokens < MIN_TOKENS || trialTokens > MAX_TOKENS
                ? DEFAULT_TRIAL_TOKENS : trialTokens;
    }

    public static RadarReserveProperties defaults() {
        return new RadarReserveProperties(null, null, null);
    }

    /** Le début du mois civil (UTC) de cet instant. */
    public static OffsetDateTime monthStart(OffsetDateTime now) {
        return now.withOffsetSameInstant(ZoneOffset.UTC).with(TemporalAdjusters.firstDayOfMonth())
                .toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    /** Le début du mois suivant (UTC) : quand la réserve mensuelle se renouvelle. */
    public static OffsetDateTime nextMonthStart(OffsetDateTime now) {
        return monthStart(now).plusMonths(1);
    }
}
