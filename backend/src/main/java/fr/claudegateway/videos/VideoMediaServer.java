package fr.claudegateway.videos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletResponse;

/**
 * <b>Servir un média avec {@code Range}</b> (F-213 / SF-213-01) : 200 complet, 206 pour une plage (lecture
 * progressive, avance rapide), 416 hors fichier. Les octets passent du stockage au client <b>en flux</b> —
 * jamais une vidéo entière en mémoire.
 */
@Component
public class VideoMediaServer {

    private static final int BUFFER = 64 * 1024;

    private final VideoService videoService;
    private final VideoStore store;

    public VideoMediaServer(VideoService videoService, VideoStore store) {
        this.videoService = videoService;
        this.store = store;
    }

    /**
     * Sert le média d'une version d'une vidéo du compte.
     *
     * @throws VideoNotFoundException si la vidéo, la version ou l'objet est absent pour ce compte
     */
    public void serveMedia(UUID userId, UUID videoId, Integer version, String range, boolean download,
            HttpServletResponse response) throws IOException {
        Video video = videoService.require(userId, videoId);
        VideoVersion wanted = videoService.requireVersion(userId, videoId, version);
        OptionalLong size = store.size(wanted.getStorageKey());
        if (size.isEmpty()) {
            throw new VideoNotFoundException();
        }
        long length = size.getAsLong();
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=3600");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentType(wanted.getMime());
        if (download) {
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(fileName(video, wanted), java.nio.charset.StandardCharsets.UTF_8).build().toString());
        }
        List<HttpRange> ranges = parse(range);
        long start = 0;
        long end = length - 1;
        if (!ranges.isEmpty()) {
            HttpRange first = ranges.get(0);
            if (isUnsatisfiable(first, length)) {
                response.reset();
                response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + length);
                return;
            }
            start = first.getRangeStart(length);
            end = first.getRangeEnd(length);
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length);
        } else {
            response.setStatus(HttpServletResponse.SC_OK);
        }
        response.setContentLengthLong(end - start + 1);
        try (InputStream in = store.openRange(wanted.getStorageKey(), start, end).orElseThrow(VideoNotFoundException::new)) {
            OutputStream out = response.getOutputStream();
            byte[] buffer = new byte[BUFFER];
            long remaining = end - start + 1;
            int read;
            while (remaining > 0 && (read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining))) > 0) {
                out.write(buffer, 0, read);
                remaining -= read;
            }
            out.flush();
        }
    }

    /**
     * Sert la couverture d'une version d'une vidéo du compte.
     *
     * @throws VideoNotFoundException si elle n'en a pas
     */
    public void servePoster(UUID userId, UUID videoId, Integer version, HttpServletResponse response)
            throws IOException {
        VideoVersion wanted = videoService.requireVersion(userId, videoId, version);
        if (wanted.getPosterKey() == null) {
            throw new VideoNotFoundException();
        }
        byte[] poster = store.get(wanted.getPosterKey()).orElseThrow(VideoNotFoundException::new);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(wanted.getPosterMime());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=3600");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentLength(poster.length);
        response.getOutputStream().write(poster);
    }

    /** {@code titre-de-la-video-v2.mp4}. */
    static String fileName(Video video, VideoVersion version) {
        String ext = VideoMediaTypes.extension(version.getStorageKey());
        return fr.claudegateway.pages.PageService.slug(video.getTitle()) + "-v" + version.getVersion() + "." + ext;
    }

    private static boolean isUnsatisfiable(HttpRange range, long length) {
        try {
            return range.getRangeStart(length) >= length;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    /** Un {@code Range} absent ou mal formé est traité comme « pas de Range » (réponse complète). */
    private static List<HttpRange> parse(String range) {
        if (range == null || range.isBlank()) {
            return List.of();
        }
        try {
            return HttpRange.parseRanges(range);
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
    }
}
