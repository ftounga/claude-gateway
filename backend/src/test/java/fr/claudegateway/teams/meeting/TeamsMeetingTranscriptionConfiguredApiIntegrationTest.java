package fr.claudegateway.teams.meeting;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.test.context.TestPropertySource;
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
import fr.claudegateway.teams.meeting.stt.TranscriptionProvider.Transcript;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Transcription — STT <b>configuré</b> (F-128 / SF-128-04) : les propriétés `app.stt.*` sont fournies,
 * le fournisseur est mocké (pas de vrai HTTP). Chemin nominal : déclencher (202 PENDING) → worker
 * (`transcribePending` piloté directement, le scheduler est off en test) → TRANSCRIBED → lire le texte.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.stt.base-url=http://stt.local", "app.stt.api-key=test-key"})
class TeamsMeetingTranscriptionConfiguredApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private HostSpaceRepository hostSpaces;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private MeetingRepository meetingRepository;
    @Autowired private MeetingMediaService mediaService;
    @Autowired private TranscriptionService transcriptionService;

    @MockBean private TranscriptionProvider provider;

    private String aliceToken;
    private UUID aliceHost;

    @BeforeEach
    void setUp() {
        meetingRepository.deleteAll();
        hostSpaces.deleteAll();
        hostRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        SecurityContextHolder.clearContext();

        User alice = seedUser("alice-stt-on@example.com");
        entitleTeams(alice.getId());
        aliceToken = jwtService.generateToken(alice);
        aliceHost = seedHost(alice.getId());
    }

    @Test
    @DisplayName("configuré : transcribe -> 202 PENDING ; worker -> TRANSCRIBED ; GET transcript -> 200 texte")
    void endToEnd() throws Exception {
        UUID userId = hostRepository.findById(aliceHost).orElseThrow().getUserId();
        UUID id = meetingRepository.save(Meeting.builder().userId(userId).hostId(aliceHost)
                .state(MeetingState.STOPPED).meetingUrl("https://teams.microsoft.com/x")
                .consentAcknowledged(true).retentionDays(30).startedAt(OffsetDateTime.now()).build()).getId();
        mediaService.storeAudio(userId, aliceHost, id, "audio/webm", "opus".getBytes());
        when(provider.transcribe(any(), anyString(), any()))
                .thenReturn(new Transcript("[00:00] Bonjour à tous", "fr"));

        // 1) Déclenchement : enfilé (202 PENDING), aucun appel synchrone.
        mockMvc.perform(post(url(id, "/transcribe")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.transcriptStatus").value("PENDING"));

        // 2) Worker (piloté directement, déterministe) : appelle le STT et rattache le transcript.
        int processed = transcriptionService.transcribePending();
        org.assertj.core.api.Assertions.assertThat(processed).isEqualTo(1);

        // 3) Lecture : le texte du transcript.
        mockMvc.perform(get(url(id, "/transcript")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Bonjour à tous")));

        // 4) Le statut est visible sur l'artefact.
        mockMvc.perform(get(url(id, "")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transcriptStatus").value("TRANSCRIBED"))
                .andExpect(jsonPath("$.transcriptLang").value("fr"))
                .andExpect(jsonPath("$.hasTranscript").value(true));
    }

    private String url(UUID meetingId, String suffix) {
        return "/api/vigie/hosts/" + aliceHost + "/meetings/" + meetingId + suffix;
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
    }

    private void entitleTeams(UUID userId) {
        subscriptionRepository.save(Subscription.builder().userId(userId).planCode(PlanCode.PRO)
                .status(SubscriptionStatus.ACTIVE).teamsOptionStatus(SubscriptionStatus.ACTIVE).build());
    }

    private UUID seedHost(UUID userId) {
        UUID hostId = hostRepository.save(RunnerHost.builder().userId(userId).name("Poste").build()).getId();
        hostSpaces.save(HostSpace.builder().userId(userId).hostId(hostId)
                .space(ClientSpace.VIGIE).activatedAt(OffsetDateTime.now()).build());
        return hostId;
    }
}
