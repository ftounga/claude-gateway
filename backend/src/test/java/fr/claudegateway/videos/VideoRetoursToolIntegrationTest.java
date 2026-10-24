package fr.claudegateway.videos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * <b>L'agent et les retours à la seconde</b> (F-214 / SF-214-01) : {@code video_retours} en lecture, et
 * {@code video_publier(remplace, retours_traites)} qui clôt les retours. Runner simulé.
 */
@SpringBootTest
@ActiveProfiles("test")
class VideoRetoursToolIntegrationTest {

    @Autowired private VideoToolExecutor executor;
    @Autowired private VideoCommentService commentService;
    @Autowired private VideoCommentRepository commentRepository;
    @Autowired private VideoRepository videoRepository;
    @Autowired private VideoVersionRepository versionRepository;
    @Autowired private VideoSeriesRepository seriesRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;

    @MockBean private RunnerToolGateway runner;
    @MockBean private RunnerAuditService audit;

    private final ObjectMapper mapper = new ObjectMapper();
    private User alice;
    private User bob;
    private Workspace workspace;
    private UUID video;

    @BeforeEach
    void setUp() {
        commentRepository.deleteAll();
        versionRepository.deleteAll();
        videoRepository.deleteAll();
        seriesRepository.deleteAll();
        alice = VideoTestSupport.user(userRepository, "alice-video-retours@example.com", UserRole.USER);
        bob = VideoTestSupport.user(userRepository, "bob-video-retours@example.com", UserRole.USER);
        workspace = VideoTestSupport.runnerWorkspace(workspaceRepository, alice.getId());
        serve("v1.mp4", VideoTestSupport.mp4(2_000));
        video = executor.publishVideo(alice.getId(), workspace, "c1",
                mapper.createObjectNode().put("path", "v1.mp4").put("titre", "Cours")).block().videoId();
    }

    private void serve(String path, byte[] file) {
        when(runner.readFileBytes(org.mockito.ArgumentMatchers.any(), anyString(), eq(path), anyLong(), anyInt()))
                .thenAnswer(call -> VideoTestSupport.chunk(file, call.getArgument(3), call.getArgument(4)));
    }

    @Test
    @DisplayName("video_retours : liste identifiant, version, instant, image, statut, texte ; filtre de version")
    void readsComments() {
        VideoComment first = commentService.create(alice.getId(), video, null, 10_133L, null, "Bannière en noir");
        commentService.create(alice.getId(), video, null, 80_000L, 95_000L, "Trop rapide");
        commentService.send(alice.getId(), video, null);

        VideoToolExecutor.VideoOutcome outcome = executor.readComments(alice.getId(),
                mapper.createObjectNode().put("video", video.toString()));

        assertThat(outcome.error()).as(outcome.content()).isFalse();
        assertThat(outcome.content()).contains("« Cours »").contains("2 retours")
                .contains("[" + first.getId() + "] v1 00:10 (image 304) [ENVOYE] — Bannière en noir")
                .contains("01:20–01:35 (images 2400–2850)");
        assertThat(executor.readComments(alice.getId(),
                mapper.createObjectNode().put("video", video.toString()).put("version", 2)).content())
                .contains("0 retour.");
    }

    @Test
    @DisplayName("video_retours : vidéo d'un autre compte ou identifiant absent = refus")
    void readIsIsolated() {
        commentService.create(alice.getId(), video, null, 1_000L, null, "Secret d'Alice");

        VideoToolExecutor.VideoOutcome foreign = executor.readComments(bob.getId(),
                mapper.createObjectNode().put("video", video.toString()));

        assertThat(foreign.error()).isTrue();
        assertThat(foreign.content()).contains("Vidéo inconnue").doesNotContain("Secret");
        assertThat(executor.readComments(alice.getId(), mapper.createObjectNode()).error()).isTrue();
        assertThat(executor.readComments(alice.getId(), mapper.createObjectNode().put("video", "abc")).error()).isTrue();
    }

    @Test
    @DisplayName("video_publier(remplace, retours_traites) : traité/rejeté + motif + version ; déjà clos et étrangers ignorés")
    void republishResolvesComments() {
        UUID banner = commentService.create(alice.getId(), video, null, 10_133L, null, "Bannière en noir").getId();
        UUID speed = commentService.create(alice.getId(), video, null, 80_000L, 95_000L, "Trop rapide").getId();
        commentService.send(alice.getId(), video, null);
        UUID unsent = commentService.create(alice.getId(), video, null, 3_000L, null, "Pas envoyé").getId();
        Workspace bobWorkspace = VideoTestSupport.runnerWorkspace(workspaceRepository, bob.getId());
        serve("b.mp4", VideoTestSupport.mp4(2_000));
        UUID bobVideo = executor.publishVideo(bob.getId(), bobWorkspace, "b",
                mapper.createObjectNode().put("path", "b.mp4").put("titre", "Bob")).block().videoId();
        UUID bobComment = commentService.create(bob.getId(), bobVideo, null, 0L, null, "À Bob").getId();

        serve("v2.mp4", VideoTestSupport.mp4(3_000));
        ObjectNode input = mapper.createObjectNode().put("path", "v2.mp4").put("titre", "Cours")
                .put("remplace", video.toString());
        ArrayNode resolved = input.putArray("retours_traites");
        resolved.addObject().put("id", banner.toString()).put("statut", "traite");
        resolved.addObject().put("id", speed.toString()).put("statut", "rejete").put("motif", "Rythme imposé par la voix");
        resolved.addObject().put("id", unsent.toString()).put("statut", "traite");
        resolved.addObject().put("id", bobComment.toString()).put("statut", "traite");

        VideoToolExecutor.VideoOutcome outcome = executor.publishVideo(alice.getId(), workspace, "c2", input);

        assertThat(outcome.error()).as(outcome.content()).isFalse();
        assertThat(outcome.block().version()).isEqualTo(2);
        assertThat(outcome.content()).contains("Retours : 2 traités, 1 rejeté").contains(bobComment.toString());
        VideoComment bannerAfter = commentRepository.findById(banner).orElseThrow();
        assertThat(bannerAfter.getStatus()).isEqualTo(VideoCommentStatus.TRAITE);
        assertThat(bannerAfter.getResolvedVersion()).isEqualTo(2);
        VideoComment speedAfter = commentRepository.findById(speed).orElseThrow();
        assertThat(speedAfter.getStatus()).isEqualTo(VideoCommentStatus.REJETE);
        assertThat(speedAfter.getResolution()).isEqualTo("Rythme imposé par la voix");
        assertThat(commentRepository.findById(unsent).orElseThrow().getStatus()).isEqualTo(VideoCommentStatus.TRAITE);
        assertThat(commentRepository.findById(bobComment).orElseThrow().getStatus()).isEqualTo(VideoCommentStatus.OUVERT);

        // Une seconde clôture du même retour est ignorée (déjà clos).
        serve("v3.mp4", VideoTestSupport.mp4(3_500));
        ObjectNode again = mapper.createObjectNode().put("path", "v3.mp4").put("titre", "Cours")
                .put("remplace", video.toString());
        again.putArray("retours_traites").addObject().put("id", banner.toString()).put("statut", "rejete");
        VideoToolExecutor.VideoOutcome third = executor.publishVideo(alice.getId(), workspace, "c3", again);
        assertThat(third.content()).contains("0 traité, 0 rejeté").contains("Ignorés");
        assertThat(commentRepository.findById(banner).orElseThrow().getStatus()).isEqualTo(VideoCommentStatus.TRAITE);
    }

    @Test
    @DisplayName("retours_traites sans remplace, ou mal formé : refusé AVANT tout transfert")
    void refusedBeforeTransfer() {
        UUID id = commentService.create(alice.getId(), video, null, 0L, null, "x").getId();
        org.mockito.Mockito.clearInvocations(runner);
        ObjectNode noReplace = mapper.createObjectNode().put("path", "new.mp4").put("titre", "Neuve");
        noReplace.putArray("retours_traites").addObject().put("id", id.toString()).put("statut", "traite");

        VideoToolExecutor.VideoOutcome outcome = executor.publishVideo(alice.getId(), workspace, "c", noReplace);
        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("remplace");

        ObjectNode badStatus = mapper.createObjectNode().put("path", "new.mp4").put("titre", "Neuve")
                .put("remplace", video.toString());
        badStatus.putArray("retours_traites").addObject().put("id", id.toString()).put("statut", "peut-être");
        assertThat(executor.publishVideo(alice.getId(), workspace, "c", badStatus).content()).contains("statut");

        verify(runner, never()).readFileBytes(org.mockito.ArgumentMatchers.any(), anyString(), anyString(), anyLong(),
                anyInt());
        assertThat(commentRepository.findById(id).orElseThrow().getStatus()).isEqualTo(VideoCommentStatus.OUVERT);
    }

    @Test
    @DisplayName("Catalogue : video_retours donné avec video_publier, retours_traites déclaré")
    void catalogDeclaresTool() {
        assertThat(VideoToolCatalog.retoursDefinition().name()).isEqualTo("video_retours");
        assertThat(VideoToolCatalog.isRetoursTool("video_retours")).isTrue();
        assertThat(VideoToolCatalog.isVideoTool("video_retours")).isFalse();
        assertThat(VideoToolCatalog.videoDefinition().toString()).contains("retours_traites");
    }
}
