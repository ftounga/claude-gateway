package fr.claudegateway.videos;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>Les bornes des vidéos</b> (F-213 / SF-213-01, cadrage §1–2 et arbitrage D1), surchargeables sous
 * {@code app.videos.*}. Une valeur absente, nulle ou négative retombe sur le défaut : une faute de
 * configuration ne doit ni ouvrir la vanne, ni tout refuser.
 *
 * <p>Un seul constructeur (le canonique) : un second casserait la liaison Spring.</p>
 *
 * @param maxVideoBytes   plafond d'une vidéo (300 Mo)
 * @param maxAudioBytes   plafond d'une piste audio (20 Mo)
 * @param maxPosterBytes  plafond d'une image de couverture (2 Mo)
 * @param maxAccountBytes plafond vidéo + audio d'un compte (5 Go, D1), ADMIN exempté
 * @param maxVersions     versions conservées par vidéo
 * @param ticketTtl       durée de validité d'un ticket de lecture
 */
@ConfigurationProperties(prefix = "app.videos")
public record VideoLimits(
        Long maxVideoBytes,
        Long maxAudioBytes,
        Long maxPosterBytes,
        Long maxAccountBytes,
        Integer maxVersions,
        Duration ticketTtl) {

    public static final long DEFAULT_MAX_VIDEO_BYTES = 300L * 1024 * 1024;
    public static final long DEFAULT_MAX_AUDIO_BYTES = 20L * 1024 * 1024;
    public static final long DEFAULT_MAX_POSTER_BYTES = 2L * 1024 * 1024;
    public static final long DEFAULT_MAX_ACCOUNT_BYTES = 5L * 1024 * 1024 * 1024;
    public static final int DEFAULT_MAX_VERSIONS = 5;
    public static final Duration DEFAULT_TICKET_TTL = Duration.ofHours(12);

    public VideoLimits {
        if (maxVideoBytes == null || maxVideoBytes <= 0) {
            maxVideoBytes = DEFAULT_MAX_VIDEO_BYTES;
        }
        if (maxAudioBytes == null || maxAudioBytes <= 0) {
            maxAudioBytes = DEFAULT_MAX_AUDIO_BYTES;
        }
        if (maxPosterBytes == null || maxPosterBytes <= 0) {
            maxPosterBytes = DEFAULT_MAX_POSTER_BYTES;
        }
        if (maxAccountBytes == null || maxAccountBytes <= 0) {
            maxAccountBytes = DEFAULT_MAX_ACCOUNT_BYTES;
        }
        if (maxVersions == null || maxVersions <= 0) {
            maxVersions = DEFAULT_MAX_VERSIONS;
        }
        if (ticketTtl == null || ticketTtl.isZero() || ticketTtl.isNegative()) {
            ticketTtl = DEFAULT_TICKET_TTL;
        }
    }

    /** Les bornes du cadrage, sans configuration. */
    public static VideoLimits defaults() {
        return new VideoLimits(null, null, null, null, null, null);
    }

    /** Le plafond d'un fichier de ce type. */
    public long maxBytes(VideoKind kind) {
        return kind == VideoKind.AUDIO ? maxAudioBytes : maxVideoBytes;
    }
}
