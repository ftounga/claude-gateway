package fr.claudegateway.jarvis.clientmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.calendar.JarvisCalendarEventRepository;
import fr.claudegateway.jarvis.calendar.JarvisCalendarFeedRepository;
import fr.claudegateway.jarvis.calendar.JarvisCalendarReader;
import fr.claudegateway.jarvis.calendar.JarvisCalendarStore;
import fr.claudegateway.jarvis.mail.JarvisMailItemRepository;
import fr.claudegateway.jarvis.notices.JarvisNoticeProperties;
import fr.claudegateway.jarvis.notices.JarvisNoticeRepository;
import fr.claudegateway.jarvis.notices.JarvisNoticeService;
import fr.claudegateway.runner.RunnerIdentity;
import fr.claudegateway.runner.RunnerTokenAuthenticator;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.shared.jobs.AsyncJobQueue;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-249 / SF-249-04 — les invitations des mails déposés dans l'agenda tous clients (REQUEST récurrente, CANCEL, mise à
 * jour, ordre inversé, vue fusionnée, outil sans flux) et les alertes avant réunion (délai, une fois, délai 0), isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClientMeetingsIntegrationTest {

    @MockitoBean private RunnerTokenAuthenticator authenticator;
    @MockitoBean private AiAgentProvider agentProvider;
    /** La file durable est traitée par le test lui-même : le travailleur planifié ne doit pas la lui prendre. */
    @MockitoBean private fr.claudegateway.shared.jobs.AsyncJobWorker jobWorker;

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private ClientMailDepositJobHandler handler;
    @Autowired private AsyncJobQueue queue;
    @Autowired private JarvisCalendarReader reader;
    @Autowired private ClientMeetingRepository meetings;
    @Autowired private ClientMeetingService meetingService;
    @Autowired private JarvisCalendarEventRepository events;
    @Autowired private JarvisCalendarFeedRepository feeds;
    @Autowired private JarvisCalendarStore store;
    @Autowired private MeetingAlertRepository alerts;
    @Autowired private ClientMailSettingsService settings;
    @Autowired private ClientMailSettingsRepository settingsRepo;
    @Autowired private JarvisNoticeRepository noticeRepo;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private JarvisMailItemRepository items;

    private User alice;
    private User bob;
    private RunnerHost edenred;
    private final AtomicReference<Instant> now = new AtomicReference<>();
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now.get(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    @BeforeEach
    void setUp() {
        alice = user("meet-a");
        bob = user("meet-b");
        edenred = hosts.save(RunnerHost.builder().userId(alice.getId()).name("EDENRED").rootName("dev").os("linux")
                .shell("posix").elevated(false).build());
        when(authenticator.authenticate(eq("tok-a")))
                .thenReturn(Optional.of(new RunnerIdentity(UUID.randomUUID(), alice.getId(), edenred.getId())));
        when(agentProvider.nextTurn(any(AgentTurnRequest.class))).thenReturn(new AgentTurn(
                "[{\"id\":\"m1\",\"classe\":\"A_LIRE\",\"raison\":\"Invitation\"}]", List.of(), true, 100, 10));
    }

    private User user(String p) {
        return users.save(User.builder().email(p + "-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build());
    }

    private static String utc(OffsetDateTime t) {
        return DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(t.withOffsetSameInstant(ZoneOffset.UTC));
    }

    private void deposit(String messageId, OffsetDateTime sent, String method, String uid, OffsetDateTime start,
            String rrule, String summary) throws Exception {
        String ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//FR\r\nMETHOD:" + method + "\r\nBEGIN:VEVENT\r\n"
                + "UID:" + uid + "\r\nDTSTAMP:" + utc(sent) + "\r\nDTSTART:" + utc(start) + "\r\nDTEND:"
                + utc(start.plusMinutes(30)) + "\r\n" + (rrule == null ? "" : "RRULE:" + rrule + "\r\n")
                + "SUMMARY:" + summary + "\r\nLOCATION:Teams\r\nATTENDEE:mailto:pirate@x.com\r\n"
                + ("CANCEL".equals(method) ? "STATUS:CANCELLED\r\n" : "") + "END:VEVENT\r\nEND:VCALENDAR\r\n";
        String eml = "From: Rémi <remi@edenred.com>\r\nSubject: Invitation " + summary + "\r\nMessage-ID: " + messageId
                + "\r\nDate: " + DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH).format(sent)
                + "\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=B\r\n\r\n--B\r\nContent-Type: "
                + "text/plain\r\n\r\nInvitation\r\n--B\r\nContent-Type: text/calendar; method=" + method + "\r\n\r\n" + ics
                + "--B--\r\n";
        mvc.perform(post("/runner/mail-deposit").contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(eml.getBytes(StandardCharsets.UTF_8)).header("X-File-Name", "m.eml")
                .header("X-Runner-Token", "tok-a")).andExpect(status().isOk());
        for (AsyncJobQueue.Job job : queue.claim(ClientMailDepositService.JOB_KIND, 50, "t")) {
            handler.handle(job);
            queue.complete(job.id(), "t", 1, java.math.BigDecimal.ZERO);
        }
    }

    private List<JarvisCalendarReader.Item> week(User u) {
        OffsetDateTime from = OffsetDateTime.now().minusHours(1);
        return reader.between(u.getId(), from, from.plusDays(14)).items();
    }

    @Test
    @DisplayName("REQUEST hebdomadaire ⇒ occurrences « Outlook EDENRED » sans flux iCal ; invités jamais lus ; B ne voit rien")
    void requestShowsInMergedAgenda() throws Exception {
        OffsetDateTime start = OffsetDateTime.now().plusDays(1).withNano(0).withSecond(0);
        deposit("<i1@x>", OffsetDateTime.now().minusMinutes(5), "REQUEST", "uid-weekly", start, "FREQ=WEEKLY;COUNT=3",
                "Point Fortec");
        List<JarvisCalendarReader.Item> mine = week(alice);
        assertThat(mine).hasSize(2);
        assertThat(mine.get(0).feedLabel()).isEqualTo("Outlook EDENRED");
        assertThat(mine.get(0).title()).isEqualTo("Point Fortec");
        assertThat(meetings.findByUserIdAndIcalUid(alice.getId(), "uid-weekly")).allMatch(m -> !m.getSealed()
                .contains("pirate"));
        assertThat(week(bob)).isEmpty();
        assertThat(reader.toolText(alice.getId(), "demain", null, null, false)).contains("Point Fortec");
    }

    @Test
    @DisplayName("Mise à jour (même UID) remplace ; plus ancienne ignorée ; CANCEL retire")
    void updateOlderAndCancel() throws Exception {
        OffsetDateTime start = OffsetDateTime.now().plusDays(2).withNano(0).withSecond(0);
        deposit("<u1@x>", OffsetDateTime.now().minusHours(2), "REQUEST", "uid-u", start, null, "Revue");
        deposit("<u2@x>", OffsetDateTime.now().minusHours(1), "REQUEST", "uid-u", start.plusHours(3), null, "Revue");
        assertThat(week(alice)).extracting(JarvisCalendarReader.Item::start)
                .containsExactly(start.plusHours(3).withOffsetSameInstant(ZoneOffset.UTC));
        deposit("<u0@x>", OffsetDateTime.now().minusHours(5), "REQUEST", "uid-u", start.minusHours(1), null, "Revue");
        assertThat(week(alice)).hasSize(1);
        deposit("<u3@x>", OffsetDateTime.now().minusMinutes(1), "CANCEL", "uid-u", start.plusHours(3), null, "Revue");
        assertThat(week(alice)).isEmpty();
    }

    @Test
    @DisplayName("Alertes : réunion à +8 min avec délai 10 ⇒ une annonce, une seule fois ; délai 0 ⇒ rien")
    void alerts() {
        now.set(Instant.now());
        JarvisNoticeService notices = new JarvisNoticeService(noticeRepo, new JarvisNoticeProperties(true), workspaces,
                clock);
        ClientMeetingAlerts job = new ClientMeetingAlerts(meetings, meetingService, events, feeds, store, alerts,
                settings, hosts, notices, clock);
        String ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//FR\r\nMETHOD:REQUEST\r\nBEGIN:VEVENT\r\nUID:uid-soon\r\n"
                + "DTSTAMP:" + utc(OffsetDateTime.now()) + "\r\nDTSTART:" + utc(OffsetDateTime.now().plusMinutes(8))
                + "\r\nDTEND:" + utc(OffsetDateTime.now().plusMinutes(38)) + "\r\nSUMMARY:Point Fortec\r\nEND:VEVENT\r\n"
                + "END:VCALENDAR\r\n";
        meetingService.apply(alice.getId(), edenred.getId(), null, ics.getBytes(StandardCharsets.UTF_8),
                OffsetDateTime.now());
        assertThat(job.run()).isEqualTo(1);
        assertThat(job.run()).isZero();
        JarvisNoticeService.NoticeView v = notices.open(alice.getId()).get(0);
        assertThat(v.text()).isEqualTo("Réunion « Point Fortec » (EDENRED) dans 8 min");
        assertThat(v.speech()).isEqualTo("« Point Fortec » commence dans huit minutes.");
        assertThat(notices.open(bob.getId())).isEmpty();

        ClientMailSettings s = ClientMailSettings.defaults(alice.getId());
        s.setMeetingAlertMinutes(0);
        s.setUpdatedAt(OffsetDateTime.now());
        settingsRepo.save(s);
        String other = ics.replace("uid-soon", "uid-later");
        meetingService.apply(alice.getId(), edenred.getId(), null, other.getBytes(StandardCharsets.UTF_8),
                OffsetDateTime.now());
        assertThat(job.run()).isZero();
    }
}
