package fr.claudegateway.jarvis.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.jarvis.thread.JarvisThreadService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-243 / SF-243-03 — poser un rendez-vous dans l'agenda pro contre un Google bouchonné : parcours OAuth (state lié au
 * compte et au navigateur, usage unique, PKCE), jeton chiffré, {@code agenda_poser} seulement dans le fil Jarvis, rien
 * sans « oui », « oui » ⇒ un événement sans invité et {@code sendUpdates=none}, 401 ⇒ rafraîchissement, un « oui » qui
 * n'est pas celui de l'utilisateur ne vaut rien, révocation, isolation A/B.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisCalendarWriteIntegrationTest {

    @org.springframework.test.context.DynamicPropertySource
    static void google(org.springframework.test.context.DynamicPropertyRegistry registry) {
        GoogleTestServer.register(registry);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JarvisThreadService threads;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private JarvisCalendarToolCatalog tools;
    @Autowired private JarvisCalendarActionService actions;
    @Autowired private JarvisCalendarGrantService grants;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void reset() {
        GoogleTestServer.reset();
    }

    private User user(String prefix) {
        return userRepository.save(User.builder().email(prefix + "-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
    }

    private String bearer(User u) {
        return "Bearer " + jwtService.generateToken(u);
    }

    /** Démarre le parcours : rend le state lu dans l'adresse de consentement et le témoin du navigateur. */
    private String[] start(User u) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/jarvis/calendar/oauth/start").contextPath("/api")
                .header("Authorization", bearer(u))).andExpect(status().isOk()).andReturn();
        String url = json.readTree(r.getResponse().getContentAsString()).get("url").asText();
        assertThat(url).startsWith(GoogleTestServer.base() + "/auth?").contains("access_type=offline")
                .contains("code_challenge_method=S256").contains("calendar.events").doesNotContain("test-secret");
        String state = null;
        for (String kv : url.substring(url.indexOf('?') + 1).split("&")) {
            if (kv.startsWith("state=")) {
                state = URLDecoder.decode(kv.substring(6), StandardCharsets.UTF_8);
            }
        }
        jakarta.servlet.http.Cookie cookie = r.getResponse().getCookie(JarvisCalendarController.OAUTH_COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        return new String[] { state, cookie.getValue() };
    }

    private void link(User u) throws Exception {
        String[] s = start(u);
        mockMvc.perform(get("/api/jarvis/calendar/oauth/callback").contextPath("/api").param("state", s[0])
                        .param("code", "good-code")
                        .cookie(new jakarta.servlet.http.Cookie(JarvisCalendarController.OAUTH_COOKIE, s[1])))
                .andExpect(status().isFound()).andExpect(header().string("Location", "/jarvis?agenda=relie"));
    }

    private JsonNode input(String start, String end) throws Exception {
        return json.readTree(json.writeValueAsString(Map.of("titre", "Point devis Helios", "debut", start, "fin", end,
                "lieu", "Visio", "description", "", "courriel", "")));
    }

    private static String tomorrowAt(int hour) {
        return LocalDate.now(ZoneId.of("Europe/Paris")).plusDays(1).atTime(hour, 0).toString();
    }

    @Test
    void oauthStateIsBoundToTheUserAndTheBrowserAndServedOnce() throws Exception {
        User a = user("oauth");
        String[] s = start(a);
        // Autre navigateur (témoin absent ou différent) : refusé, et le state est consommé (aucun rejeu).
        mockMvc.perform(get("/api/jarvis/calendar/oauth/callback").contextPath("/api").param("state", s[0])
                        .param("code", "good-code")
                        .cookie(new jakarta.servlet.http.Cookie(JarvisCalendarController.OAUTH_COOKIE, "autre")))
                .andExpect(status().isFound()).andExpect(header().string("Location", "/jarvis?agenda=refuse"));
        mockMvc.perform(get("/api/jarvis/calendar/oauth/callback").contextPath("/api").param("state", s[0])
                        .param("code", "good-code")
                        .cookie(new jakarta.servlet.http.Cookie(JarvisCalendarController.OAUTH_COOKIE, s[1])))
                .andExpect(header().string("Location", "/jarvis?agenda=refuse"));
        // State inventé : refusé.
        mockMvc.perform(get("/api/jarvis/calendar/oauth/callback").contextPath("/api").param("state", "invente")
                .param("code", "good-code")).andExpect(header().string("Location", "/jarvis?agenda=refuse"));
        assertThat(grants.list(a.getId())).isEmpty();

        // Le bon parcours : jeton chiffré, jamais renvoyé, adresse lue dans le jeton d'identité.
        link(a);
        mockMvc.perform(get("/api/jarvis/calendar/grants").contextPath("/api").header("Authorization", bearer(a)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.grants[0].account").value("pro@ng-itconsulting.com"));
        List<Map<String, Object>> raw = jdbc.queryForList("select * from jarvis_calendar_grants where user_id = ?",
                a.getId());
        assertThat(raw).hasSize(1);
        assertThat(raw.get(0).values().stream().map(String::valueOf)).noneMatch(v -> v.contains("refresh-secret"));
        String body = mockMvc.perform(get("/api/jarvis/calendar/grants").contextPath("/api")
                .header("Authorization", bearer(a))).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("refresh-secret").doesNotContain("access-");
        // Le PKCE a voyagé à l'échange.
        assertThat(GoogleTestServer.RECEIVED).anySatisfy(r -> assertThat(r.body()).contains("code_verifier="));

        // Un state de A présenté avec le témoin de B (autre compte, autre navigateur) : refusé.
        User b = user("oauthb");
        String[] sa = start(a);
        String[] sb = start(b);
        mockMvc.perform(get("/api/jarvis/calendar/oauth/callback").contextPath("/api").param("state", sa[0])
                        .param("code", "good-code")
                        .cookie(new jakarta.servlet.http.Cookie(JarvisCalendarController.OAUTH_COOKIE, sb[1])))
                .andExpect(header().string("Location", "/jarvis?agenda=refuse"));
        assertThat(grants.list(b.getId())).isEmpty();
    }

    @Test
    void posesOnlyOnTheUsersYesWithoutGuestsAndWithoutMail() throws Exception {
        User a = user("poser");
        Workspace thread = threads.open(a.getId());
        // Sans droit : l'outil existe dans le fil Jarvis mais dit « non relié ».
        assertThat(tools.toolsFor(a.getId(), thread)).extracting(t -> t.name())
                .containsExactly("agenda_poser", "agenda_repondre");
        assertThat(tools.execute(a.getId(), thread, "agenda_poser", input(tomorrowAt(14), tomorrowAt(15))).content())
                .contains("Agenda pro non relié");
        link(a);

        // Jamais hors du fil Jarvis.
        Workspace subject = workspaces.save(Workspace.builder().userId(a.getId()).name("Sujet client").build());
        assertThat(tools.toolsFor(a.getId(), subject)).isEmpty();
        assertThat(tools.execute(a.getId(), subject, "agenda_poser", input(tomorrowAt(14), tomorrowAt(15))).error())
                .isTrue();

        JarvisCalendarToolCatalog.Result proposed = tools.execute(a.getId(), thread, "agenda_poser",
                input(tomorrowAt(14), tomorrowAt(15)));
        assertThat(proposed.error()).isFalse();
        assertThat(proposed.content()).contains("14 h – 15 h").contains("« Point devis Helios »")
                .contains("agenda pro").contains("Vous confirmez ?");
        assertThat(GoogleTestServer.RECEIVED).noneMatch(r -> r.path().endsWith("/events"));

        // « Oui, mais… » n'est pas un oui : annulé, rien n'est posé.
        assertThat(actions.onUserMessage(a.getId(), thread.getId(), UUID.randomUUID(), "oui mais plutôt à 16 h"))
                .contains("n'est pas posé");
        assertThat(GoogleTestServer.RECEIVED).noneMatch(r -> r.path().endsWith("/events"));

        // Nouvelle proposition, puis « oui » : posé une seule fois, après un 401 qui rafraîchit le jeton.
        tools.execute(a.getId(), thread, "agenda_poser", input(tomorrowAt(14), tomorrowAt(15)));
        GoogleTestServer.unauthorizedLeft.set(1);
        UUID yes = UUID.randomUUID();
        assertThat(actions.onUserMessage(a.getId(), thread.getId(), yes, "Oui")).contains("posé");
        assertThat(actions.onUserMessage(a.getId(), thread.getId(), yes, "Oui")).isEmpty();
        List<GoogleTestServer.Received> creations = GoogleTestServer.RECEIVED.stream()
                .filter(r -> r.path().endsWith("/calendars/primary/events")).toList();
        assertThat(creations).hasSize(2); // le 401, puis la création réussie
        GoogleTestServer.Received created = creations.get(1);
        assertThat(created.query()).isEqualTo("sendUpdates=none");
        JsonNode event = json.readTree(created.body());
        assertThat(event.has("attendees")).isFalse();
        assertThat(event.get("summary").asText()).isEqualTo("Point devis Helios");
        assertThat(event.get("start").get("dateTime").asText()).isEqualTo(tomorrowAt(14));
        assertThat(event.get("start").get("timeZone").asText()).isEqualTo("Europe/Paris");
        assertThat(event.get("description").asText()).contains("Posé par Jarvis");
        assertThat(created.authorization()).isNotEqualTo(creations.get(0).authorization());
        assertThat(jdbc.queryForObject("select status from jarvis_calendar_actions where user_id = ? "
                + "and google_event_id = 'evt-123'", String.class, a.getId())).isEqualTo("EXECUTED");
    }

    @Test
    void aLockedPhoneYesPosesNothingUntilAnUnlockedYes() throws Exception {
        // F-232 / SF-232-06 (D8) — téléphone du compagnon verrouillé : la passerelle ne pose rien sur « oui ».
        User a = user("verrou");
        Workspace thread = threads.open(a.getId());
        link(a);
        assertThat(tools.execute(a.getId(), thread, "agenda_poser", input(tomorrowAt(10), tomorrowAt(11))).error())
                .isFalse();

        String locked = actions.onUserMessage(a.getId(), thread.getId(), UUID.randomUUID(), "Oui", true);
        assertThat(locked).contains("téléphone verrouillé").contains("n'est pas posé").contains("reste en attente");
        assertThat(GoogleTestServer.RECEIVED).noneMatch(r -> r.path().endsWith("/calendars/primary/events"));
        assertThat(jdbc.queryForObject("select status from jarvis_calendar_actions where user_id = ?", String.class,
                a.getId())).isEqualTo("PENDING");

        assertThat(actions.onUserMessage(a.getId(), thread.getId(), UUID.randomUUID(), "Oui", false)).contains("posé");
        assertThat(GoogleTestServer.RECEIVED.stream().filter(r -> r.path().endsWith("/calendars/primary/events")))
                .hasSize(1);
    }

    @Test
    void aYesFromAnotherUserOrAnotherThreadIsWorthNothingAndRevocationClearsTheToken() throws Exception {
        User a = user("iso");
        User b = user("isob");
        Workspace thread = threads.open(a.getId());
        Workspace threadB = threads.open(b.getId());
        link(a);
        tools.execute(a.getId(), thread, "agenda_poser", input(tomorrowAt(10), tomorrowAt(11)));
        // B dit « oui » dans son fil : rien chez A.
        assertThat(actions.onUserMessage(b.getId(), threadB.getId(), UUID.randomUUID(), "oui")).isEmpty();
        assertThat(actions.onUserMessage(b.getId(), thread.getId(), UUID.randomUUID(), "oui")).isEmpty();
        assertThat(GoogleTestServer.RECEIVED).noneMatch(r -> r.path().endsWith("/events"));
        // B ne voit ni ne révoque le droit de A.
        UUID grantId = grants.list(a.getId()).get(0).id();
        mockMvc.perform(get("/api/jarvis/calendar/grants").contextPath("/api").header("Authorization", bearer(b)))
                .andExpect(jsonPath("$.grants.length()").value(0));
        mockMvc.perform(delete("/api/jarvis/calendar/grants/" + grantId).contextPath("/api")
                .header("Authorization", bearer(b))).andExpect(status().isNotFound());

        // A révoque : révocation chez Google, jeton et action en attente effacés ; l'outil dit « non relié ».
        mockMvc.perform(delete("/api/jarvis/calendar/grants/" + grantId).contextPath("/api")
                .header("Authorization", bearer(a))).andExpect(status().isNoContent());
        assertThat(GoogleTestServer.RECEIVED).anySatisfy(r -> {
            assertThat(r.path()).isEqualTo("/revoke");
            assertThat(r.body()).contains("refresh-secret-xyz");
        });
        assertThat(jdbc.queryForObject("select count(*) from jarvis_calendar_grants where user_id = ?", Long.class,
                a.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from jarvis_calendar_actions where user_id = ?", Long.class,
                a.getId())).isZero();
        assertThat(tools.execute(a.getId(), thread, "agenda_poser", input(tomorrowAt(10), tomorrowAt(11))).content())
                .contains("Agenda pro non relié");

        // Un droit révoqué chez Google (invalid_grant) : effacé, la pose échoue proprement.
        link(a);
        tools.execute(a.getId(), thread, "agenda_poser", input(tomorrowAt(12), tomorrowAt(13)));
        GoogleTestServer.refreshRevoked = true;
        grants.purgeCacheForTests();
        assertThat(actions.onUserMessage(a.getId(), thread.getId(), UUID.randomUUID(), "oui"))
                .contains("ÉCHEC").contains("non relié");
        assertThat(grants.list(a.getId())).isEmpty();
    }

    @Test
    void invalidSlotsAreRefusedBeforeAnyProposal() throws Exception {
        User a = user("slot");
        Workspace thread = threads.open(a.getId());
        link(a);
        assertThat(tools.execute(a.getId(), thread, "agenda_poser", input("jeudi 14h", "")).content())
                .contains("Heure illisible");
        assertThat(tools.execute(a.getId(), thread, "agenda_poser", input(tomorrowAt(15), tomorrowAt(14))).content())
                .contains("Créneau invalide");
        assertThat(tools.execute(a.getId(), thread, "agenda_poser", input("2020-01-01T10:00", "")).content())
                .contains("passé");
        assertThat(jdbc.queryForObject("select count(*) from jarvis_calendar_actions where user_id = ?", Long.class,
                a.getId())).isZero();
    }
}
