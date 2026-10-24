package fr.claudegateway.videos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** Les retours à la seconde par l'API : CRUD, envoi, transitions, isolation (F-214 / SF-214-01). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VideoCommentApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoService videoService;
    @Autowired private VideoCommentService commentService;
    @Autowired private VideoStore store;
    @Autowired private VideoRepository videoRepository;
    @Autowired private VideoVersionRepository versionRepository;
    @Autowired private VideoCommentRepository commentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private JwtService jwtService;

    private final ObjectMapper mapper = new ObjectMapper();
    private User alice;
    private String aliceToken;
    private String bobToken;
    private UUID aliceVideo;
    private UUID bobVideo;
    private VideoPlace alicePlace;

    @BeforeEach
    void setUp() {
        commentRepository.deleteAll();
        versionRepository.deleteAll();
        videoRepository.deleteAll();
        alice = VideoTestSupport.user(userRepository, "alice-video-comments@example.com", UserRole.USER);
        User bob = VideoTestSupport.user(userRepository, "bob-video-comments@example.com", UserRole.USER);
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(bob);
        Workspace workspace = VideoTestSupport.runnerWorkspace(workspaceRepository, alice.getId());
        alicePlace = new VideoPlace(alice.getId(), workspace.getId(), workspace.getHostId());
        aliceVideo = VideoTestSupport.publish(videoService, store, alicePlace, "Cours EKS",
                VideoTestSupport.mp4(5_000), List.of(), null, null).video().getId();
        Workspace bobWorkspace = VideoTestSupport.runnerWorkspace(workspaceRepository, bob.getId());
        bobVideo = VideoTestSupport.publish(videoService, store,
                new VideoPlace(bob.getId(), bobWorkspace.getId(), bobWorkspace.getHostId()), "Bob",
                VideoTestSupport.mp4(5_000), List.of(), null, null).video().getId();
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        request.contextPath("/api").header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        }
        return mockMvc.perform(request);
    }

    private JsonNode json(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private String base(UUID video) {
        return "/api/videos/" + video + "/comments";
    }

    @Test
    @DisplayName("Poser un instant et une plage : image à 30 i/s, version courante, liste ordonnée")
    void createsInstantAndRange() throws Exception {
        JsonNode instant = json(call(post(base(aliceVideo)), aliceToken,
                java.util.Map.of("tStartMs", 10_133, "text", "La bannière à droite n'est pas visible, je la veux en noir"))
                .andExpect(status().isCreated()));
        assertThat(instant.get("frame").asInt()).isEqualTo(304);
        assertThat(instant.get("version").asInt()).isEqualTo(1);
        assertThat(instant.get("status").asText()).isEqualTo("OUVERT");
        assertThat(instant.get("tStartMs").asLong()).isEqualTo(10_133);
        assertThat(instant.get("tEndMs").isNull()).isTrue();

        JsonNode range = json(call(post(base(aliceVideo)), aliceToken,
                java.util.Map.of("tStartMs", 80_000, "tEndMs", 95_000, "text", "Trop rapide"))
                .andExpect(status().isCreated()));
        assertThat(range.get("frame").asInt()).isEqualTo(2400);
        assertThat(range.get("endFrame").asInt()).isEqualTo(2850);

        call(get(base(aliceVideo)), aliceToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].frame").value(304))
                .andExpect(jsonPath("$[1].frame").value(2400));
    }

    @Test
    @DisplayName("Envoyer : message structuré des retours ouverts de la version, passés ENVOYE ; rien de plus à envoyer")
    void sendComposesMessageAndMarksSent() throws Exception {
        commentService.create(alice.getId(), aliceVideo, null, 80_000L, 95_000L, "Trop rapide");
        commentService.create(alice.getId(), aliceVideo, null, 10_133L, null, "Bannière en noir");

        JsonNode sent = json(call(post(base(aliceVideo) + "/send"), aliceToken, java.util.Map.of())
                .andExpect(status().isOk()));

        assertThat(sent.get("message").asText()).isEqualTo("Retours sur « Cours EKS » v1 (" + aliceVideo + ") :\n"
                + "00:10 (image 304) — Bannière en noir\n"
                + "01:20–01:35 (images 2400–2850) — Trop rapide");
        assertThat(sent.get("comments")).hasSize(2);
        assertThat(commentRepository.findAll()).allMatch(c -> c.getStatus() == VideoCommentStatus.ENVOYE
                && c.getSentAt() != null);
        call(post(base(aliceVideo) + "/send"), aliceToken, null).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Envoyer ne concerne que la version demandée")
    void sendIsPerVersion() throws Exception {
        commentService.create(alice.getId(), aliceVideo, 1, 1_000L, null, "Sur la v1");
        VideoTestSupport.publish(videoService, store, alicePlace, "Cours EKS", VideoTestSupport.mp4(6_000), List.of(),
                null, aliceVideo);
        commentService.create(alice.getId(), aliceVideo, null, 2_000L, null, "Sur la v2");

        JsonNode sent = json(call(post(base(aliceVideo) + "/send"), aliceToken, java.util.Map.of("version", 2))
                .andExpect(status().isOk()));

        assertThat(sent.get("message").asText()).contains("v2").contains("Sur la v2").doesNotContain("Sur la v1");
        call(get(base(aliceVideo) + "?version=1"), aliceToken, null)
                .andExpect(jsonPath("$[0].status").value("OUVERT"));
    }

    @Test
    @DisplayName("Transitions : texte modifiable seulement OUVERT ; l'écran ne fait que rouvrir")
    void transitions() throws Exception {
        UUID id = commentService.create(alice.getId(), aliceVideo, null, 1_000L, null, "Avant").getId();
        call(patch(base(aliceVideo) + "/" + id), aliceToken, java.util.Map.of("text", "Après"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("Après"));
        commentService.send(alice.getId(), aliceVideo, null);

        call(patch(base(aliceVideo) + "/" + id), aliceToken, java.util.Map.of("text", "Trop tard"))
                .andExpect(status().isBadRequest());
        call(patch(base(aliceVideo) + "/" + id), aliceToken, java.util.Map.of("status", "TRAITE"))
                .andExpect(status().isBadRequest());
        call(patch(base(aliceVideo) + "/" + id), aliceToken, java.util.Map.of("status", "OUVERT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OUVERT"))
                .andExpect(jsonPath("$.sentAt").doesNotExist());
    }

    @Test
    @DisplayName("Contraintes : texte vide, instant négatif, fin avant début, version inconnue")
    void validation() throws Exception {
        call(post(base(aliceVideo)), aliceToken, java.util.Map.of("tStartMs", 0, "text", "  "))
                .andExpect(status().isBadRequest());
        call(post(base(aliceVideo)), aliceToken, java.util.Map.of("tStartMs", -1, "text", "x"))
                .andExpect(status().isBadRequest());
        call(post(base(aliceVideo)), aliceToken, java.util.Map.of("tStartMs", 5_000, "tEndMs", 4_000, "text", "x"))
                .andExpect(status().isBadRequest());
        call(post(base(aliceVideo)), aliceToken, java.util.Map.of("version", 7, "tStartMs", 0, "text", "x"))
                .andExpect(status().isNotFound());
        call(post(base(aliceVideo)), aliceToken, java.util.Map.of("tStartMs", 0, "text", "x".repeat(1001)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Isolation : la vidéo et les retours d'un autre compte sont introuvables (404 croisés)")
    void isolation() throws Exception {
        UUID id = commentService.create(alice.getId(), aliceVideo, null, 1_000L, null, "À Alice").getId();

        call(get(base(aliceVideo)), bobToken, null).andExpect(status().isNotFound());
        call(post(base(aliceVideo)), bobToken, java.util.Map.of("tStartMs", 0, "text", "intrus"))
                .andExpect(status().isNotFound());
        call(patch(base(aliceVideo) + "/" + id), bobToken, java.util.Map.of("text", "x")).andExpect(status().isNotFound());
        call(delete(base(aliceVideo) + "/" + id), bobToken, null).andExpect(status().isNotFound());
        call(post(base(aliceVideo) + "/send"), bobToken, null).andExpect(status().isNotFound());
        // Le retour d'Alice sous la vidéo de Bob, par Bob : introuvable aussi.
        call(patch(base(bobVideo) + "/" + id), bobToken, java.util.Map.of("text", "x")).andExpect(status().isNotFound());

        assertThat(commentRepository.findById(id).orElseThrow().getText()).isEqualTo("À Alice");
        call(delete(base(aliceVideo) + "/" + id), aliceToken, null).andExpect(status().isNoContent());
        assertThat(commentRepository.findById(id)).isEmpty();
    }

    @Test
    @DisplayName("La suppression de la vidéo emporte ses retours")
    void cascadeOnVideoDelete() {
        commentService.create(alice.getId(), aliceVideo, null, 1_000L, null, "x");
        videoService.delete(alice.getId(), aliceVideo);
        assertThat(commentRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("Format : mm:ss, h:mm:ss, image arrondie")
    void format() {
        assertThat(VideoCommentService.timecode(10_133)).isEqualTo("00:10");
        assertThat(VideoCommentService.timecode(3_723_000)).isEqualTo("1:02:03");
        assertThat(VideoCommentService.frame(10_133)).isEqualTo(304);
        assertThat(VideoCommentService.frame(0)).isZero();
    }
}
