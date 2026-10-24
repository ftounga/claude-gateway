package fr.claudegateway.videos;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * <b>Lire les vidéos de son compte</b> (F-213 / SF-213-01). Aucune route de dépôt : une vidéo n'entre que par
 * l'outil de l'agent. Tout est filtré par le compte courant (404 sinon).
 */
@RestController
@RequestMapping("/videos")
public class VideoController {

    private final VideoService videoService;
    private final VideoViewTicketService tickets;
    private final VideoMediaServer mediaServer;
    private final CurrentUser currentUser;

    public VideoController(VideoService videoService, VideoViewTicketService tickets, VideoMediaServer mediaServer,
            CurrentUser currentUser) {
        this.videoService = videoService;
        this.tickets = tickets;
        this.mediaServer = mediaServer;
        this.currentUser = currentUser;
    }

    /** Une vidéo du compte, version courante ou demandée, avec ses adresses de lecture. */
    @GetMapping("/{id}")
    public VideoResponse get(@PathVariable UUID id, @RequestParam(required = false) Integer version,
            HttpServletRequest request) {
        UUID userId = currentUser.requireId();
        Video video = videoService.require(userId, id);
        VideoVersion served = videoService.requireVersion(userId, id, version);
        return VideoResponse.of(video, served, videoService.series(userId, video.getSeriesId()).orElse(null),
                mediaBase(request, tickets.issue(userId, id, served.getVersion())));
    }

    /** Les versions conservées, la plus récente d'abord. */
    @GetMapping("/{id}/versions")
    public List<VideoResponse.VersionResponse> versions(@PathVariable UUID id) {
        return videoService.versions(currentUser.requireId(), id).stream()
                .map(VideoResponse.VersionResponse::of).toList();
    }

    /** Le média d'une version, avec {@code Range} (JWT). */
    @GetMapping("/{id}/versions/{version}/media")
    public void media(@PathVariable UUID id, @PathVariable int version,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
            @RequestParam(value = "download", required = false) boolean download,
            HttpServletResponse response) throws IOException {
        mediaServer.serveMedia(currentUser.requireId(), id, version, range, download, response);
    }

    /** L'adresse de lecture par ticket : {@code /api/video-media/{ticket}}. */
    static String mediaBase(HttpServletRequest request, String ticket) {
        return request.getContextPath() + VideoMediaController.PATH + "/" + ticket;
    }
}
