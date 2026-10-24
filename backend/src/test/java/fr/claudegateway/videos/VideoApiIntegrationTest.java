package fr.claudegateway.videos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** Lire une vidéo de son compte : métadonnées, ticket, {@code Range}, isolation (F-213 / SF-213-01). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VideoApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoService videoService;
    @Autowired private VideoStore store;
    @Autowired private VideoRepository videoRepository;
    @Autowired private VideoVersionRepository versionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private JwtService jwtService;

    private final ObjectMapper mapper = new ObjectMapper();
    private String aliceToken;
    private String bobToken;
    private UUID aliceVideo;
    private byte[] content;

    @BeforeEach
    void setUp() {
        versionRepository.deleteAll();
        videoRepository.deleteAll();
        User alice = VideoTestSupport.user(userRepository, "alice-video-api@example.com", UserRole.USER);
        User bob = VideoTestSupport.user(userRepository, "bob-video-api@example.com", UserRole.USER);
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(bob);
        Workspace workspace = VideoTestSupport.runnerWorkspace(workspaceRepository, alice.getId());
        content = VideoTestSupport.mp4(10_000);
        aliceVideo = VideoTestSupport.publish(videoService, store,
                new VideoPlace(alice.getId(), workspace.getId(), workspace.getHostId()), "Cours EKS", content,
                List.of(new VideoChapter(0, "Intro"), new VideoChapter(20, "Suite")), "Série EKS", null)
                .video().getId();
    }

    private JsonNode metadata(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/videos/" + aliceVideo).contextPath("/api")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("GET /videos/{id} — métadonnées, chapitres, série, adresses par ticket (jamais le JWT)")
    void metadataWithTicketUrls() throws Exception {
        mockMvc.perform(get("/api/videos/" + aliceVideo).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Cours EKS"))
                .andExpect(jsonPath("$.kind").value("VIDEO"))
                .andExpect(jsonPath("$.seriesTitle").value("Série EKS"))
                .andExpect(jsonPath("$.episode").value(1))
                .andExpect(jsonPath("$.durationS").value(42))
                .andExpect(jsonPath("$.chapters[1].titre").value("Suite"))
                .andExpect(jsonPath("$.chapters[1].t").value(20))
                .andExpect(jsonPath("$.mediaUrl", startsWith("/api/video-media/v1.")))
                .andExpect(jsonPath("$.posterUrl").doesNotExist())
                .andExpect(jsonPath("$.downloadUrl", containsString("download=1")));
        mockMvc.perform(get("/api/videos/" + aliceVideo + "/versions").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].sizeBytes").value(content.length));
    }

    @Test
    @DisplayName("Média par ticket, sans JWT : 200 complet, 206 pour une plage, 416 hors fichier, téléchargement")
    void ticketMediaWithRange() throws Exception {
        String mediaUrl = metadata(aliceToken).get("mediaUrl").asText();

        MvcResult full = mockMvc.perform(get(mediaUrl).contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andReturn();
        assertThat(full.getResponse().getContentAsByteArray()).isEqualTo(content);

        MvcResult partial = mockMvc.perform(get(mediaUrl).contextPath("/api").header("Range", "bytes=100-199"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 100-199/" + content.length))
                .andReturn();
        assertThat(partial.getResponse().getContentAsByteArray()).isEqualTo(Arrays.copyOfRange(content, 100, 200));

        mockMvc.perform(get(mediaUrl).contextPath("/api").header("Range", "bytes=" + content.length + "-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */" + content.length));

        mockMvc.perform(get(mediaUrl + "?download=1").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("cours-eks-v1.mp4")));

        mockMvc.perform(get(mediaUrl + "/poster").contextPath("/api")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Média par JWT avec Range")
    void jwtMediaWithRange() throws Exception {
        MvcResult partial = mockMvc.perform(get("/api/videos/" + aliceVideo + "/versions/1/media").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken).header("Range", "bytes=-10"))
                .andExpect(status().isPartialContent())
                .andReturn();
        assertThat(partial.getResponse().getContentAsByteArray())
                .isEqualTo(Arrays.copyOfRange(content, content.length - 10, content.length));
        mockMvc.perform(get("/api/videos/" + aliceVideo + "/versions/9/media").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Isolation : la vidéo d'Alice est introuvable pour Bob ; un ticket falsifié n'ouvre rien")
    void isolation() throws Exception {
        mockMvc.perform(get("/api/videos/" + aliceVideo).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/videos/" + aliceVideo + "/versions").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/videos/" + aliceVideo + "/versions/1/media").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/videos/" + aliceVideo).contextPath("/api")).andExpect(status().isUnauthorized());

        String mediaUrl = metadata(aliceToken).get("mediaUrl").asText();
        mockMvc.perform(get(mediaUrl.substring(0, mediaUrl.length() - 3) + "abc").contextPath("/api"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/video-media/" + UUID.randomUUID()).contextPath("/api"))
                .andExpect(status().isNotFound());
    }
}
