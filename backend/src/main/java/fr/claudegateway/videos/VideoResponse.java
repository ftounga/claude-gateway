package fr.claudegateway.videos;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * <b>Une vidéo telle que l'écran la lit</b> (F-213 / SF-213-01) : ses métadonnées, la version servie, ses
 * chapitres, et les adresses de lecture par ticket — jamais le JWT dans une URL.
 */
public record VideoResponse(UUID id, String kind, String title, String description, UUID seriesId,
        String seriesTitle, Integer episode, int currentVersion, int version, Integer durationS, String mime,
        long sizeBytes, String label, List<VideoChapter> chapters, String mediaUrl, String posterUrl,
        String downloadUrl, OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    /** Assemble la réponse d'une version servie. */
    public static VideoResponse of(Video video, VideoVersion version, VideoSeries series, String mediaBase) {
        return new VideoResponse(video.getId(), video.getKind().name(), video.getTitle(), video.getDescription(),
                video.getSeriesId(), series == null ? null : series.getTitle(), video.getEpisode(),
                video.getCurrentVersion(), version.getVersion(), video.getDurationS(), version.getMime(),
                version.getSizeBytes(), version.getLabel(), VideoChapter.fromJson(version.getChaptersJson()),
                mediaBase, version.getPosterKey() == null ? null : mediaBase + "/poster", mediaBase + "?download=1",
                video.getCreatedAt(), video.getUpdatedAt());
    }

    /** Une version conservée, pour la liste des versions. */
    public record VersionResponse(int version, long sizeBytes, String mime, boolean poster, OffsetDateTime createdAt) {

        public static VersionResponse of(VideoVersion version) {
            return new VersionResponse(version.getVersion(), version.getSizeBytes(), version.getMime(),
                    version.getPosterKey() != null, version.getCreatedAt());
        }
    }
}
