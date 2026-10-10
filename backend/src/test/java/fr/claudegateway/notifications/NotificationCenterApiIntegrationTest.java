package fr.claudegateway.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.push.PushEvent;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Le centre de notifications, de bout en bout (F-185 / SF-185-04) : ce que voit la cloche, et
 * surtout ce qu'elle ne voit pas — les notifications d'un autre compte.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationCenterApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private UserNotificationRepository repository;
    @Autowired private NotificationCenterService center;
    @Autowired private JwtService jwtService;

    private User alice;
    private User bob;
    private String aliceToken;
    private String bobToken;
    private Workspace aliceTerminal;

    @BeforeEach
    void setUp() {
        alice = userRepository.save(user("notif-alice-" + UUID.randomUUID() + "@example.com"));
        bob = userRepository.save(user("notif-bob-" + UUID.randomUUID() + "@example.com"));
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(bob);
        aliceTerminal = workspaceRepository.save(Workspace.builder().userId(alice.getId()).name("alarm4tech")
                .createdAt(OffsetDateTime.now()).build());
    }

    private static User user(String email) {
        return User.builder().email(email).emailVerified(true).provider(AuthProvider.LOCAL)
                .role(UserRole.USER).createdAt(OffsetDateTime.now()).build();
    }

    @Test
    void theBellShowsTheAccountsNotificationsWithTheSubjectNewestFirst() throws Exception {
        center.record(alice.getId(), aliceTerminal.getId(), PushEvent.QUESTION_ASKED, false);
        center.record(alice.getId(), aliceTerminal.getId(), PushEvent.TURN_DONE, false);
        center.record(alice.getId(), aliceTerminal.getId(), PushEvent.PLAN_AWAITING, true);

        mockMvc.perform(get("/api/notifications").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(2))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].event").value("PLAN_AWAITING"))
                .andExpect(jsonPath("$.items[0].read").value(true))
                .andExpect(jsonPath("$.items[1].title").value("Une réponse est prête"))
                .andExpect(jsonPath("$.items[1].subject").value("alarm4tech"))
                .andExpect(jsonPath("$.items[1].workspaceId").value(aliceTerminal.getId().toString()));
    }

    @Test
    void anotherAccountSeesNothingAndCannotMarkAliceNotificationRead() throws Exception {
        center.record(alice.getId(), aliceTerminal.getId(), PushEvent.TURN_DONE, false);
        UUID id = repository.findAll().stream().filter(n -> n.getUserId().equals(alice.getId()))
                .findFirst().orElseThrow().getId();

        mockMvc.perform(get("/api/notifications").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(post("/api/notifications/" + id + "/read").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/notifications/read-all").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNoContent());

        assertThat(repository.countByUserIdAndReadAtIsNull(alice.getId())).isEqualTo(1);
    }

    @Test
    void aliceMarksOneThenAllRead() throws Exception {
        center.record(alice.getId(), aliceTerminal.getId(), PushEvent.TURN_DONE, false);
        center.record(alice.getId(), aliceTerminal.getId(), PushEvent.QUESTION_ASKED, false);
        UUID id = repository.findAll().stream().filter(n -> n.getUserId().equals(alice.getId()))
                .findFirst().orElseThrow().getId();

        mockMvc.perform(post("/api/notifications/" + id + "/read").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());
        assertThat(repository.countByUserIdAndReadAtIsNull(alice.getId())).isEqualTo(1);

        mockMvc.perform(post("/api/notifications/read-all").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());
        assertThat(repository.countByUserIdAndReadAtIsNull(alice.getId())).isZero();
    }

    @Test
    void anUnknownIdIsNotFoundAndAMalformedOneIsRejected() throws Exception {
        mockMvc.perform(post("/api/notifications/" + UUID.randomUUID() + "/read").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/notifications/pas-un-uuid/read").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withoutATokenTheBellIsRefused() throws Exception {
        mockMvc.perform(get("/api/notifications").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void notificationsOlderThanThirtyDaysArePurgedOnRecordForThisAccountOnly() {
        repository.save(UserNotification.builder().userId(alice.getId()).event("TURN_DONE")
                .createdAt(OffsetDateTime.now().minusDays(31)).build());
        repository.save(UserNotification.builder().userId(bob.getId()).event("TURN_DONE")
                .createdAt(OffsetDateTime.now().minusDays(31)).build());

        center.record(alice.getId(), null, PushEvent.TURN_DONE, false);

        assertThat(repository.findAll()).filteredOn(n -> n.getUserId().equals(alice.getId())).hasSize(1);
        assertThat(repository.findAll()).filteredOn(n -> n.getUserId().equals(bob.getId())).hasSize(1);
    }

    @Test
    void aTerminalOfAnotherAccountGivesNoSubject() {
        Workspace bobs = workspaceRepository.save(Workspace.builder().userId(bob.getId()).name("secret")
                .createdAt(OffsetDateTime.now()).build());

        center.record(alice.getId(), bobs.getId(), PushEvent.TURN_DONE, false);

        assertThat(center.list(alice.getId()).items()).singleElement()
                .satisfies(item -> assertThat(item.subject()).isNull());
    }

    @Test
    void anUnknownEventCodeShowsAsIs() {
        assertThat(NotificationCenterService.title("FUTURE_EVENT")).isEqualTo("FUTURE_EVENT");
        assertThat(NotificationCenterService.title("TURN_DONE")).isEqualTo("Une réponse est prête");
    }
}
