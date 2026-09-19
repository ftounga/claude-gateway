package fr.claudegateway.teams.meeting;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.http.HttpHeaders;
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
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration de la <b>lecture des médias</b> d'une réunion (F-128 / SF-128-10) : GET audio
 * (nominal, Range 206, plage hors bornes 416, téléchargement), GET deck (liste + image), gardes d'accès
 * (droit Teams 403, hors Vigie 409) et isolation user_id+host_id (404).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeamsMeetingMediaApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private HostSpaceRepository hostSpaces;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private MeetingRepository meetingRepository;
    @Autowired private MeetingMediaService mediaService;

    private static final byte[] AUDIO = "0123456789".getBytes(StandardCharsets.UTF_8);

    private String aliceToken;
    private UUID aliceHost;         // possédé + Vigie active + droit Teams
    private UUID aliceHostNoVigie;  // possédé, non activé Vigie
    private String bobToken;        // autre compte, isolation
    private String carolToken;      // Vigie active, sans droit Teams
    private UUID carolHost;

    @BeforeEach
    void setUp() {
        meetingRepository.deleteAll();
        hostSpaces.deleteAll();
        hostRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        SecurityContextHolder.clearContext();

        User alice = seedUser("alice-media@example.com");
        entitleTeams(alice.getId());
        aliceToken = jwtService.generateToken(alice);
        aliceHost = seedHost(alice.getId(), true);
        aliceHostNoVigie = seedHost(alice.getId(), false);

        User bob = seedUser("bob-media@example.com");
        entitleTeams(bob.getId());
        bobToken = jwtService.generateToken(bob);

        User carol = seedUser("carol-media@example.com");
        carolToken = jwtService.generateToken(carol);
        carolHost = seedHost(carol.getId(), true);
    }

    // ---------------------------------------------------------------- audio

    @Test
    @DisplayName("GET audio : 200, type d'origine, Accept-Ranges, corps complet")
    void audioNominal() throws Exception {
        UUID id = seedMeetingWithAudio(userId(aliceHost), aliceHost);
        mockMvc.perform(get(audioUrl(aliceHost, id)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "audio/webm"))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"));
    }

    @Test
    @DisplayName("GET audio : Content-Type audio/webm même si l'upload était ambigu (SF-128-17)")
    void audioContentTypeIsWebmEvenIfUploadedAmbiguous() throws Exception {
        // Le runner a pu déposer l'audio en application/octet-stream : la lecture doit malgré tout
        // servir audio/webm (redéduit de l'extension), sans quoi le <audio> reste à 0:00/0:00.
        UUID id = meetingRepository.save(baseMeeting(userId(aliceHost), aliceHost)
                .state(MeetingState.STOPPED).build()).getId();
        mediaService.storeAudio(userId(aliceHost), aliceHost, id, "application/octet-stream", AUDIO);
        mockMvc.perform(get(audioUrl(aliceHost, id)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "audio/webm"))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"));
    }

    @Test
    @DisplayName("GET audio avec Range : 206 + Content-Range + corps partiel")
    void audioRange() throws Exception {
        UUID id = seedMeetingWithAudio(userId(aliceHost), aliceHost);
        mockMvc.perform(get(audioUrl(aliceHost, id)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header(HttpHeaders.RANGE, "bytes=0-3"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-3/" + AUDIO.length))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 4L));
    }

    @Test
    @DisplayName("GET audio avec Range hors bornes : 416")
    void audioRangeUnsatisfiable() throws Exception {
        UUID id = seedMeetingWithAudio(userId(aliceHost), aliceHost);
        mockMvc.perform(get(audioUrl(aliceHost, id)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header(HttpHeaders.RANGE, "bytes=9999-"))
                .andExpect(status().isRequestedRangeNotSatisfiable());
    }

    @Test
    @DisplayName("GET audio?download=1 : Content-Disposition attachment")
    void audioDownload() throws Exception {
        UUID id = seedMeetingWithAudio(userId(aliceHost), aliceHost);
        mockMvc.perform(get(audioUrl(aliceHost, id) + "?download=1").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("attachment")));
    }

    @Test
    @DisplayName("GET audio : réunion sans audio → 404")
    void audioMissing() throws Exception {
        UUID id = meetingRepository.save(baseMeeting(userId(aliceHost), aliceHost).build()).getId();
        mockMvc.perform(get(audioUrl(aliceHost, id)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- isolation

    @Test
    @DisplayName("ISOLATION : Bob ne lit pas l'audio d'une réunion du poste d'Alice → 404")
    void audioIsolation() throws Exception {
        UUID id = seedMeetingWithAudio(userId(aliceHost), aliceHost);
        mockMvc.perform(get(audioUrl(aliceHost, id)).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(audioUrl(aliceHost, UUID.randomUUID())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- deck (images)

    @Test
    @DisplayName("GET images : liste des identifiants ; GET image : 200 + type")
    void imagesListAndGet() throws Exception {
        UUID id = meetingRepository.save(baseMeeting(userId(aliceHost), aliceHost).build()).getId();
        mediaService.storeImage(userId(aliceHost), aliceHost, id, "image/png", new byte[] {1, 2, 3});

        String listJson = mockMvc.perform(get(base(aliceHost, id) + "/images").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageIds.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        String imageId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(listJson)
                .path("imageIds").get(0).asText();

        mockMvc.perform(get(base(aliceHost, id) + "/images/" + imageId).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"));
    }

    @Test
    @DisplayName("GET image inconnue → 404")
    void imageUnknown() throws Exception {
        UUID id = meetingRepository.save(baseMeeting(userId(aliceHost), aliceHost).build()).getId();
        mockMvc.perform(get(base(aliceHost, id) + "/images/inconnue").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- gardes

    @Test
    @DisplayName("Sans droit Teams : 403")
    void audioWithoutTeamsRight() throws Exception {
        UUID id = meetingRepository.save(baseMeeting(userId(carolHost), carolHost).build()).getId();
        mockMvc.perform(get(audioUrl(carolHost, id)).contextPath("/api")
                        .header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Poste non activé dans la Vigie : 409")
    void audioHostNotInVigie() throws Exception {
        UUID id = meetingRepository.save(baseMeeting(userId(aliceHostNoVigie), aliceHostNoVigie).build()).getId();
        mockMvc.perform(get(audioUrl(aliceHostNoVigie, id)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------- montage

    private UUID seedMeetingWithAudio(UUID userId, UUID hostId) {
        UUID id = meetingRepository.save(baseMeeting(userId, hostId).state(MeetingState.STOPPED).build()).getId();
        mediaService.storeAudio(userId, hostId, id, "audio/webm", AUDIO);
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

    private static String base(UUID hostId, UUID meetingId) {
        return "/api/vigie/hosts/" + hostId + "/meetings/" + meetingId;
    }

    private static String audioUrl(UUID hostId, UUID meetingId) {
        return base(hostId, meetingId) + "/audio";
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
