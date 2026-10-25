package fr.claudegateway.videos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** Format long (F-215 / SF-215-04) : fiche des sources par version, voix de série, isolation. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VideoSeriesVoiceSourcesIntegrationTest {

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
    private User bob;
    private Workspace subject;
    private VideoPlace here;

    @BeforeEach
    void setUp() {
        versionRepository.deleteAll();
        videoRepository.deleteAll();
        seriesRepository.deleteAll();
        alice = VideoTestSupport.user(userRepository, "alice-video-long@example.com", UserRole.USER);
        bob = VideoTestSupport.user(userRepository, "bob-video-long@example.com", UserRole.USER);
        subject = VideoTestSupport.runnerWorkspace(workspaceRepository, alice.getId());
        here = new VideoPlace(alice.getId(), subject.getId(), subject.getHostId());
    }

    private VideoService.PublishedVideo publish(String title, String series, UUID replace, List<VideoSource> sources,
            String voice, String instructions) {
        VideoMediaTypes.MediaType type = VideoMediaTypes.of("x.mp4", VideoKind.VIDEO).orElseThrow();
        byte[] content = VideoTestSupport.mp4(1_500);
        VideoService.Prepared prepared = videoService.prepare(new VideoService.PublishRequest(here, type, replace,
                title, null, List.of(), series, null, 90, null, sources, voice, instructions), content.length, 0);
        var upload = store.openUpload(prepared.mediaKey(), type.mime());
        upload.write(content);
        upload.complete();
        return videoService.commit(prepared, content.length, null, null);
    }

    @Test
    @DisplayName("Fiche des sources rangée par version et rendue par GET /videos/{id} ; voix de la série rendue")
    void sourcesPerVersion() throws Exception {
        List<VideoSource> sheet = List.of(new VideoSource("Le VPC est en 10.180.0.0/16", "carte : vpc-prod", false),
                new VideoSource("L'ALB écoute sur 443", null, true));
        VideoService.PublishedVideo first = publish("Épisode 1", "Cours EKS", null, sheet, "nova", "posée");
        UUID id = first.video().getId();
        publish("Épisode 1", null, id, List.of(), null, null);

        String token = jwtService.generateToken(alice);
        mockMvc.perform(get("/api/videos/" + id).param("version", "1").contextPath("/api")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources.length()").value(2))
                .andExpect(jsonPath("$.sources[0].source").value("carte : vpc-prod"))
                .andExpect(jsonPath("$.sources[1].aConfirmer").value(true))
                .andExpect(jsonPath("$.seriesVoice").value("nova"));
        mockMvc.perform(get("/api/videos/" + id).contextPath("/api").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.sources.length()").value(0));
    }

    @Test
    @DisplayName("Voix de série : fixée au 1er épisode, gardée, modifiable pour la série entière ; refus hors série")
    void seriesVoice() {
        VideoService.PublishedVideo ep1 = publish("Ép. 1", "Cours EKS", null, List.of(), "nova", "posée");
        UUID seriesId = ep1.series().getId();
        publish("Ép. 2", "Cours EKS", null, List.of(), null, null);
        assertThat(seriesRepository.findById(seriesId).orElseThrow().getVoice()).isEqualTo("nova");

        publish("Ép. 1", null, ep1.video().getId(), List.of(), "ASH", null);
        VideoSeries changed = seriesRepository.findById(seriesId).orElseThrow();
        assertThat(changed.getVoice()).isEqualTo("ash");
        assertThat(changed.getVoiceInstructions()).isEqualTo("posée");

        assertThatThrownBy(() -> publish("Seule", null, null, List.of(), "nova", null))
                .isInstanceOf(VideoRejectedException.class).hasMessageContaining("SÉRIE");
        assertThatThrownBy(() -> publish("Mauvaise", "Cours EKS", null, List.of(), "voix !", null))
                .isInstanceOf(VideoRejectedException.class).hasMessageContaining("voix invalide");
    }

    @Test
    @DisplayName("Isolation : findSeries d'un autre compte ou d'un autre sujet vide ; GET d'un autre compte 404")
    void isolation() throws Exception {
        VideoService.PublishedVideo ep1 = publish("Ép. 1", "Cours EKS", null,
                List.of(new VideoSource("a", "b", false)), "nova", null);
        assertThat(videoService.findSeries(alice.getId(), subject.getId(), "cours eks")).isPresent();
        assertThat(videoService.findSeries(alice.getId(), subject.getId(), ep1.series().getId().toString())).isPresent();
        assertThat(videoService.findSeries(bob.getId(), subject.getId(), "Cours EKS")).isEmpty();
        assertThat(videoService.findSeries(bob.getId(), subject.getId(), ep1.series().getId().toString())).isEmpty();
        Workspace elsewhere = VideoTestSupport.runnerWorkspace(workspaceRepository, alice.getId());
        assertThat(videoService.findSeries(alice.getId(), elsewhere.getId(), ep1.series().getId().toString())).isEmpty();

        mockMvc.perform(get("/api/videos/" + ep1.video().getId()).contextPath("/api")
                        .header("Authorization", "Bearer " + jwtService.generateToken(bob)))
                .andExpect(status().isNotFound());
    }
}
