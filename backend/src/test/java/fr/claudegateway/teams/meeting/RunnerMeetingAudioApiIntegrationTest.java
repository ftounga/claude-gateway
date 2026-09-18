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

/**
 * Dépôt de l'audio d'une réunion par le runner (F-128 / SF-128-02) : jeton runner (401), isolation
 * user_id+host_id (404), option Teams (403), terminal Teams (400), bornes (400/413), et stockage effectif
 * rattaché à l'artefact.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerMeetingAudioApiIntegrationTest {

    private static final String HEADER = "X-Runner-Token";
    private static final byte[] AUDIO = "webm-opus-bytes".getBytes(StandardCharsets.UTF_8);

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private RunnerTokenRepository runnerTokenRepository;
    @Autowired private RunnerTokenService tokenService;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private MeetingRepository meetingRepository;

    private UUID aliceId;
    private String aliceToken;
    private UUID aliceHost;
    private UUID aliceTerminal;
    private UUID aliceProject;
    private UUID aliceMeeting;

    private String bobToken;
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

        User alice = seedUser("alice-audio@example.com");
        aliceId = alice.getId();
        entitle(aliceId);
        aliceHost = seedHost(aliceId);
        aliceToken = tokenService.issue(aliceId, aliceHost, "poste-alice").clearToken();
        aliceTerminal = seedTerminal(aliceId, aliceHost, true);
        aliceProject = seedTerminal(aliceId, aliceHost, false);
        aliceMeeting = seedMeeting(aliceId, aliceHost);

        User bob = seedUser("bob-audio@example.com");
        entitle(bob.getId());
        UUID bobHost = seedHost(bob.getId());
        bobToken = tokenService.issue(bob.getId(), bobHost, "poste-bob").clearToken();
        bobTerminal = seedTerminal(bob.getId(), bobHost, true);

        User carol = seedUser("carol-audio@example.com"); // pas d'option Teams
        UUID carolHost = seedHost(carol.getId());
        carolToken = tokenService.issue(carol.getId(), carolHost, "poste-carol").clearToken();
        carolTerminal = seedTerminal(carol.getId(), carolHost, true);
        carolMeeting = seedMeeting(carol.getId(), carolHost);
    }

    private String url(UUID meetingId, UUID workspaceId) {
        return "/api/runner/teams/meetings/" + meetingId + "/audio?workspaceId=" + workspaceId;
    }

    @Test
    @DisplayName("nominal : l'audio est stocké et rattaché à la réunion")
    void audioIsStored() throws Exception {
        mockMvc.perform(post(url(aliceMeeting, aliceTerminal)).contextPath("/api")
                        .header(HEADER, aliceToken).contentType("audio/webm").content(AUDIO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audioBytes").value(AUDIO.length));

        Meeting stored = meetingRepository.findById(aliceMeeting).orElseThrow();
        assertThat(stored.getAudioKey()).isNotBlank();
        assertThat(stored.getAudioBytes()).isEqualTo((long) AUDIO.length);
    }

    @Test
    @DisplayName("sans jeton runner : 401 générique")
    void withoutTokenUnauthorized() throws Exception {
        mockMvc.perform(post(url(aliceMeeting, aliceTerminal)).contextPath("/api")
                        .contentType("audio/webm").content(AUDIO))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ISOLATION : le terminal d'un autre compte est introuvable (404)")
    void otherAccountTerminalNotFound() throws Exception {
        mockMvc.perform(post(url(aliceMeeting, bobTerminal)).contextPath("/api")
                        .header(HEADER, aliceToken).contentType("audio/webm").content(AUDIO))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("réunion inconnue pour ce compte : 404")
    void unknownMeetingNotFound() throws Exception {
        mockMvc.perform(post(url(UUID.randomUUID(), aliceTerminal)).contextPath("/api")
                        .header(HEADER, aliceToken).contentType("audio/webm").content(AUDIO))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("sans option Teams : produire est refusé (403)")
    void withoutTeamsOptionForbidden() throws Exception {
        mockMvc.perform(post(url(carolMeeting, carolTerminal)).contextPath("/api")
                        .header(HEADER, carolToken).contentType("audio/webm").content(AUDIO))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("workspace de projet (non Teams) : 400")
    void projectWorkspaceRejected() throws Exception {
        mockMvc.perform(post(url(aliceMeeting, aliceProject)).contextPath("/api")
                        .header(HEADER, aliceToken).contentType("audio/webm").content(AUDIO))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("audio vide : 400")
    void emptyAudioRejected() throws Exception {
        mockMvc.perform(post(url(aliceMeeting, aliceTerminal)).contextPath("/api")
                        .header(HEADER, aliceToken).contentType("audio/webm").content(new byte[0]))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- montage

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
                .name(teamsTerminal ? "Terminal Teams" : "Projet de code").projectPath("")
                .source(WorkspaceSource.LOCAL).teamsTerminal(teamsTerminal).build()).getId();
    }

    private UUID seedMeeting(UUID userId, UUID hostId) {
        return meetingRepository.save(Meeting.builder().userId(userId).hostId(hostId)
                .state(MeetingState.RECORDING).meetingUrl("https://teams.microsoft.com/x")
                .consentAcknowledged(true).retentionDays(30).startedAt(OffsetDateTime.now())
                .build()).getId();
    }
}
