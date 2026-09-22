package fr.claudegateway.teams.meeting;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

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
 * F-147 / SF-147-02 — la remontée du <b>texte</b> d'un enregistrement déposé : jeton runner (401),
 * isolation par le couple du jeton (404), option Teams (403), texte vide = échec <b>dit</b>.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerRecordingTranscriptApiIntegrationTest {

    private static final String HEADER = "X-Runner-Token";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private RunnerTokenRepository runnerTokenRepository;
    @Autowired private RunnerTokenService tokenService;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private MeetingRepository meetingRepository;

    private String aliceToken;
    private UUID aliceMeeting;
    private String bobToken;
    private String carolToken;
    private UUID carolMeeting;

    @BeforeEach
    void setUp() {
        meetingRepository.deleteAll();
        runnerTokenRepository.deleteAll();
        hostRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        SecurityContextHolder.clearContext();

        User alice = seedUser("alice-transcript@example.com");
        entitle(alice.getId());
        UUID aliceHost = seedHost(alice.getId());
        aliceToken = tokenService.issue(alice.getId(), aliceHost, "poste-alice").clearToken();
        aliceMeeting = seedRecordingMeeting(alice.getId(), aliceHost);

        User bob = seedUser("bob-transcript@example.com");
        entitle(bob.getId());
        UUID bobHost = seedHost(bob.getId());
        bobToken = tokenService.issue(bob.getId(), bobHost, "poste-bob").clearToken();

        User carol = seedUser("carol-transcript@example.com"); // pas d'option Teams
        UUID carolHost = seedHost(carol.getId());
        carolToken = tokenService.issue(carol.getId(), carolHost, "poste-carol").clearToken();
        carolMeeting = seedRecordingMeeting(carol.getId(), carolHost);
    }

    private String url(UUID meetingId) {
        return "/api/runner/teams/meetings/" + meetingId + "/local-transcript";
    }

    private static String body(String text, String failure) {
        return "{\"text\":\"" + text + "\",\"failure\":\"" + failure + "\"}";
    }

    @Test
    @DisplayName("nominal : le texte transcrit sur le poste rejoint la réunion")
    void theTextJoinsTheMeeting() throws Exception {
        mockMvc.perform(post(url(aliceMeeting)).contextPath("/api").header(HEADER, aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Paul : on part sur Okta.", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transcriptStatus").value("TRANSCRIBED"));

        Meeting stored = meetingRepository.findById(aliceMeeting).orElseThrow();
        assertThat(stored.getTranscript()).contains("Okta");
        assertThat(stored.getTranscriptError()).isNull();
        assertThat(stored.getExternalTranscriptSource()).isEqualTo(RecordingMeetingService.SOURCE);
    }

    @Test
    @DisplayName("sans texte : la réunion PORTE l'échec — une réunion vide en silence serait pire")
    void anEmptyTextIsAnAnnouncedFailure() throws Exception {
        mockMvc.perform(post(url(aliceMeeting)).contextPath("/api").header(HEADER, aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("", "aucune parole reconnue")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transcriptStatus").value("FAILED"));

        Meeting stored = meetingRepository.findById(aliceMeeting).orElseThrow();
        assertThat(stored.getTranscriptError()).contains("aucune parole");
        assertThat(stored.getTranscript()).isNull();
    }

    @Test
    @DisplayName("sans jeton runner : 401 générique")
    void withoutTokenUnauthorized() throws Exception {
        mockMvc.perform(post(url(aliceMeeting)).contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(body("texte", "")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ISOLATION : le jeton d'un autre compte ne remplit pas cette réunion (404)")
    void otherAccountCannotFillTheMeeting() throws Exception {
        mockMvc.perform(post(url(aliceMeeting)).contextPath("/api").header(HEADER, bobToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("texte volé", "")))
                .andExpect(status().isNotFound());

        assertThat(meetingRepository.findById(aliceMeeting).orElseThrow().getTranscript()).isNull();
    }

    @Test
    @DisplayName("réunion inconnue : 404")
    void unknownMeetingNotFound() throws Exception {
        mockMvc.perform(post(url(UUID.randomUUID())).contextPath("/api").header(HEADER, aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("texte", "")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("sans option Teams : 403")
    void withoutTeamsOptionForbidden() throws Exception {
        mockMvc.perform(post(url(carolMeeting)).contextPath("/api").header(HEADER, carolToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("texte", "")))
                .andExpect(status().isForbidden());
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

    /** Une réunion née d'un enregistrement : pas d'URL, transcription attendue. */
    private UUID seedRecordingMeeting(UUID userId, UUID hostId) {
        return meetingRepository.save(Meeting.builder().userId(userId).hostId(hostId)
                .state(MeetingState.STOPPED).meetingUrl(null).title("Atelier sécurité")
                .consentAcknowledged(true).retentionDays(30).startedAt(OffsetDateTime.now())
                .transcriptStatus(TranscriptStatus.PENDING).build()).getId();
    }
}
