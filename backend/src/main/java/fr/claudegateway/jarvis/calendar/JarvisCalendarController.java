package fr.claudegateway.jarvis.calendar;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
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

    /** F-243 / SF-243-03 — le témoin qui lie le parcours OAuth au navigateur (HttpOnly, 10 min). */
    public static final String OAUTH_COOKIE = "cg_cal_oauth";
    static final String OAUTH_PATH = "/api/jarvis/calendar/oauth";

    /** SF-243-03 — l'état de l'écriture : configurée ou non, les droits (jamais un jeton). */
    public record Grants(boolean configured, List<JarvisCalendarGrantService.View> grants) {
    }

    private final JarvisCalendarFeedService feeds;
    private final JarvisCalendarReader reader;
    private final JarvisCalendarGrantService grants;
    private final CurrentUser currentUser;

    public JarvisCalendarController(JarvisCalendarFeedService feeds, JarvisCalendarReader reader,
            JarvisCalendarGrantService grants, CurrentUser currentUser) {
        this.feeds = feeds;
        this.reader = reader;
        this.grants = grants;
        this.currentUser = currentUser;
    }

    /** F-243 / SF-243-03 (D3) — les droits d'écriture du compte. */
    @GetMapping("/jarvis/calendar/grants")
    public Grants grants() {
        return new Grants(grants.configured(), grants.list(currentUser.requireId()));
    }

    /**
     * F-243 / SF-243-03 (D3) — le départ du parcours OAuth : l'adresse de consentement, et le témoin {@code HttpOnly}
     * qui lie le {@code state} à ce navigateur. 503 si le client n'est pas configuré.
     */
    @GetMapping("/jarvis/calendar/oauth/start")
    public ResponseEntity<Map<String, String>> start() {
        try {
            JarvisCalendarGrantService.Start s = grants.start(currentUser.requireId());
            ResponseCookie cookie = ResponseCookie.from(OAUTH_COOKIE, s.browserNonce()).httpOnly(true).secure(true)
                    .sameSite("Lax").path(OAUTH_PATH).maxAge(JarvisCalendarGrantService.STATE_TTL).build();
            return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(Map.of("url", s.url()));
        } catch (JarvisCalendarGrantService.NotConfiguredException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "calendar_oauth_not_configured", "message", e.getMessage()));
        }
    }

    /**
     * F-243 / SF-243-03 (D3) — le retour de Google (navigation de premier niveau : aucun jeton d'API). Le compte est
     * celui qui a démarré le parcours ({@code state}), et seulement depuis le même navigateur (témoin). Toujours une
     * redirection vers {@code /jarvis} : {@code agenda=relie} ou {@code agenda=refuse} (aucun détail).
     */
    @GetMapping("/jarvis/calendar/oauth/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String state,
            @RequestParam(required = false) String code, @RequestParam(required = false) String error,
            @CookieValue(name = OAUTH_COOKIE, required = false) String browser) {
        String outcome;
        try {
            grants.callback(state, code, error, browser);
            outcome = "relie";
        } catch (RuntimeException e) {
            outcome = "refuse";
        }
        ResponseCookie clear = ResponseCookie.from(OAUTH_COOKIE, "").httpOnly(true).secure(true).sameSite("Lax")
                .path(OAUTH_PATH).maxAge(0).build();
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.SET_COOKIE, clear.toString())
                .header(HttpHeaders.LOCATION, "/jarvis?agenda=" + outcome).build();
    }

    /** F-243 / SF-243-03 (D3) — révoquer : chez Google, puis effacement du jeton. */
    @DeleteMapping("/jarvis/calendar/grants/{id}")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        grants.revoke(currentUser.requireId(), id);
        return ResponseEntity.noContent().build();
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
