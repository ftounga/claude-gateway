package fr.claudegateway.radar.analysis;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * La <b>réserve de synchro</b> configurée (F-101 / SF-101-05), en attendant qu'elle soit portée par
 * l'option Vigie (F-107 / SF-107-04).
 *
 * <p>Valeur de départ : la grille décidée par le PO — <b>3 M jetons par client suivi et par mois</b>,
 * à revalider après l'essai. Une valeur aberrante retombe sur le défaut, sans empêcher le démarrage.</p>
 *
 * @param monthlyTokens jetons d'analyse par poste et par mois civil (UTC)
 * @param perSyncTokens plafond d'une synchro ; 0 = aucun
 */
@ConfigurationProperties(prefix = "app.radar.reserve")
public record RadarReserveProperties(Long monthlyTokens, Long perSyncTokens) {

    public static final long DEFAULT_MONTHLY_TOKENS = 3_000_000L;
    public static final long MIN_TOKENS = 10_000L;
    public static final long MAX_TOKENS = 1_000_000_000L;

    public RadarReserveProperties {
        monthlyTokens = monthlyTokens == null || monthlyTokens < MIN_TOKENS || monthlyTokens > MAX_TOKENS
                ? DEFAULT_MONTHLY_TOKENS : monthlyTokens;
        perSyncTokens = perSyncTokens == null || perSyncTokens < MIN_TOKENS || perSyncTokens > MAX_TOKENS
                ? 0L : perSyncTokens;
    }

    public static RadarReserveProperties defaults() {
        return new RadarReserveProperties(null, null);
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
