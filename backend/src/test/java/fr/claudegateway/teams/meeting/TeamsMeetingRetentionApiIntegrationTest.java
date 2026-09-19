package fr.claudegateway.teams.meeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.runner.host.ClientSpace;
import fr.claudegateway.runner.host.HostSpace;
import fr.claudegateway.runner.host.HostSpaceRepository;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Rétention & purge des médias de réunion (F-128 / SF-128-07) : purge manuelle (endpoint), worker de
 * rétention, gardes et isolation. Les médias vivent dans le stockage in-memory du profil test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeamsMeetingRetentionApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private HostSpaceRepository hostSpaces;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private MeetingRepository meetingRepository;
    @Autowired private MeetingMediaService mediaService;
    @Autowired private MeetingRetentionService retentionService;

    @MockBean private AIProvider aiProvider;

    private String aliceToken;
    private UUID aliceHost;
    private UUID aliceHostNoVigie;
    private String bobToken;
    private String carolToken;
    private UUID carolHost;

    @BeforeEach
    void setUp() {
        meetingRepository.deleteAll();
        hostSpaces.deleteAll();
        hostRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        SecurityContextHolder.clearContext();

        User alice = seedUser("alice-ret@example.com");
        entitleTeams(alice.getId());
        aliceToken = jwtService.generateToken(alice);
        aliceHost = seedHost(alice.getId(), true);
        aliceHostNoVigie = seedHost(alice.getId(), false);

        User bob = seedUser("bob-ret@example.com");
        entitleTeams(bob.getId());
        bobToken = jwtService.generateToken(bob);

        User carol = seedUser("carol-ret@example.com");
        carolToken = jwtService.generateToken(carol);
        carolHost = seedHost(carol.getId(), true);
    }

    @Test
    @DisplayName("DELETE media : 200, médias effacés, transcript gardé, media_purged_at posé")
    void manualDelete() throws Exception {
        UUID id = seedMeetingWithMedia(userId(aliceHost), aliceHost, OffsetDateTime.now().minusDays(1));

        mockMvc.perform(delete(url(aliceHost, id)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasAudio").value(false))
                .andExpect(jsonPath("$.imageCount").value(0))
                .andExpect(jsonPath("$.mediaPurgedAt").isNotEmpty())
                .andExpect(jsonPath("$.hasTranscript").value(true)); // transcript conservé

        assertThat(mediaService.findAudio(userId(aliceHost), aliceHost, id)).isEmpty();
        assertThat(mediaService.listFrames(userId(aliceHost), aliceHost, id)).isEmpty();
        Meeting reloaded = meetingRepository.findById(id).orElseThrow();
        assertThat(reloaded.getMediaPurgedAt()).isNotNull();
        assertThat(reloaded.getTranscript()).isNotBlank();
    }

    @Test
    @DisplayName("ISOLATION : Bob ne purge pas une réunion du poste d'Alice -> 404")
    void isolation() throws Exception {
        UUID id = seedMeetingWithMedia(userId(aliceHost), aliceHost, OffsetDateTime.now().minusDays(1));
        mockMvc.perform(delete(url(aliceHost, id)).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        // Les médias d'Alice sont intacts.
        assertThat(mediaService.findAudio(userId(aliceHost), aliceHost, id)).isPresent();
    }

    @Test
    @DisplayName("Gardes : sans droit Teams 403 ; hors Vigie 409")
    void guards() throws Exception {
        UUID carolMeeting = seedMeetingWithMedia(userId(carolHost), carolHost, OffsetDateTime.now());
        mockMvc.perform(delete(url(carolHost, carolMeeting)).contextPath("/api")
                        .header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isForbidden());

        UUID noVigie = seedMeetingWithMedia(userId(aliceHostNoVigie), aliceHostNoVigie, OffsetDateTime.now());
        mockMvc.perform(delete(url(aliceHostNoVigie, noVigie)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Purge de rétention : purge une réunion au-delà de la rétention, garde une réunion récente")
    void purgesExpiredKeepsRecent() {
        UUID expired = seedMeetingWithMedia(userId(aliceHost), aliceHost, OffsetDateTime.now().minusDays(40));
        UUID recent = seedMeetingWithMedia(userId(aliceHost), aliceHost, OffsetDateTime.now().minusDays(2));

        retentionService.purgeExpired(OffsetDateTime.now());

        Meeting expiredReloaded = meetingRepository.findById(expired).orElseThrow();
        assertThat(expiredReloaded.getMediaPurgedAt()).isNotNull();
        assertThat(expiredReloaded.getAudioKey()).isNull();
        assertThat(mediaService.findAudio(userId(aliceHost), aliceHost, expired)).isEmpty();

        Meeting recentReloaded = meetingRepository.findById(recent).orElseThrow();
        assertThat(recentReloaded.getMediaPurgedAt()).isNull();
        assertThat(mediaService.findAudio(userId(aliceHost), aliceHost, recent)).isPresent();
    }

    @Test
    @DisplayName("Worker : idempotent — rejouer ne repurge pas (aucune candidate)")
    void workerIdempotent() {
        UUID expired = seedMeetingWithMedia(userId(aliceHost), aliceHost, OffsetDateTime.now().minusDays(40));
        assertThat(retentionService.purgeExpired(OffsetDateTime.now())).isEqualTo(1);
        assertThat(retentionService.purgeExpired(OffsetDateTime.now())).isZero(); // idempotent
        assertThat(meetingRepository.findById(expired).orElseThrow().getMediaPurgedAt()).isNotNull();
    }

    // ---------------------------------------------------------------- montage

    private UUID seedMeetingWithMedia(UUID userId, UUID hostId, OffsetDateTime startedAt) {
        UUID id = meetingRepository.save(Meeting.builder().userId(userId).hostId(hostId)
                .state(MeetingState.STOPPED).meetingUrl("https://teams.microsoft.com/x")
                .consentAcknowledged(true).retentionDays(30).title("Comité").startedAt(startedAt)
                .transcript("[00:00] Bonjour").transcriptStatus(TranscriptStatus.TRANSCRIBED).build()).getId();
        mediaService.storeAudio(userId, hostId, id, "audio/webm", new byte[] {1, 2, 3, 4});
        mediaService.storeImage(userId, hostId, id, "image/png", new byte[] {5, 6, 7});
        return id;
    }

    private UUID userId(UUID hostId) {
        return hostRepository.findById(hostId).orElseThrow().getUserId();
    }

    private static String url(UUID hostId, UUID meetingId) {
        return "/api/vigie/hosts/" + hostId + "/meetings/" + meetingId + "/media";
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
    }

    private void entitleTeams(UUID userId) {
        subscriptionRepository.save(Subscription.builder().userId(userId).planCode(PlanCode.PRO)
                .status(SubscriptionStatus.ACTIVE).teamsOptionStatus(SubscriptionStatus.ACTIVE).build());
    }

    private UUID seedHost(UUID userId, boolean activateVigie) {
        UUID hostId = hostRepository.save(RunnerHost.builder().userId(userId).name("Poste").build()).getId();
        if (activateVigie) {
            hostSpaces.save(HostSpace.builder().userId(userId).hostId(hostId)
                    .space(ClientSpace.VIGIE).activatedAt(OffsetDateTime.now()).build());
        }
        return hostId;
    }
}
