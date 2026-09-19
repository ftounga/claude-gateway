package fr.claudegateway.teams.meeting;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
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

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ProviderFileReference;
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
 * Ranger une réunion dans la carte du poste (F-128 / SF-128-11) : bilan, gardes et isolation.
 * L'{@code AIProvider} est mocké. Sans gouvernance active, la carte n'a aucune destination : l'endpoint
 * rend un bilan « aucune carte active » (200), sans jamais écrire ni appeler le modèle pour rien.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeamsMeetingCardApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private HostSpaceRepository hostSpaces;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private MeetingRepository meetingRepository;

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

        when(aiProvider.uploadFile(any())).thenReturn(new ProviderFileReference("file_x"));
        when(aiProvider.complete(any())).thenReturn(new ChatCompletionResult(
                "===CARTE===\n{\"files\":[]}", "claude-opus-4-8", 5, 5));

        User alice = seedUser("alice-card@example.com");
        entitleTeams(alice.getId());
        aliceToken = jwtService.generateToken(alice);
        aliceHost = seedHost(alice.getId(), true);
        aliceHostNoVigie = seedHost(alice.getId(), false);

        User bob = seedUser("bob-card@example.com");
        entitleTeams(bob.getId());
        bobToken = jwtService.generateToken(bob);

        User carol = seedUser("carol-card@example.com");
        carolToken = jwtService.generateToken(carol);
        carolHost = seedHost(carol.getId(), true);
    }

    @Test
    @DisplayName("POST promote-to-card : 200 bilan (aucune carte active -> rien rangé)")
    void promoteNoCard() throws Exception {
        UUID id = seedMeeting(userId(aliceHost), aliceHost);

        mockMvc.perform(post(url(aliceHost, id)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.factsWritten").value(0))
                .andExpect(jsonPath("$.note").isNotEmpty());
    }

    @Test
    @DisplayName("ISOLATION : Bob ne range pas une réunion du poste d'Alice -> 404")
    void isolation() throws Exception {
        UUID id = seedMeeting(userId(aliceHost), aliceHost);
        mockMvc.perform(post(url(aliceHost, id)).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Gardes : sans droit Teams 403 ; hors Vigie 409")
    void guards() throws Exception {
        UUID carolMeeting = seedMeeting(userId(carolHost), carolHost);
        mockMvc.perform(post(url(carolHost, carolMeeting)).contextPath("/api")
                        .header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isForbidden());

        UUID noVigie = seedMeeting(userId(aliceHostNoVigie), aliceHostNoVigie);
        mockMvc.perform(post(url(aliceHostNoVigie, noVigie)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------- montage

    private UUID seedMeeting(UUID userId, UUID hostId) {
        return meetingRepository.save(Meeting.builder().userId(userId).hostId(hostId)
                .state(MeetingState.STOPPED).meetingUrl("https://teams.microsoft.com/x")
                .consentAcknowledged(true).retentionDays(30).title("Comité").startedAt(OffsetDateTime.now())
                .transcript("[00:00] Bonjour").transcriptStatus(TranscriptStatus.TRANSCRIBED).build()).getId();
    }

    private UUID userId(UUID hostId) {
        return hostRepository.findById(hostId).orElseThrow().getUserId();
    }

    private static String url(UUID hostId, UUID meetingId) {
        return "/api/vigie/hosts/" + hostId + "/meetings/" + meetingId + "/promote-to-card";
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
