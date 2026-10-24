package fr.claudegateway.videos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** La galerie, les séries et la suppression (F-213 / SF-213-03). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VideoGalleryApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoService videoService;
    @Autowired private VideoStore store;
    @Autowired private VideoRepository videoRepository;
    @Autowired private VideoVersionRepository versionRepository;
    @Autowired private VideoSeriesRepository seriesRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private JwtService jwtService;

    private User alice;
    private String aliceToken;
    private String bobToken;
    private UUID hostId;
    private Workspace subject;
    private Workspace other;
    private UUID ep1;
    private UUID ep2;
    private UUID standalone;
    private UUID seriesId;

    @BeforeEach
    void setUp() {
        versionRepository.deleteAll();
        videoRepository.deleteAll();
        seriesRepository.deleteAll();
        alice = VideoTestSupport.user(userRepository, "alice-video-gallery@example.com", UserRole.USER);
        User bob = VideoTestSupport.user(userRepository, "bob-video-gallery@example.com", UserRole.USER);
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(bob);
        hostId = UUID.randomUUID();
        subject = workspace(alice.getId(), hostId, "cours");
        other = workspace(alice.getId(), hostId, "autre");
        VideoPlace here = new VideoPlace(alice.getId(), subject.getId(), hostId);
        ep1 = VideoTestSupport.publish(videoService, store, here, "Le cluster", VideoTestSupport.mp4(2_000),
                List.of(new VideoChapter(0, "Intro")), "Cours EKS", null).video().getId();
        ep2 = VideoTestSupport.publish(videoService, store, here, "Les nœuds", VideoTestSupport.mp4(2_000),
                List.of(), "Cours EKS", null).video().getId();
        seriesId = videoService.require(alice.getId(), ep1).getSeriesId();
        standalone = VideoTestSupport.publish(videoService, store,
                new VideoPlace(alice.getId(), other.getId(), hostId), "Démo seule", VideoTestSupport.mp4(1_000),
                List.of(), null, null).video().getId();
        // Un son : jamais dans la galerie.
        VideoMediaTypes.MediaType mp3 = VideoMediaTypes.of("v.mp3", VideoKind.AUDIO).orElseThrow();
        byte[] sound = VideoTestSupport.mp3(500);
        VideoService.Prepared prepared = videoService.prepare(new VideoService.PublishRequest(here, mp3, null,
                "Voix 1", null, List.of(), null, null, null, "Voix 1"), sound.length, 0);
        var upload = store.openUpload(prepared.mediaKey(), mp3.mime());
        upload.write(sound);
        upload.complete();
        videoService.commit(prepared, sound.length, null, null);
    }

    private Workspace workspace(UUID userId, UUID host, String name) {
        return workspaceRepository.save(Workspace.builder().userId(userId).name(name + "-" + UUID.randomUUID())
                .hostId(host).projectPath(name).executionTarget(WorkspaceExecutionTarget.RUNNER).build());
    }

    @Test
    @DisplayName("Galerie du sujet : la série et ses épisodes dans l'ordre, durée totale ; sons exclus")
    void subjectGallery() throws Exception {
        mockMvc.perform(get("/api/videos").param("workspaceId", subject.getId().toString()).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.series.length()").value(1))
                .andExpect(jsonPath("$.series[0].title").value("Cours EKS"))
                .andExpect(jsonPath("$.series[0].episodeCount").value(2))
                .andExpect(jsonPath("$.series[0].totalDurationS").value(84))
                .andExpect(jsonPath("$.series[0].episodes[0].id").value(ep1.toString()))
                .andExpect(jsonPath("$.series[0].episodes[0].episode").value(1))
                .andExpect(jsonPath("$.series[0].episodes[0].chapterCount").value(1))
                .andExpect(jsonPath("$.series[0].episodes[1].id").value(ep2.toString()))
                .andExpect(jsonPath("$.videos.length()").value(0));
    }

    @Test
    @DisplayName("Vue du poste : toutes les vidéos du poste, séries et vidéos seules")
    void hostGallery() throws Exception {
        mockMvc.perform(get("/api/videos").param("hostId", hostId.toString()).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.series.length()").value(1))
                .andExpect(jsonPath("$.videos.length()").value(1))
                .andExpect(jsonPath("$.videos[0].id").value(standalone.toString()));
        mockMvc.perform(get("/api/videos").contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Isolation : galerie vide pour Bob, suppression et durée introuvables")
    void isolation() throws Exception {
        mockMvc.perform(get("/api/videos").param("hostId", hostId.toString()).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.series.length()").value(0))
                .andExpect(jsonPath("$.videos.length()").value(0));
        mockMvc.perform(delete("/api/videos/" + ep1).contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/video-series/" + seriesId).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/videos/" + ep1 + "/duration").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"durationS\":12}"))
                .andExpect(status().isNotFound());
        assertThat(videoRepository.findById(ep1)).isPresent();
    }

    @Test
    @DisplayName("Supprimer une vidéo efface ses objets ; la série vidée part avec le dernier épisode")
    void deleteVideo() throws Exception {
        String key = videoService.requireVersion(alice.getId(), ep1, null).getStorageKey();
        mockMvc.perform(delete("/api/videos/" + ep1).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());
        assertThat(videoRepository.findById(ep1)).isEmpty();
        assertThat(store.size(key)).isEmpty();
        assertThat(seriesRepository.findById(seriesId)).isPresent();

        mockMvc.perform(delete("/api/videos/" + ep2).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());
        assertThat(seriesRepository.findById(seriesId)).isEmpty();
    }

    @Test
    @DisplayName("Supprimer une série efface tous ses épisodes")
    void deleteSeries() throws Exception {
        String key = videoService.requireVersion(alice.getId(), ep2, null).getStorageKey();
        mockMvc.perform(delete("/api/video-series/" + seriesId).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());
        assertThat(videoRepository.findById(ep1)).isEmpty();
        assertThat(videoRepository.findById(ep2)).isEmpty();
        assertThat(store.size(key)).isEmpty();
        assertThat(videoRepository.findById(standalone)).isPresent();
    }

    @Test
    @DisplayName("La durée lue par le navigateur corrige celle annoncée ; une durée absurde est refusée")
    void duration() throws Exception {
        mockMvc.perform(put("/api/videos/" + ep1 + "/duration").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"durationS\":153.6}"))
                .andExpect(status().isNoContent());
        assertThat(videoService.require(alice.getId(), ep1).getDurationS()).isEqualTo(154);
        mockMvc.perform(put("/api/videos/" + ep1 + "/duration").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"durationS\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/videos").param("workspaceId", subject.getId().toString()).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.series[0].episodes[0].posterUrl").doesNotExist())
                .andExpect(jsonPath("$.series[0].title", endsWith("EKS")));
    }
}
