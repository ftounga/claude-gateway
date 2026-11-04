package fr.claudegateway.jarvis.calendar;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Les agendas de Jarvis</b> (F-243 / SF-243-01) — {@code /api/jarvis/calendar/feeds}, authentifié, sur le seul
 * {@code user_id} du principal. <b>Aucun point d'accès administrateur</b> aux agendas d'autrui. L'adresse iCal n'est
 * jamais renvoyée.
 */
@RestController
public class JarvisCalendarController {

    /** {@code {"url": "https://calendar.google.com/calendar/ical/…/basic.ics", "label": "Pro"}}. */
    public record LinkRequest(String url, String label) {
    }

    private final JarvisCalendarFeedService feeds;
    private final JarvisCalendarReader reader;
    private final CurrentUser currentUser;

    public JarvisCalendarController(JarvisCalendarFeedService feeds, JarvisCalendarReader reader,
            CurrentUser currentUser) {
        this.feeds = feeds;
        this.reader = reader;
        this.currentUser = currentUser;
    }

    /**
     * F-243 / SF-243-02 (D10, D11) — les rendez-vous fusionnés de [from, to[ (ISO-8601, 31 jours au plus) ; rien en
     * présentation (409).
     */
    @GetMapping("/jarvis/calendar/events")
    public ResponseEntity<JarvisCalendarReader.Window> events(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) Boolean presentation) {
        if (Boolean.TRUE.equals(presentation)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return ResponseEntity.ok(reader.between(currentUser.requireId(), from, to));
    }

    @GetMapping("/jarvis/calendar/feeds")
    public List<JarvisCalendarFeedService.View> list() {
        return feeds.list(currentUser.requireId());
    }

    @PostMapping("/jarvis/calendar/feeds")
    public ResponseEntity<JarvisCalendarFeedService.View> link(@RequestBody(required = false) LinkRequest body) {
        if (body == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(feeds.link(currentUser.requireId(), body.url(), body.label()));
    }

    @PostMapping("/jarvis/calendar/feeds/{id}/test")
    public JarvisCalendarFeedService.View test(@PathVariable UUID id) {
        return feeds.test(currentUser.requireId(), id);
    }

    @DeleteMapping("/jarvis/calendar/feeds/{id}")
    public ResponseEntity<Void> remove(@PathVariable UUID id) {
        feeds.remove(currentUser.requireId(), id);
        return ResponseEntity.noContent().build();
    }
}
