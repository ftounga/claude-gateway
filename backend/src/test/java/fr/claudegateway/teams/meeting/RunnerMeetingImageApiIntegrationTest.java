package fr.claudegateway.teams.meeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceSource;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.runner.RunnerTokenRepository;
import fr.claudegateway.runner.RunnerTokenService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** Dépôt des images clés d'une réunion (F-128 / SF-128-03) : gardes, isolation, et compteur rattaché. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerMeetingImageApiIntegrationTest {

    private static final String HEADER = "X-Runner-Token";
    private static final byte[] IMAGE = "jpeg-bytes".getBytes(StandardCharsets.UTF_8);

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private RunnerTokenRepository runnerTokenRepository;
    @Autowired private RunnerTokenService tokenService;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private MeetingRepository meetingRepository;

    private String aliceToken;
    private UUID aliceTerminal;
    private UUID aliceProject;
    private UUID aliceMeeting;
    private UUID bobTerminal;
    private String carolToken;
    private UUID carolTerminal;
    private UUID carolMeeting;

    @BeforeEach
    void setUp() {
        meetingRepository.deleteAll();
        runnerTokenRepository.deleteAll();
        workspaceRepository.deleteAll();
        hostRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        SecurityContextHolder.clearContext();

        User alice = seedUser("alice-img@example.com");
        entitle(alice.getId());
        UUID aliceHost = seedHost(alice.getId());
        aliceToken = tokenService.issue(alice.getId(), aliceHost, "poste-alice").clearToken();
        aliceTerminal = seedTerminal(alice.getId(), aliceHost, true);
        aliceProject = seedTerminal(alice.getId(), aliceHost, false);
        aliceMeeting = seedMeeting(alice.getId(), aliceHost);

        User bob = seedUser("bob-img@example.com");
        entitle(bob.getId());
        UUID bobHost = seedHost(bob.getId());
        bobTerminal = seedTerminal(bob.getId(), bobHost, true);

        User carol = seedUser("carol-img@example.com");
        UUID carolHost = seedHost(carol.getId());
        carolToken = tokenService.issue(carol.getId(), carolHost, "poste-carol").clearToken();
        carolTerminal = seedTerminal(carol.getId(), carolHost, true);
        carolMeeting = seedMeeting(carol.getId(), carolHost);
    }

    private String url(UUID meetingId, UUID workspaceId) {
        return "/api/runner/teams/meetings/" + meetingId + "/images?workspaceId=" + workspaceId;
    }

    @Test
    @DisplayName("nominal : l'image est stockée et le compteur monte")
    void imageIsStored() throws Exception {
        mockMvc.perform(post(url(aliceMeeting, aliceTerminal)).contextPath("/api")
                        .header(HEADER, aliceToken).contentType("image/jpeg").content(IMAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageCount").value(1));

        assertThat(meetingRepository.findById(aliceMeeting).orElseThrow().getImageCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("sans jeton : 401")
    void withoutTokenUnauthorized() throws Exception {
        mockMvc.perform(post(url(aliceMeeting, aliceTerminal)).contextPath("/api")
                        .contentType("image/jpeg").content(IMAGE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ISOLATION : terminal d'un autre compte → 404")
    void otherAccountTerminalNotFound() throws Exception {
        mockMvc.perform(post(url(aliceMeeting, bobTerminal)).contextPath("/api")
                        .header(HEADER, aliceToken).contentType("image/jpeg").content(IMAGE))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("réunion inconnue → 404")
    void unknownMeetingNotFound() throws Exception {
        mockMvc.perform(post(url(UUID.randomUUID(), aliceTerminal)).contextPath("/api")
                        .header(HEADER, aliceToken).contentType("image/jpeg").content(IMAGE))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("sans option Teams → 403")
    void withoutTeamsOptionForbidden() throws Exception {
        mockMvc.perform(post(url(carolMeeting, carolTerminal)).contextPath("/api")
                        .header(HEADER, carolToken).contentType("image/jpeg").content(IMAGE))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("workspace de projet (non Teams) → 400")
    void projectWorkspaceRejected() throws Exception {
        mockMvc.perform(post(url(aliceMeeting, aliceProject)).contextPath("/api")
                        .header(HEADER, aliceToken).contentType("image/jpeg").content(IMAGE))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("image vide → 400")
    void emptyImageRejected() throws Exception {
        mockMvc.perform(post(url(aliceMeeting, aliceTerminal)).contextPath("/api")
                        .header(HEADER, aliceToken).contentType("image/jpeg").content(new byte[0]))
                .andExpect(status().isBadRequest());
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
    }

    private void entitle(UUID userId) {
        subscriptionRepository.save(Subscription.builder().userId(userId).planCode(PlanCode.PRO)
                .status(SubscriptionStatus.ACTIVE).teamsOptionStatus(SubscriptionStatus.ACTIVE).build());
    }

    private UUID seedHost(UUID userId) {
        return hostRepository.save(RunnerHost.builder().userId(userId).name("Poste").build()).getId();
    }

    private UUID seedTerminal(UUID userId, UUID hostId, boolean teamsTerminal) {
        return workspaceRepository.save(Workspace.builder().userId(userId).hostId(hostId)
                .name(teamsTerminal ? "Terminal Teams" : "Projet").projectPath("")
                .source(WorkspaceSource.LOCAL).teamsTerminal(teamsTerminal).build()).getId();
    }

    private UUID seedMeeting(UUID userId, UUID hostId) {
        return meetingRepository.save(Meeting.builder().userId(userId).hostId(hostId)
                .state(MeetingState.RECORDING).meetingUrl("https://teams.microsoft.com/x")
                .consentAcknowledged(true).retentionDays(30).startedAt(OffsetDateTime.now())
                .build()).getId();
    }
}
