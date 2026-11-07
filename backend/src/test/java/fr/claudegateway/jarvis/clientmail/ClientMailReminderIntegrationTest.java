package fr.claudegateway.jarvis.clientmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.jarvis.mail.JarvisMailItemRepository;
import fr.claudegateway.jarvis.notices.JarvisNoticeRepository;
import fr.claudegateway.jarvis.notices.JarvisNoticeService;
import fr.claudegateway.jarvis.notices.JarvisNoticeProperties;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-249 / SF-249-05 — rappel du soir (horloge réglable) : trois rappels puis silence, idempotence, le dépôt fait taire,
 * samedi, client désactivé, premier dépôt actif ; routes GET/PUT (400, 404) ; isolation A/B.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClientMailReminderIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private ClientMailSettingsRepository settingsRepo;
    @Autowired private ClientMailHostRepository clientHosts;
    @Autowired private ClientMailReminderDayRepository days;
    @Autowired private ClientMailDepositRepository deposits;
    @Autowired private JarvisMailItemRepository items;
    @Autowired private JarvisNoticeRepository noticeRepo;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private ClientMailProperties properties;
    @Autowired private JwtService jwt;

    private final AtomicReference<Instant> now = new AtomicReference<>();
    private ClientMailSettingsService settings;
    private ClientMailReminder reminder;
    private JarvisNoticeService notices;
    private User alice;
    private User bob;
    private RunnerHost edenred;

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
        notices = new JarvisNoticeService(noticeRepo, new JarvisNoticeProperties(true), workspaces, clock);
        settings = new ClientMailSettingsService(settingsRepo, clientHosts, days, deposits, items, hosts, notices, clock);
        reminder = new ClientMailReminder(clientHosts, days, settings, hosts, notices, properties, clock);
        alice = user("rem-a");
        bob = user("rem-b");
        edenred = hosts.save(RunnerHost.builder().userId(alice.getId()).name("EDENRED").rootName("dev").os("linux")
                .shell("posix").elevated(false).runnerCapabilities("files,mail_deposit").build());
    }

    private User user(String p) {
        return users.save(User.builder().email(p + "-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build());
    }

    /** Heure de Paris un vendredi (UTC+1 en novembre). */
    private void at(String parisTime) {
        now.set(OffsetDateTime.parse("2026-11-06T" + parisTime + ":00+01:00").toInstant());
    }

    private long reminders() {
        return notices.open(alice.getId()).stream().filter(n -> n.kind().equals("DEPOSIT_REMINDER")).count();
    }

    @Test
    @DisplayName("Trois rappels (18:30, 19:15, 20:00) puis silence ; un seul par échéance")
    void threeThenSilence() {
        at("10:00");
        settings.setActive(alice.getId(), edenred.getId(), true);
        at("18:29");
        assertThat(reminder.remind(alice.getId(), edenred.getId())).isFalse();
        at("18:30");
        assertThat(reminder.remind(alice.getId(), edenred.getId())).isTrue();
        assertThat(reminder.remind(alice.getId(), edenred.getId())).isFalse();
        assertThat(notices.open(alice.getId()).get(0).text()).isEqualTo("Déposez vos mails EDENRED");
        at("19:15");
        assertThat(reminder.remind(alice.getId(), edenred.getId())).isTrue();
        at("20:00");
        assertThat(reminder.remind(alice.getId(), edenred.getId())).isTrue();
        at("21:30");
        assertThat(reminder.remind(alice.getId(), edenred.getId())).isFalse();
        assertThat(reminders()).isEqualTo(1);
    }

    @Test
    @DisplayName("Un dépôt à 19:00 ferme l'annonce et empêche le rappel de 19:15 ; le premier dépôt rend le client actif")
    void depositSilences() {
        at("18:40");
        settings.onDeposit(alice.getId(), edenred.getId());
        assertThat(clientHosts.findByUserIdAndHostId(alice.getId(), edenred.getId()).orElseThrow().isReminderEnabled())
                .isTrue();
        assertThat(reminder.remind(alice.getId(), edenred.getId())).isFalse();

        RunnerHost cagip = hosts.save(RunnerHost.builder().userId(alice.getId()).name("CAGIP").rootName("dev")
                .os("linux").shell("posix").elevated(false).build());
        settings.setActive(alice.getId(), cagip.getId(), true);
        at("18:30");
        assertThat(reminder.remind(alice.getId(), cagip.getId())).isTrue();
        assertThat(reminders()).isEqualTo(1);
        at("19:00");
        settings.onDeposit(alice.getId(), cagip.getId());
        assertThat(reminders()).isZero();
        at("19:15");
        assertThat(reminder.remind(alice.getId(), cagip.getId())).isFalse();
    }

    @Test
    @DisplayName("Samedi (jours ouvrés) : rien ; client désactivé : rien")
    void saturdayAndDisabled() {
        at("10:00");
        settings.setActive(alice.getId(), edenred.getId(), true);
        now.set(OffsetDateTime.parse("2026-11-07T19:00:00+01:00").toInstant());
        assertThat(reminder.remind(alice.getId(), edenred.getId())).isFalse();
        at("19:00");
        settings.setActive(alice.getId(), edenred.getId(), false);
        assertThat(reminder.remind(alice.getId(), edenred.getId())).isFalse();
    }

    @Test
    @DisplayName("Routes : état par poste, réglages bornés (400), poste d'autrui (404) ; B ne voit pas les postes de A")
    void routes() throws Exception {
        String a = "Bearer " + jwt.generateToken(alice);
        String b = "Bearer " + jwt.generateToken(bob);
        mvc.perform(get("/jarvis/client-mail").header("Authorization", a)).andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.reminderTime").value("18:30"))
                .andExpect(jsonPath("$.clients[0].name").value("EDENRED"))
                .andExpect(jsonPath("$.clients[0].upToDate").value(true))
                .andExpect(jsonPath("$.clients[0].folder").value("dev/jarvis/mails/"))
                .andExpect(jsonPath("$.clients[0].active").value(false));
        mvc.perform(put("/jarvis/client-mail/settings").header("Authorization", a)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reminderTime\":\"19:00\",\"reminderMax\":2,"
                        + "\"reminderEveryMinutes\":30,\"weekdaysOnly\":false,\"meetingAlertMinutes\":5}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reminderTime").value("19:00"));
        mvc.perform(put("/jarvis/client-mail/settings").header("Authorization", a)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reminderTime\":\"25:00\",\"reminderMax\":2,"
                        + "\"reminderEveryMinutes\":30,\"weekdaysOnly\":false,\"meetingAlertMinutes\":5}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/jarvis/client-mail/settings").header("Authorization", a)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reminderTime\":\"19:00\",\"reminderMax\":5,"
                        + "\"reminderEveryMinutes\":30,\"weekdaysOnly\":false,\"meetingAlertMinutes\":5}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/jarvis/client-mail/hosts/" + edenred.getId()).header("Authorization", b)
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}")).andExpect(status().isNotFound());
        mvc.perform(put("/jarvis/client-mail/hosts/" + edenred.getId()).header("Authorization", a)
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        mvc.perform(get("/jarvis/client-mail").header("Authorization", b))
                .andExpect(jsonPath("$.clients.length()").value(0))
                .andExpect(jsonPath("$.settings.reminderTime").value("18:30"));
    }
}
