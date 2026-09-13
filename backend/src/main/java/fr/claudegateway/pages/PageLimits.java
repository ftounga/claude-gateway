package fr.claudegateway.pages;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Les bornes des pages (F-109 / SF-109-01, cadrage §3.3 et §7), réglables sans livraison.
 *
 * @param maxPageBytes    taille d'une version, pièces jointes comprises (8 Mo)
 * @param maxAccountBytes octets conservés par compte, toutes pages et versions (500 Mo)
 * @param maxVersions     versions conservées par page ; au-delà, les plus anciennes sont purgées (10)
 * @param maxAttachments  pièces jointes par version (20)
 * @param ticketTtl       durée d'un ticket de lecture de l'écran (10 minutes)
 */
@ConfigurationProperties(prefix = "app.pages")
public record PageLimits(
        Long maxPageBytes,
        Long maxAccountBytes,
        Integer maxVersions,
        Integer maxAttachments,
        Duration ticketTtl) {

    public static final long DEFAULT_MAX_PAGE_BYTES = 8L * 1024 * 1024;
    public static final long DEFAULT_MAX_ACCOUNT_BYTES = 500L * 1024 * 1024;
    public static final int DEFAULT_MAX_VERSIONS = 10;
    public static final int DEFAULT_MAX_ATTACHMENTS = 20;
    public static final Duration DEFAULT_TICKET_TTL = Duration.ofMinutes(10);

    public PageLimits {
        // Une valeur absente, nulle ou négative retombe sur le défaut du cadrage : une faute de
        // configuration ne doit ni ouvrir la vanne, ni refuser toutes les pages.
        if (maxPageBytes == null || maxPageBytes <= 0) {
            maxPageBytes = DEFAULT_MAX_PAGE_BYTES;
        }
        if (maxAccountBytes == null || maxAccountBytes <= 0) {
            maxAccountBytes = DEFAULT_MAX_ACCOUNT_BYTES;
        }
        if (maxVersions == null || maxVersions <= 0) {
            maxVersions = DEFAULT_MAX_VERSIONS;
        }
        if (maxAttachments == null || maxAttachments < 0) {
            maxAttachments = DEFAULT_MAX_ATTACHMENTS;
        }
        if (ticketTtl == null || ticketTtl.isZero() || ticketTtl.isNegative()) {
            ticketTtl = DEFAULT_TICKET_TTL;
        }
    }

    /** Les bornes du cadrage, sans configuration. */
    public static PageLimits defaults() {
        return new PageLimits(null, null, null, null, null);
    }
}
