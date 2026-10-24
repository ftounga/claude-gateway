package fr.claudegateway.videos;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Les retours à la seconde d'une vidéo du compte</b> (F-214 / SF-214-01). Tout est filtré par le compte
 * courant et la vidéo possédée (404 sinon).
 */
@RestController
@RequestMapping("/videos/{videoId}/comments")
public class VideoCommentController {

    private final VideoCommentService service;
    private final CurrentUser currentUser;

    public VideoCommentController(VideoCommentService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    /** Les retours d'une version, ou de toutes. */
    @GetMapping
    public List<CommentResponse> list(@PathVariable UUID videoId, @RequestParam(required = false) Integer version) {
        return service.list(currentUser.requireId(), videoId, version).stream().map(CommentResponse::of).toList();
    }

    /** Pose un retour (instant ou plage). */
    @PostMapping
    public ResponseEntity<CommentResponse> create(@PathVariable UUID videoId, @RequestBody CreateRequest body) {
        if (body == null) {
            throw new VideoRejectedException("Corps attendu : {version?, tStartMs, tEndMs?, text}.");
        }
        VideoComment created = service.create(currentUser.requireId(), videoId, body.version(), body.tStartMs(),
                body.tEndMs(), body.text());
        return ResponseEntity.status(HttpStatus.CREATED).body(CommentResponse.of(created));
    }

    /** Modifie le texte d'un retour ouvert, ou le rouvre. */
    @PatchMapping("/{commentId}")
    public CommentResponse update(@PathVariable UUID videoId, @PathVariable UUID commentId,
            @RequestBody UpdateRequest body) {
        return CommentResponse.of(service.update(currentUser.requireId(), videoId, commentId,
                body == null ? null : body.text(), body == null ? null : body.status()));
    }

    /** Supprime un retour. */
    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID videoId, @PathVariable UUID commentId) {
        service.delete(currentUser.requireId(), videoId, commentId);
        return ResponseEntity.noContent().build();
    }

    /** « Envoyer à l'agent » / « Copier » : le message composé, les retours passés ENVOYE. */
    @PostMapping("/send")
    public SendResponse send(@PathVariable UUID videoId, @RequestBody(required = false) SendRequest body) {
        VideoCommentService.Sent sent = service.send(currentUser.requireId(), videoId,
                body == null ? null : body.version());
        return new SendResponse(sent.message(), sent.comments().stream().map(CommentResponse::of).toList());
    }

    /** Le corps de la création. */
    public record CreateRequest(Integer version, Long tStartMs, Long tEndMs, String text) {
    }

    /** Le corps de la modification. */
    public record UpdateRequest(String text, String status) {
    }

    /** Le corps de l'envoi. */
    public record SendRequest(Integer version) {
    }

    /** La réponse de l'envoi. */
    public record SendResponse(String message, List<CommentResponse> comments) {
    }

    /** Un retour tel que l'écran le lit. */
    public record CommentResponse(UUID id, UUID videoId, int version, long tStartMs, Long tEndMs, int frame,
            Integer endFrame, String text, String status, String resolution, Integer resolvedVersion,
            OffsetDateTime sentAt, OffsetDateTime createdAt) {

        static CommentResponse of(VideoComment c) {
            return new CommentResponse(c.getId(), c.getVideoId(), c.getVersion(), c.getStartMs(), c.getEndMs(),
                    c.getFrame(), c.getEndMs() == null ? null : VideoCommentService.frame(c.getEndMs()), c.getText(),
                    c.getStatus().name(), c.getResolution(), c.getResolvedVersion(), c.getSentAt(), c.getCreatedAt());
        }
    }
}
