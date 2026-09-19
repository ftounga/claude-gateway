package fr.claudegateway.teams.meeting;

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

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.radar.RadarCommitmentRepository;
import fr.claudegateway.radar.RadarCorrectionRepository;
import fr.claudegateway.radar.RadarEvidenceLinkRepository;
import fr.claudegateway.radar.RadarEvidenceRepository;
import fr.claudegateway.radar.RadarSubject;
import fr.claudegateway.radar.RadarSubjectRepository;
import fr.claudegateway.radar.RadarSubjectState;
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
 * Pousser les actions d'une réunion dans le Radar (F-128 / SF-128-06) : bilan, gardes et isolation.
 * Aucun appel modèle (écriture pure) : rien à mocker côté fournisseur.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeamsMeetingActionsApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private HostSpaceRepository hostSpaces;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private MeetingRepository meetingRepository;
    @Autowired private RadarSubjectRepository subjects;
    @Autowired private RadarCommitmentRepository commitments;
    @Autowired private RadarEvidenceRepository evidence;
    @Autowired private RadarEvidenceLinkRepository links;
    @Autowired private RadarCorrectionRepository corrections;

    private String aliceToken;
    private UUID aliceHost;
    private UUID aliceHostNoVigie;
    private String bobToken;
    private String carolToken;
    private UUID carolHost;

    @BeforeEach
    void setUp() {
        meetingRepository.deleteAll();
        corrections.deleteAll();
        links.deleteAll();
        evidence.deleteAll();
        commitments.deleteAll();
        subjects.deleteAll();
        hostSpaces.deleteAll();
        hostRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        SecurityContextHolder.clearContext();

        User alice = seedUser("alice-a2r-api@example.com");
        entitleTeams(alice.getId());
        aliceToken = jwtService.generateToken(alice);
        aliceHost = seedHost(alice.getId(), true);
        aliceHostNoVigie = seedHost(alice.getId(), false);

        User bob = seedUser("bob-a2r-api@example.com");
        entitleTeams(bob.getId());
        bobToken = jwtService.generateToken(bob);

        User carol = seedUser("carol-a2r-api@example.com");
        carolToken = jwtService.generateToken(carol);
        carolHost = seedHost(carol.getId(), true);
    }

    @Test
    @DisplayName("POST actions-to-radar : 200, 2 engagements créés sur le sujet de la réunion")
    void pushNominal() throws Exception {
        UUID userId = userId(aliceHost);
        UUID subjectId = seedSubject(userId, aliceHost, "Migration");
        UUID meetingId = seedMeeting(userId, aliceHost, subjectId);

        mockMvc.perform(post(url(aliceHost, meetingId)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actions\":[\"Envoyer le CR\",\"Relancer l'infra\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsSubject").value(false))
                .andExpect(jsonPath("$.added").value(2))
                .andExpect(jsonPath("$.subjectId").value(subjectId.toString()));

        org.assertj.core.api.Assertions.assertThat(
                        commitments.findByUserIdAndHostIdAndSubjectId(userId, aliceHost, subjectId))
                .hasSize(2);
    }

    @Test
    @DisplayName("Sans sujet (ni requête ni réunion) : 200 needsSubject, rien écrit")
    void needsSubject() throws Exception {
        UUID userId = userId(aliceHost);
        UUID meetingId = seedMeeting(userId, aliceHost, null);

        mockMvc.perform(post(url(aliceHost, meetingId)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actions\":[\"Faire un truc\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsSubject").value(true))
                .andExpect(jsonPath("$.added").value(0));

        org.assertj.core.api.Assertions.assertThat(commitments.findByUserIdAndHostId(userId, aliceHost)).isEmpty();
    }

    @Test
    @DisplayName("ISOLATION : Bob ne pousse pas sur une réunion du poste d'Alice -> 404")
    void isolation() throws Exception {
        UUID userId = userId(aliceHost);
        UUID subjectId = seedSubject(userId, aliceHost, "Migration");
        UUID meetingId = seedMeeting(userId, aliceHost, subjectId);

        mockMvc.perform(post(url(aliceHost, meetingId)).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actions\":[\"Envoyer le CR\"]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Gardes : sans droit Teams 403 ; hors Vigie 409")
    void guards() throws Exception {
        UUID carolMeeting = seedMeeting(userId(carolHost), carolHost, null);
        mockMvc.perform(post(url(carolHost, carolMeeting)).contextPath("/api")
                        .header("Authorization", "Bearer " + carolToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actions\":[\"x\"]}"))
                .andExpect(status().isForbidden());

        UUID noVigie = seedMeeting(userId(aliceHostNoVigie), aliceHostNoVigie, null);
        mockMvc.perform(post(url(aliceHostNoVigie, noVigie)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actions\":[\"x\"]}"))
                .andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------- montage

    private UUID seedSubject(UUID userId, UUID hostId, String name) {
        return subjects.save(RadarSubject.builder().userId(userId).hostId(hostId)
                .name(name).state(RadarSubjectState.NEW).build()).getId();
    }

    private UUID seedMeeting(UUID userId, UUID hostId, UUID subjectId) {
        return meetingRepository.save(Meeting.builder().userId(userId).hostId(hostId).subjectId(subjectId)
                .state(MeetingState.STOPPED).meetingUrl("https://teams.microsoft.com/x")
                .consentAcknowledged(true).retentionDays(30).title("Comité").startedAt(OffsetDateTime.now())
                .transcript("[00:00] Bonjour").transcriptStatus(TranscriptStatus.TRANSCRIBED).build()).getId();
    }

    private UUID userId(UUID hostId) {
        return hostRepository.findById(hostId).orElseThrow().getUserId();
    }

    private static String url(UUID hostId, UUID meetingId) {
        return "/api/vigie/hosts/" + hostId + "/meetings/" + meetingId + "/actions-to-radar";
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
