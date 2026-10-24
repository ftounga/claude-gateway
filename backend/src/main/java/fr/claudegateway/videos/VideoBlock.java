package fr.claudegateway.videos;

import java.util.List;
import java.util.UUID;

/**
 * <b>Le bloc « vidéo » du fil</b> (F-213 / SF-213-01) : de quoi l'afficher — titre, version, chapitres, série —,
 * <b>jamais les octets</b> ni une adresse : l'écran relit {@code GET /videos/{id}?version=n}, isolé par compte,
 * qui lui donne un ticket de lecture frais.
 *
 * @param videoId     identifiant de la vidéo
 * @param title       titre
 * @param description description, ou {@code null}
 * @param version     version publiée par cet appel
 * @param durationS   durée annoncée, ou {@code null}
 * @param chapters    chapitres (liste vide sinon)
 * @param seriesId    série, ou {@code null}
 * @param seriesTitle titre de la série, ou {@code null}
 * @param episode     rang dans la série, ou {@code null}
 * @param poster      vrai si une couverture a été rangée
 */
public record VideoBlock(UUID videoId, String title, String description, int version, Integer durationS,
        List<VideoChapter> chapters, UUID seriesId, String seriesTitle, Integer episode, boolean poster) {

    /** Le bloc d'une publication. */
    public static VideoBlock of(VideoService.PublishedVideo published) {
        Video video = published.video();
        VideoVersion version = published.version();
        return new VideoBlock(video.getId(), video.getTitle(), video.getDescription(), version.getVersion(),
                video.getDurationS(), VideoChapter.fromJson(version.getChaptersJson()), video.getSeriesId(),
                published.series() == null ? null : published.series().getTitle(), video.getEpisode(),
                version.getPosterKey() != null);
    }
}
