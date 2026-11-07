package fr.claudegateway.jarvis.notices;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Les annonces d'interface</b> (F-249 / SF-249-03) — lues par l'annonceur (sondage), fermées d'un « OK », marquées
 * « dites » par Jarvis. Toujours sous le compte du principal ; l'annonce d'autrui est introuvable (404).
 */
@RestController
public class JarvisNoticeController {

    private final JarvisNoticeService notices;
    private final CurrentUser currentUser;

    public JarvisNoticeController(JarvisNoticeService notices, CurrentUser currentUser) {
        this.notices = notices;
        this.currentUser = currentUser;
    }

    @GetMapping("/jarvis/notices")
    public List<JarvisNoticeService.NoticeView> open() {
        return notices.open(currentUser.requireId());
    }

    @PostMapping("/jarvis/notices/{id}/seen")
    public JarvisNoticeService.NoticeView seen(@PathVariable UUID id) {
        return notices.seen(currentUser.requireId(), id);
    }

    @PostMapping("/jarvis/notices/{id}/spoken")
    public JarvisNoticeService.NoticeView spoken(@PathVariable UUID id) {
        return notices.spoken(currentUser.requireId(), id);
    }

    @ExceptionHandler(JarvisNoticeService.NoticeNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(JarvisNoticeService.NoticeNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found", "message", e.getMessage()));
    }
}
