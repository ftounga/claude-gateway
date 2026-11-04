package fr.claudegateway.jarvis.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.push.PushEvent;
import fr.claudegateway.push.PushNotificationService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-243 / SF-243-01 — lire un agenda par son adresse iCal contre un serveur HTTP local : ajout (vérifié avant écriture),
 * adresse chiffrée et jamais renvoyée, texte scellé, 304 et même empreinte ⇒ aucune analyse, changement ⇒ cache
 * remplacé, 404 ⇒ {@code BROKEN} et une seule notification, garde anti-SSRF, isolation A/B, retrait, purge.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisCalendarFeedIntegrationTest {

    static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JarvisCalendarFeedService feedService;
    @Autowired private JarvisCalendarPoller poller;
    @Autowired private JarvisCalendarStore store;
    @Autowired private JarvisCalendarEventRepository events;
    @Autowired private PushNotificationService realPush;

    private final ObjectMapper json = new ObjectMapper();

    private User user(String prefix) {
        return userRepository.save(User.builder().email(prefix + "-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
    }

    private String bearer(User u) {
        return "Bearer " + jwtService.generateToken(u);
    }

    static String ics(String title) {
        LocalDate tomorrow = LocalDate.now(PARIS).plusDays(1);
        String d = tomorrow.format(DAY);
        return ("BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:test\nX-WR-TIMEZONE:Europe/Paris\n"
                + "BEGIN:VEVENT\nDTSTART;TZID=Europe/Paris:" + d + "T140000\nDTEND;TZID=Europe/Paris:" + d
                + "T150000\nUID:rdv-" + title.hashCode() + "@google.com\nSUMMARY:" + title + "\nLOCATION:Salle secrète\n"
                + "END:VEVENT\n"
                + "BEGIN:VEVENT\nDTSTART;TZID=Europe/Paris:" + d + "T090000\nDTEND;TZID=Europe/Paris:" + d
                + "T093000\nRRULE:FREQ=WEEKLY;COUNT=3\nUID:hebdo@google.com\nSUMMARY:Point hebdo\nEND:VEVENT\n"
                + "END:VCALENDAR\n").replace("\n", "\r\n");
    }

    private CalendarTestServer.Route route(String body, String etag) {
        CalendarTestServer.Route r = new CalendarTestServer.Route();
        r.body = body;
        r.etag = etag;
        return r;
    }

    private String link(User u, String url, String label) throws Exception {
        return mockMvc.perform(post("/api/jarvis/calendar/feeds").contextPath("/api")
                        .header("Authorization", bearer(u)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("url", url, "label", label))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private long feedRows(UUID userId) {
        return jdbc.queryForObject("select count(*) from jarvis_calendar_feeds where user_id = ?", Long.class, userId);
    }

    private long eventRows(UUID userId) {
        return jdbc.queryForObject("select count(*) from jarvis_calendar_events where user_id = ?", Long.class, userId);
    }

    @Test
    void linkReadsSealsAndNeverReturnsTheAddress() throws Exception {
        User a = user("cal");
        String path = "/calendar/ical/pro%40ng-itconsulting.com/private-" + UUID.randomUUID() + "/basic.ics";
        String url = CalendarTestServer.route(path, route(ics("Point devis Helios"), "\"v1\""));
        String body = link(a, url, "Pro");
        assertThat(body).doesNotContain("private-").doesNotContain("127.0.0.1");
        assertThat(json.readTree(body).get("eventCount").asInt()).isEqualTo(4);
        assertThat(json.readTree(body).get("status").asText()).isEqualTo("OK");

        // Aucune colonne ne porte l'adresse ni le titre en clair.
        List<Map<String, Object>> raw = jdbc.queryForList("select * from jarvis_calendar_feeds where user_id = ?",
                a.getId());
        assertThat(raw).hasSize(1);
        assertThat(raw.get(0).values().stream().map(String::valueOf)).noneMatch(v -> v.contains("private-"));
        List<Map<String, Object>> ev = jdbc.queryForList("select * from jarvis_calendar_events where user_id = ?",
                a.getId());
        assertThat(ev).hasSize(4);
        assertThat(ev).allSatisfy(r -> assertThat(r.values().stream().map(String::valueOf))
                .noneMatch(v -> v.contains("Helios") || v.contains("Salle secrète") || v.contains("Point hebdo")));
        // …mais le serveur sait le relire.
        OffsetDateTime now = OffsetDateTime.now();
        List<JarvisCalendarEvent> mine = events.overlapping(a.getId(), now.minusDays(1), now.plusDays(30));
        assertThat(mine.stream().map(e -> store.open(e).title())).contains("Point devis Helios", "Point hebdo");

        mockMvc.perform(get("/api/jarvis/calendar/feeds").contextPath("/api").header("Authorization", bearer(a)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].label").value("Pro"))
                .andExpect(jsonPath("$[0].secretLast4").isNotEmpty())
                .andExpect(jsonPath("$[0].url").doesNotExist());

        // Ajouter deux fois la même adresse : une seule ligne, libellé changé.
        link(a, url, "Pro (travail)");
        assertThat(feedRows(a.getId())).isEqualTo(1);
    }

    @Test
    void wrongAddressesWriteNothing() throws Exception {
        User a = user("calko");
        CalendarTestServer.Route gone = new CalendarTestServer.Route();
        gone.status = 404;
        String goneUrl = CalendarTestServer.route("/calendar/ical/gone/basic.ics", gone);
        mockMvc.perform(post("/api/jarvis/calendar/feeds").contextPath("/api").header("Authorization", bearer(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("url", goneUrl))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("calendar_feed_gone"));
        String html = CalendarTestServer.route("/calendar/ical/html/basic.ics", route("<html>nope</html>", null));
        mockMvc.perform(post("/api/jarvis/calendar/feeds").contextPath("/api").header("Authorization", bearer(a))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("url", html))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("calendar_unreadable"));
        // Garde anti-SSRF : hôte non admis, schéma non admis, identifiants dans l'adresse — refusés sans appel réseau.
        for (String bad : List.of("https://evil.example.com/calendar/ical/x/basic.ics", "file:///etc/passwd",
                "https://user:pw@calendar.google.com/calendar/ical/x/basic.ics", "ftp://calendar.google.com/x",
                "http://169.254.169.254/latest/meta-data/")) {
            mockMvc.perform(post("/api/jarvis/calendar/feeds").contextPath("/api").header("Authorization", bearer(a))
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("url", bad))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_calendar_feed"));
        }
        assertThat(feedRows(a.getId())).isZero();
        assertThat(eventRows(a.getId())).isZero();
    }

    @Test
    void unchangedFeedIsNeverParsedChangedFeedIsReplaced() throws Exception {
        User a = user("calpoll");
        CalendarTestServer.Route r = route(ics("Premier titre"), "\"e1\"");
        String url = CalendarTestServer.route("/calendar/ical/poll-" + UUID.randomUUID() + "/basic.ics", r);
        UUID id = UUID.fromString(json.readTree(link(a, url, "Perso")).get("id").asText());

        // ETag identique ⇒ 304 ⇒ aucune analyse.
        assertThat(poller.poll(id)).isEqualTo(JarvisCalendarPoller.Outcome.UNCHANGED);
        assertThat(r.conditional.get()).isEqualTo(1);
        // Sans ETag, même corps ⇒ même empreinte ⇒ aucune analyse.
        r.etag = null;
        jdbc.update("update jarvis_calendar_feeds set etag = null where id = ?", id);
        assertThat(poller.poll(id)).isEqualTo(JarvisCalendarPoller.Outcome.UNCHANGED);
        Map<String, Object> day = jdbc.queryForMap("select calendar_polls, calendar_polls_unchanged "
                + "from jarvis_usage_days where user_id = ?", a.getId());
        assertThat(((Number) day.get("calendar_polls")).intValue()).isEqualTo(2);
        assertThat(((Number) day.get("calendar_polls_unchanged")).intValue()).isEqualTo(2);

        // Le flux change ⇒ le cache est remplacé.
        r.body = ics("Second titre");
        assertThat(poller.poll(id)).isEqualTo(JarvisCalendarPoller.Outcome.UPDATED);
        OffsetDateTime now = OffsetDateTime.now();
        assertThat(events.overlapping(a.getId(), now.minusDays(1), now.plusDays(30)).stream()
                .map(e -> store.open(e).title())).contains("Second titre").doesNotContain("Premier titre");
        assertThat(eventRows(a.getId())).isEqualTo(4);

        // Bail tenu par un autre pod : sauté.
        jdbc.update("update jarvis_calendar_feeds set lease_until = ?, next_poll_at = ? where id = ?",
                now.plusMinutes(5), now.minusMinutes(1), id);
        assertThat(poller.poll(id)).isEqualTo(JarvisCalendarPoller.Outcome.SKIPPED);
        jdbc.update("update jarvis_calendar_feeds set lease_until = null where id = ?", id);

        // La fenêtre glisse : un flux inchangé est redéployé après 24 h.
        jdbc.update("update jarvis_calendar_feeds set expanded_at = ? where id = ?", now.minusHours(25), id);
        assertThat(poller.poll(id)).isEqualTo(JarvisCalendarPoller.Outcome.UPDATED);
    }

    @Test
    void notFoundBreaksTheFeedWithASingleNotification() throws Exception {
        User a = user("calbroken");
        CalendarTestServer.Route r = route(ics("Rendez-vous"), null);
        String url = CalendarTestServer.route("/calendar/ical/broken-" + UUID.randomUUID() + "/basic.ics", r);
        UUID id = UUID.fromString(json.readTree(link(a, url, "Perso")).get("id").asText());
        PushNotificationService push = mock(PushNotificationService.class);
        poller.setPush(push);
        try {
            r.status = 404;
            assertThat(poller.poll(id)).isEqualTo(JarvisCalendarPoller.Outcome.BROKEN);
            jdbc.update("update jarvis_calendar_feeds set next_poll_at = ? where id = ?",
                    OffsetDateTime.now().minusMinutes(1), id);
            assertThat(poller.poll(id)).isEqualTo(JarvisCalendarPoller.Outcome.BROKEN);
            verify(push, times(1)).notify(eq(a.getId()), any(), eq(PushEvent.CALENDAR_BROKEN), eq("/jarvis"));
            assertThat(jdbc.queryForObject("select status from jarvis_calendar_feeds where id = ?", String.class, id))
                    .isEqualTo("BROKEN");
            // Les occurrences connues restent lisibles ; l'adresse revient ⇒ OK.
            assertThat(eventRows(a.getId())).isEqualTo(4);
            r.status = 200;
            mockMvc.perform(post("/api/jarvis/calendar/feeds/" + id + "/test").contextPath("/api")
                            .header("Authorization", bearer(a)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OK"));
        } finally {
            poller.setPush(realPush);
        }
    }

    @Test
    void isolationRemovalAndPurge() throws Exception {
        User a = user("cala");
        User b = user("calb");
        String url = CalendarTestServer.route("/calendar/ical/iso-" + UUID.randomUUID() + "/basic.ics",
                route(ics("Confidentiel"), null));
        UUID id = UUID.fromString(json.readTree(link(a, url, "Pro")).get("id").asText());

        mockMvc.perform(get("/api/jarvis/calendar/feeds").contextPath("/api").header("Authorization", bearer(b)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(post("/api/jarvis/calendar/feeds/" + id + "/test").contextPath("/api")
                .header("Authorization", bearer(b))).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/jarvis/calendar/feeds/" + id).contextPath("/api")
                .header("Authorization", bearer(b))).andExpect(status().isNotFound());
        OffsetDateTime now = OffsetDateTime.now();
        assertThat(events.overlapping(b.getId(), now.minusDays(1), now.plusDays(30))).isEmpty();
        assertThat(feedRows(a.getId())).isEqualTo(1);
        mockMvc.perform(get("/api/jarvis/calendar/feeds").contextPath("/api")).andExpect(status().isUnauthorized());

        // Retirer : l'agenda et ses occurrences.
        mockMvc.perform(delete("/api/jarvis/calendar/feeds/" + id).contextPath("/api")
                .header("Authorization", bearer(a))).andExpect(status().isNoContent());
        assertThat(feedRows(a.getId())).isZero();
        assertThat(eventRows(a.getId())).isZero();

        // Purge du compte.
        link(a, url, "Pro");
        assertThat(eventRows(a.getId())).isPositive();
        feedService.purgeUser(a.getId());
        assertThat(feedRows(a.getId())).isZero();
        assertThat(eventRows(a.getId())).isZero();
    }
}
