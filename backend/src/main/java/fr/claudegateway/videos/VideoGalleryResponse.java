package fr.claudegateway.videos;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * <b>La galerie d'un sujet ou d'un poste</b> (F-213 / SF-213-03) : les séries, épisodes dans l'ordre, puis les
 * vidéos seules. Le lecteur relit {@code GET /videos/{id}} pour une adresse de lecture fraîche ; la carte, elle,
 * porte l'adresse de sa couverture.
 */
public record VideoGalleryResponse(List<SeriesItem> series, List<VideoItem> videos) {

    /** Une vidéo telle qu'une carte la montre. */
    public record VideoItem(UUID id, String title, String description, UUID seriesId, Integer episode,
            Integer durationS, int currentVersion, int chapterCount, String posterUrl, OffsetDateTime createdAt,
            BigDecimal costUsd, BigDecimal estimateLowUsd, BigDecimal estimateHighUsd, BigDecimal costEur,
            BigDecimal estimateLowEur, BigDecimal estimateHighEur) {
    }

    /**
     * Une série : titre, nombre d'épisodes, durée totale connue, épisodes ; coût et estimation sommés sur les épisodes
     * (F-216 / SF-216-02 ; {@code null} si aucun épisode n'est mesuré).
     */
    public record SeriesItem(UUID id, String title, String description, int episodeCount, int totalDurationS,
            List<VideoItem> episodes, OffsetDateTime createdAt, BigDecimal costUsd, BigDecimal estimateLowUsd,
            BigDecimal estimateHighUsd, BigDecimal costEur, BigDecimal estimateLowEur, BigDecimal estimateHighEur) {
    }

    /**
     * Assemble la galerie.
     *
     * @param posterUrl l'adresse de couverture d'une version (ticket), appelée seulement si elle en a une
     * @param usdToEur  le taux unique de la passerelle : chaque montant est aussi rendu en euros (F-224 / SF-224-06)
     */
    public static VideoGalleryResponse of(VideoService.Gallery gallery, Function<VideoVersion, String> posterUrl,
            BigDecimal usdToEur) {
        Function<Video, VideoItem> item = video -> {
            VideoVersion version = gallery.current().get(video.getId());
            return new VideoItem(video.getId(), video.getTitle(), video.getDescription(), video.getSeriesId(),
                    video.getEpisode(), video.getDurationS(), video.getCurrentVersion(),
                    version == null ? 0 : VideoChapter.fromJson(version.getChaptersJson()).size(),
                    version == null || version.getPosterKey() == null ? null : posterUrl.apply(version),
                    video.getCreatedAt(), money(video.getCostUsd()), money(video.getEstimateLowUsd()),
                    money(video.getEstimateHighUsd()), eur(video.getCostUsd(), usdToEur),
                    eur(video.getEstimateLowUsd(), usdToEur), eur(video.getEstimateHighUsd(), usdToEur));
        };
        List<SeriesItem> series = gallery.series().stream().map(entry -> {
            List<VideoItem> episodes = entry.episodes().stream().map(item).toList();
            int total = episodes.stream().mapToInt(e -> e.durationS() == null ? 0 : e.durationS()).sum();
            return new SeriesItem(entry.series().getId(), entry.series().getTitle(), entry.series().getDescription(),
                    episodes.size(), total, episodes, entry.series().getCreatedAt(),
                    sum(episodes.stream().map(VideoItem::costUsd).toList()),
                    sum(episodes.stream().map(VideoItem::estimateLowUsd).toList()),
                    sum(episodes.stream().map(VideoItem::estimateHighUsd).toList()),
                    sum(episodes.stream().map(VideoItem::costEur).toList()),
                    sum(episodes.stream().map(VideoItem::estimateLowEur).toList()),
                    sum(episodes.stream().map(VideoItem::estimateHighEur).toList()));
        }).toList();
        return new VideoGalleryResponse(series, gallery.standalone().stream().map(item).toList());
    }

    /** Un montant au cent (F-216 / SF-216-02), ou {@code null}. */
    static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    /** Un montant en dollars, en euros au centime ({@code HALF_UP}), ou {@code null} (F-224 / SF-224-06). */
    static BigDecimal eur(BigDecimal usd, BigDecimal usdToEur) {
        if (usd == null) {
            return null;
        }
        BigDecimal rate = usdToEur == null || usdToEur.signum() <= 0 ? VideoQuoteService.DEFAULT_USD_TO_EUR : usdToEur;
        return usd.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /** La somme des montants connus, ou {@code null} s'il n'y en a aucun. */
    private static BigDecimal sum(List<BigDecimal> values) {
        BigDecimal total = null;
        for (BigDecimal value : values) {
            if (value != null) {
                total = total == null ? value : total.add(value);
            }
        }
        return total;
    }
}
