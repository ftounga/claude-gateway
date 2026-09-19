package fr.claudegateway.teams.meeting;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import fr.claudegateway.teams.meeting.stt.TranscriptionProvider;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Transcription — STT <b>éteint par défaut</b> (F-128 / SF-128-04) : le profil test ne configure aucun
 * service, donc déclencher répond 503 {@code stt_not_configured} et <b>le fournisseur n'est jamais
 * appelé</b> (aucune donnée ne sort). Plus : sans audio (409), isolation (404), gardes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeamsMeetingTranscriptionApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private HostSpaceRepository hostSpaces;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private MeetingRepository meetingRepository;
    @Autowired private MeetingMediaService mediaService;

    @MockBean private TranscriptionProvider provider; // remplace l'impl HTTP : on prouve qu'on ne l'appelle pas

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

        User alice = seedUser("alice-stt@example.com");
        entitleTeams(alice.getId());
        aliceToken = jwtService.generateToken(alice);
        aliceHost = seedHost(alice.getId(), true);
        aliceHostNoVigie = seedHost(alice.getId(), false);

        User bob = seedUser("bob-stt@example.com");
        entitleTeams(bob.getId());
        bobToken = jwtService.generateToken(bob);

        User carol = seedUser("carol-stt@example.com");
        carolToken = jwtService.generateToken(carol);
        carolHost = seedHost(carol.getId(), true);
    }

    @Test
    @DisplayName("STT non configuré : POST transcribe -> 503 stt_not_configured, fournisseur jamais appelé")
    void transcribeNotConfigured() throws Exception {
        UUID id = seedMeetingWithAudio(userId(aliceHost), aliceHost);
        mockMvc.perform(post(transcribeUrl(aliceHost, id)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("stt_not_configured"));
        verify(provider, never()).transcribe(any(), anyString(), any());
    }

    @Test
    @DisplayName("réunion sans audio : 409 invalid_state")
    void transcribeNoAudio() throws Exception {
        UUID id = meetingRepository.save(baseMeeting(userId(aliceHost), aliceHost).build()).getId();
        mockMvc.perform(post(transcribeUrl(aliceHost, id)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("invalid_state"));
    }

    @Test
    @DisplayName("ISOLATION : Bob ne transcrit pas une réunion du poste d'Alice -> 404")
    void transcribeIsolation() throws Exception {
        UUID id = seedMeetingWithAudio(userId(aliceHost), aliceHost);
        mockMvc.perform(post(transcribeUrl(aliceHost, id)).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Sans droit Teams : 403 ; poste hors Vigie : 409")
    void transcribeGuards() throws Exception {
        UUID carolMeeting = meetingRepository.save(baseMeeting(userId(carolHost), carolHost).build()).getId();
        mockMvc.perform(post(transcribeUrl(carolHost, carolMeeting)).contextPath("/api")
                        .header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isForbidden());

        UUID noVigie = meetingRepository.save(baseMeeting(userId(aliceHostNoVigie), aliceHostNoVigie).build()).getId();
        mockMvc.perform(post(transcribeUrl(aliceHostNoVigie, noVigie)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("GET transcript sans transcript -> 404")
    void transcriptMissing() throws Exception {
        UUID id = seedMeetingWithAudio(userId(aliceHost), aliceHost);
        mockMvc.perform(get(transcribeUrl(aliceHost, id).replace("/transcribe", "/transcript")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- montage

    private UUID seedMeetingWithAudio(UUID userId, UUID hostId) {
        UUID id = meetingRepository.save(baseMeeting(userId, hostId).build()).getId();
        mediaService.storeAudio(userId, hostId, id, "audio/webm", "opus".getBytes());
        return id;
    }

    private Meeting.MeetingBuilder baseMeeting(UUID userId, UUID hostId) {
        return Meeting.builder().userId(userId).hostId(hostId).state(MeetingState.STOPPED)
                .meetingUrl("https://teams.microsoft.com/x").consentAcknowledged(true).retentionDays(30)
                .startedAt(OffsetDateTime.now());
    }

    private UUID userId(UUID hostId) {
        return hostRepository.findById(hostId).orElseThrow().getUserId();
    }

    private static String transcribeUrl(UUID hostId, UUID meetingId) {
        return "/api/vigie/hosts/" + hostId + "/meetings/" + meetingId + "/transcribe";
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
