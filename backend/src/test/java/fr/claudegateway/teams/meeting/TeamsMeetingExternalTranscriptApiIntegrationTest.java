package fr.claudegateway.teams.meeting;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.docx.DocxFixtures;
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
 * La transcription externe (client) d'une réunion (F-128 / SF-128-20a) : coller, déposer
 * (.txt/.vtt/.docx), lire, remplacer, retirer, gardes et isolation. Aucun appel modèle (SF-128-20b).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeamsMeetingExternalTranscriptApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private HostSpaceRepository hostSpaces;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private MeetingRepository meetingRepository;

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

        User alice = seedUser("alice-ext@example.com");
        entitleTeams(alice.getId());
        aliceToken = jwtService.generateToken(alice);
        aliceHost = seedHost(alice.getId(), true);
        aliceHostNoVigie = seedHost(alice.getId(), false);

        User bob = seedUser("bob-ext@example.com");
        entitleTeams(bob.getId());
        bobToken = jwtService.generateToken(bob);

        User carol = seedUser("carol-ext@example.com");
        carolToken = jwtService.generateToken(carol);
        carolHost = seedHost(carol.getId(), true);
    }

    @Test
    @DisplayName("PUT texte -> 200 hasExternalTranscript ; GET -> 200 texte")
    void pasteAndRead() throws Exception {
        UUID id = seedMeeting(userId(aliceHost), aliceHost);

        mockMvc.perform(auth(put(url(aliceHost, id)), aliceToken)
                        .contentType("application/json")
                        .content("{\"text\":\"Alice : bonjour\",\"source\":\"Transcription Teams (client)\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasExternalTranscript").value(true))
                .andExpect(jsonPath("$.externalTranscriptFormat").value("TEXT"))
                .andExpect(jsonPath("$.externalTranscriptSource").value("Transcription Teams (client)"));

        mockMvc.perform(auth(get(url(aliceHost, id)), aliceToken))
                .andExpect(status().isOk())
                .andExpect(content().string("Alice : bonjour"));
    }

    @Test
    @DisplayName("PUT texte vide -> 400 invalid_meeting")
    void pasteEmpty() throws Exception {
        UUID id = seedMeeting(userId(aliceHost), aliceHost);
        mockMvc.perform(auth(put(url(aliceHost, id)), aliceToken)
                        .contentType("application/json").content("{\"text\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_meeting"));
    }

    @Test
    @DisplayName("POST fichier .txt et .vtt -> 200 ; format déduit")
    void uploadTxtAndVtt() throws Exception {
        UUID id = seedMeeting(userId(aliceHost), aliceHost);

        mockMvc.perform(auth(file(aliceHost, id, "notes.txt", "Bob : ok".getBytes(StandardCharsets.UTF_8)),
                        aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalTranscriptFormat").value("TEXT"));

        mockMvc.perform(auth(file(aliceHost, id, "teams.vtt",
                        "WEBVTT\n\n00:00.000 --> 00:02.000\nAlice : salut".getBytes(StandardCharsets.UTF_8)),
                        aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalTranscriptFormat").value("VTT"));
    }

    @Test
    @DisplayName("POST fichier .docx valide -> 200 texte extrait ; .docx corrompu -> 422")
    void uploadDocx() throws Exception {
        UUID id = seedMeeting(userId(aliceHost), aliceHost);

        byte[] good = DocxFixtures.docx(DocxFixtures.paragraph("Alice : la migration est validée."));
        mockMvc.perform(auth(file(aliceHost, id, "t.docx", good), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalTranscriptFormat").value("DOCX"));
        mockMvc.perform(auth(get(url(aliceHost, id)), aliceToken))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("migration est validée")));

        byte[] bad = DocxFixtures.archive(Map.of("word/document.xml", "<w:document><w:body><w:p></broken"));
        mockMvc.perform(auth(file(aliceHost, id, "x.docx", bad), aliceToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_document"));
    }

    @Test
    @DisplayName("Remplacement : le second envoi gagne (une seule transcription externe)")
    void replace() throws Exception {
        UUID id = seedMeeting(userId(aliceHost), aliceHost);
        mockMvc.perform(auth(put(url(aliceHost, id)), aliceToken)
                        .contentType("application/json").content("{\"text\":\"ancienne\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(auth(put(url(aliceHost, id)), aliceToken)
                        .contentType("application/json").content("{\"text\":\"nouvelle\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(auth(get(url(aliceHost, id)), aliceToken))
                .andExpect(content().string("nouvelle"));
    }

    @Test
    @DisplayName("DELETE -> 204 ; GET -> 404")
    void clear() throws Exception {
        UUID id = seedMeeting(userId(aliceHost), aliceHost);
        mockMvc.perform(auth(put(url(aliceHost, id)), aliceToken)
                        .contentType("application/json").content("{\"text\":\"texte\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(auth(delete(url(aliceHost, id)), aliceToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(auth(get(url(aliceHost, id)), aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET sans transcription externe -> 404")
    void getMissing() throws Exception {
        UUID id = seedMeeting(userId(aliceHost), aliceHost);
        mockMvc.perform(auth(get(url(aliceHost, id)), aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("ISOLATION : Bob ne touche pas la réunion du poste d'Alice -> 404 (PUT/POST/GET/DELETE)")
    void isolation() throws Exception {
        UUID id = seedMeeting(userId(aliceHost), aliceHost);
        mockMvc.perform(auth(put(url(aliceHost, id)), bobToken)
                        .contentType("application/json").content("{\"text\":\"x\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(auth(file(aliceHost, id, "n.txt", "x".getBytes(StandardCharsets.UTF_8)), bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(auth(get(url(aliceHost, id)), bobToken)).andExpect(status().isNotFound());
        mockMvc.perform(auth(delete(url(aliceHost, id)), bobToken)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Gardes : sans droit Teams 403 ; hors Vigie 409")
    void guards() throws Exception {
        UUID carolMeeting = seedMeeting(userId(carolHost), carolHost);
        mockMvc.perform(auth(put(url(carolHost, carolMeeting)), carolToken)
                        .contentType("application/json").content("{\"text\":\"x\"}"))
                .andExpect(status().isForbidden());

        UUID noVigie = seedMeeting(userId(aliceHostNoVigie), aliceHostNoVigie);
        mockMvc.perform(auth(put(url(aliceHostNoVigie, noVigie)), aliceToken)
                        .contentType("application/json").content("{\"text\":\"x\"}"))
                .andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------- montage

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder, String token) {
        return builder.contextPath("/api").header("Authorization", "Bearer " + token);
    }

    private static MockMultipartHttpServletRequestBuilder file(UUID hostId, UUID meetingId, String name,
            byte[] content) {
        MockMultipartHttpServletRequestBuilder b =
                (MockMultipartHttpServletRequestBuilder) multipart(url(hostId, meetingId));
        return b.file(new MockMultipartFile("file", name, null, content));
    }

    private UUID seedMeeting(UUID userId, UUID hostId) {
        return meetingRepository.save(Meeting.builder().userId(userId).hostId(hostId)
                .state(MeetingState.STOPPED).meetingUrl("https://teams.microsoft.com/x")
                .consentAcknowledged(true).retentionDays(30).title("Comité")
                .startedAt(OffsetDateTime.now()).build()).getId();
    }

    private UUID userId(UUID hostId) {
        return hostRepository.findById(hostId).orElseThrow().getUserId();
    }

    private static String url(UUID hostId, UUID meetingId) {
        return "/api/vigie/hosts/" + hostId + "/meetings/" + meetingId + "/external-transcript";
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
