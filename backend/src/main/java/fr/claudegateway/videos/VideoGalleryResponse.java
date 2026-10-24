package fr.claudegateway.videos;

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
            Integer durationS, int currentVersion, int chapterCount, String posterUrl, OffsetDateTime createdAt) {
    }

    /** Une série : titre, nombre d'épisodes, durée totale connue, épisodes. */
    public record SeriesItem(UUID id, String title, String description, int episodeCount, int totalDurationS,
            List<VideoItem> episodes, OffsetDateTime createdAt) {
    }

    /**
     * Assemble la galerie.
     *
     * @param posterUrl l'adresse de couverture d'une version (ticket), appelée seulement si elle en a une
     */
    public static VideoGalleryResponse of(VideoService.Gallery gallery, Function<VideoVersion, String> posterUrl) {
        Function<Video, VideoItem> item = video -> {
            VideoVersion version = gallery.current().get(video.getId());
            return new VideoItem(video.getId(), video.getTitle(), video.getDescription(), video.getSeriesId(),
                    video.getEpisode(), video.getDurationS(), video.getCurrentVersion(),
                    version == null ? 0 : VideoChapter.fromJson(version.getChaptersJson()).size(),
                    version == null || version.getPosterKey() == null ? null : posterUrl.apply(version),
                    video.getCreatedAt());
        };
        List<SeriesItem> series = gallery.series().stream().map(entry -> {
            List<VideoItem> episodes = entry.episodes().stream().map(item).toList();
            int total = episodes.stream().mapToInt(e -> e.durationS() == null ? 0 : e.durationS()).sum();
            return new SeriesItem(entry.series().getId(), entry.series().getTitle(), entry.series().getDescription(),
                    episodes.size(), total, episodes, entry.series().getCreatedAt());
        }).toList();
        return new VideoGalleryResponse(series, gallery.standalone().stream().map(item).toList());
    }
}
