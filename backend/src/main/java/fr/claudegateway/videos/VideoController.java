package fr.claudegateway.videos;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /**
     * La galerie d'un sujet ({@code workspaceId}) ou d'un poste ({@code hostId}) du compte (SF-213-03) : séries
     * et vidéos seules, jamais les sons.
     */
    @GetMapping
    public VideoGalleryResponse gallery(@RequestParam(required = false) UUID workspaceId,
            @RequestParam(required = false) UUID hostId, HttpServletRequest request) {
        UUID userId = currentUser.requireId();
        VideoService.Gallery gallery = videoService.gallery(userId, workspaceId, hostId);
        return VideoGalleryResponse.of(gallery, version -> mediaBase(request,
                tickets.issue(userId, version.getVideoId(), version.getVersion())) + "/poster");
    }

    /** Supprime une vidéo du compte, ses versions et ses objets (SF-213-03). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        videoService.delete(currentUser.requireId(), id);
        return ResponseEntity.noContent().build();
    }

    /** La durée lue par le navigateur corrige celle annoncée (SF-213-03). */
    @PutMapping("/{id}/duration")
    public ResponseEntity<Void> duration(@PathVariable UUID id, @RequestBody DurationRequest body) {
        videoService.setDuration(currentUser.requireId(), id, body == null || body.durationS() == null ? 0
                : body.durationS());
        return ResponseEntity.noContent().build();
    }

    /** Le corps de {@code PUT /videos/{id}/duration}. */
    public record DurationRequest(Double durationS) {
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
