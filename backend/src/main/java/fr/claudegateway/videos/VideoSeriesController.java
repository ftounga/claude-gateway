package fr.claudegateway.videos;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/** <b>Les séries de son compte</b> (F-213 / SF-213-03) : la suppression d'une série et de ses épisodes. */
@RestController
@RequestMapping("/video-series")
public class VideoSeriesController {

    private final VideoService videoService;
    private final CurrentUser currentUser;

    public VideoSeriesController(VideoService videoService, CurrentUser currentUser) {
        this.videoService = videoService;
        this.currentUser = currentUser;
    }

    /** Supprime une série du compte et tous ses épisodes (objets compris). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        videoService.deleteSeries(currentUser.requireId(), id);
        return ResponseEntity.noContent().build();
    }
}
