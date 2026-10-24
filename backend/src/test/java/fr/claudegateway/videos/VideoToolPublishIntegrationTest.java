package fr.claudegateway.videos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * <b>Publier une vidéo ou des sons depuis le poste</b> (F-213 / SF-213-01). Le runner est simulé (tranches
 * servies depuis un tableau d'octets), le reste de la chaîne est réel : préparation, envoi par morceaux,
 * stockage, inscription.
 */
@SpringBootTest
@ActiveProfiles("test")
class VideoToolPublishIntegrationTest {

    @Autowired private VideoToolExecutor executor;
    @Autowired private VideoService videoService;
    @Autowired private VideoStore store;
    @Autowired private VideoRepository videoRepository;
    @Autowired private VideoVersionRepository versionRepository;
    @Autowired private VideoSeriesRepository seriesRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;

    @MockBean private RunnerToolGateway runner;
    @MockBean private RunnerAuditService audit;

    private final ObjectMapper mapper = new ObjectMapper();
    private User alice;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        versionRepository.deleteAll();
        videoRepository.deleteAll();
        seriesRepository.deleteAll();
        alice = VideoTestSupport.user(userRepository, "alice-video-tool@example.com", UserRole.USER);
        workspace = VideoTestSupport.runnerWorkspace(workspaceRepository, alice.getId());
    }

    private void serve(String path, byte[] file) {
        when(runner.readFileBytes(org.mockito.ArgumentMatchers.any(), anyString(), eq(path), anyLong(), anyInt()))
                .thenAnswer(call -> VideoTestSupport.chunk(file, call.getArgument(3), call.getArgument(4)));
    }

    private ObjectNode video(String path, String title) {
        return mapper.createObjectNode().put("path", path).put("titre", title);
    }

    @Test
    @DisplayName("Nominal : rapatriée par tranches, rangée, chapitres et série ; le 2e épisode suit")
    void publishesInChunksWithChaptersAndSeries() throws Exception {
        byte[] file = VideoTestSupport.mp4(1_000_000);
        serve("out/ep1.mp4", file);
        ObjectNode input = video("out/ep1.mp4", "Le cluster EKS").put("serie", "Cours EKS").put("duree_s", 154);
        ArrayNode chapters = input.putArray("chapitres");
        chapters.addObject().put("t", 0).put("titre", "Intro");
        chapters.addObject().put("t", 30).put("titre", "Le plan de contrôle");

        VideoToolExecutor.VideoOutcome first = executor.publishVideo(alice.getId(), workspace, "call-1", input);

        assertThat(first.error()).as(first.content()).isFalse();
        VideoBlock block = first.block();
        assertThat(block.version()).isEqualTo(1);
        assertThat(block.chapters()).hasSize(2);
        assertThat(block.seriesTitle()).isEqualTo("Cours EKS");
        assertThat(block.episode()).isEqualTo(1);
        assertThat(block.durationS()).isEqualTo(154);
        VideoVersion version = videoService.requireVersion(alice.getId(), block.videoId(), null);
        assertThat(version.getSizeBytes()).isEqualTo(file.length);
        assertThat(version.getMime()).isEqualTo("video/mp4");
        assertThat(version.getStorageKey()).isEqualTo("videos/" + alice.getId() + "/" + block.videoId() + "/v1/video.mp4");
        assertThat(VideoTestSupport.readAll(store, version.getStorageKey())).isEqualTo(file);

        serve("out/ep2.mp4", VideoTestSupport.mp4(5_000));
        VideoToolExecutor.VideoOutcome second = executor.publishVideo(alice.getId(), workspace, "call-2",
                video("out/ep2.mp4", "Les nœuds").put("serie", "cours eks"));
        assertThat(second.error()).as(second.content()).isFalse();
        assertThat(second.block().seriesId()).isEqualTo(block.seriesId());
        assertThat(second.block().episode()).isEqualTo(2);
        assertThat(seriesRepository.findByUserIdAndWorkspaceId(alice.getId(), workspace.getId())).hasSize(1);
    }

    @Test
    @DisplayName("remplace : nouvelle version de la même vidéo, chapitres de la v2")
    void replaceCreatesNewVersion() {
        serve("v1.mp4", VideoTestSupport.mp4(2_000));
        UUID id = executor.publishVideo(alice.getId(), workspace, "c1", video("v1.mp4", "Cours")).block().videoId();
        serve("v2.mp4", VideoTestSupport.mp4(3_000));
        ObjectNode input = video("v2.mp4", "Cours corrigé").put("remplace", id.toString());
        input.putArray("chapitres").addObject().put("t", 5).put("titre", "Reprise");

        VideoToolExecutor.VideoOutcome outcome = executor.publishVideo(alice.getId(), workspace, "c2", input);

        assertThat(outcome.error()).as(outcome.content()).isFalse();
        assertThat(outcome.block().videoId()).isEqualTo(id);
        assertThat(outcome.block().version()).isEqualTo(2);
        assertThat(videoService.versions(alice.getId(), id)).extracting(VideoVersion::getVersion).containsExactly(2, 1);
        assertThat(videoService.require(alice.getId(), id).getTitle()).isEqualTo("Cours corrigé");
        assertThat(VideoChapter.fromJson(videoService.requireVersion(alice.getId(), id, 1).getChaptersJson())).isEmpty();
    }

    @Test
    @DisplayName("remplace d'un autre compte : identifiant inconnu, rien rangé")
    void replaceForeignIsUnknown() {
        User bob = VideoTestSupport.user(userRepository, "bob-video-tool@example.com", UserRole.USER);
        Workspace bobWorkspace = VideoTestSupport.runnerWorkspace(workspaceRepository, bob.getId());
        serve("b.mp4", VideoTestSupport.mp4(2_000));
        UUID bobVideo = executor.publishVideo(bob.getId(), bobWorkspace, "b1", video("b.mp4", "Bob")).block().videoId();

        VideoToolExecutor.VideoOutcome outcome = executor.publishVideo(alice.getId(), workspace, "a1",
                video("b.mp4", "Volée").put("remplace", bobVideo.toString()));

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("remplace inconnu");
        assertThat(videoService.versions(bob.getId(), bobVideo)).hasSize(1);
    }

    @Test
    @DisplayName("Plafond 300 Mo : refusé dès la première tranche, avant tout transfert")
    void tooLargeIsRefusedBeforeTransfer() {
        byte[] head = VideoTestSupport.mp4(1_000);
        when(runner.readFileBytes(org.mockito.ArgumentMatchers.any(), anyString(), eq("big.mp4"), anyLong(), anyInt()))
                .thenReturn(new RunnerCallResult(true, java.util.Base64.getEncoder().encodeToString(head), true, null,
                        2L, 301L * 1024 * 1024, null, null, "", false));

        VideoToolExecutor.VideoOutcome outcome = executor.publishVideo(alice.getId(), workspace, "c", video("big.mp4", "Gros"));

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("300 Mo");
        assertThat(videoRepository.findAll()).isEmpty();
        org.mockito.Mockito.verify(runner, org.mockito.Mockito.times(1))
                .readFileBytes(org.mockito.ArgumentMatchers.any(), anyString(), eq("big.mp4"), anyLong(), anyInt());
    }

    @Test
    @DisplayName("Quota 5 Go (D1) : refusé pour un compte plein, ADMIN exempté")
    void accountQuotaWithAdminExempt() {
        serve("seed.mp4", VideoTestSupport.mp4(1_000));
        UUID seeded = executor.publishVideo(alice.getId(), workspace, "s", video("seed.mp4", "Seed")).block().videoId();
        seedSize(alice.getId(), seeded, VideoLimits.DEFAULT_MAX_ACCOUNT_BYTES - 1_500);
        serve("next.mp4", VideoTestSupport.mp4(1_000));

        VideoToolExecutor.VideoOutcome refused = executor.publishVideo(alice.getId(), workspace, "n", video("next.mp4", "Suite"));
        assertThat(refused.error()).isTrue();
        assertThat(refused.content()).contains("Stockage vidéo plein").contains("sur 5,0 Go");

        User admin = VideoTestSupport.user(userRepository, "admin-video-tool@example.com", UserRole.ADMIN);
        Workspace adminWorkspace = VideoTestSupport.runnerWorkspace(workspaceRepository, admin.getId());
        serve("a.mp4", VideoTestSupport.mp4(1_000));
        UUID adminVideo = executor.publishVideo(admin.getId(), adminWorkspace, "a", video("a.mp4", "Admin")).block().videoId();
        seedSize(admin.getId(), adminVideo, VideoLimits.DEFAULT_MAX_ACCOUNT_BYTES + 1);
        VideoToolExecutor.VideoOutcome accepted = executor.publishVideo(admin.getId(), adminWorkspace, "a2",
                video("a.mp4", "Admin 2"));
        assertThat(accepted.error()).as(accepted.content()).isFalse();
    }

    /** Une version fictive très lourde, pour remplir le compte sans 5 Go d'octets. */
    private void seedSize(UUID userId, UUID videoId, long bytes) {
        versionRepository.save(VideoVersion.builder().id(UUID.randomUUID()).videoId(videoId).userId(userId)
                .version(99).storageKey("videos/fictif").sizeBytes(bytes).mime("video/mp4").build());
    }

    @Test
    @DisplayName("Signature inattendue, extension refusée, fichier modifié : rien rangé")
    void refusals() {
        serve("faux.mp4", "<html>pas une vidéo</html>".getBytes());
        assertThat(executor.publishVideo(alice.getId(), workspace, "c1", video("faux.mp4", "Faux")).content())
                .contains("signature");
        assertThat(executor.publishVideo(alice.getId(), workspace, "c2", video("film.mov", "Mov")).content())
                .contains(".mp4, .webm");

        byte[] file = VideoTestSupport.mp4(1_000_000);
        when(runner.readFileBytes(org.mockito.ArgumentMatchers.any(), anyString(), eq("bouge.mp4"), anyLong(), anyInt()))
                .thenAnswer(call -> {
                    long offset = call.getArgument(3);
                    RunnerCallResult result = VideoTestSupport.chunk(file, offset, call.getArgument(4));
                    return offset == 0 ? result : new RunnerCallResult(true, result.content(), result.truncated(),
                            null, 2L, (long) file.length + 10, null, null, "", false);
                });
        VideoToolExecutor.VideoOutcome changed = executor.publishVideo(alice.getId(), workspace, "c3",
                video("bouge.mp4", "Bouge"));
        assertThat(changed.error()).isTrue();
        assertThat(changed.content()).contains("a changé");
        assertThat(videoRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("Runner trop ancien ou projet hébergé : message clair")
    void oldRunnerAndHostedProject() {
        when(runner.readFileBytes(org.mockito.ArgumentMatchers.any(), anyString(), eq("old.mp4"), anyLong(), anyInt()))
                .thenReturn(RunnerCallResult.backendError("too_large", "Fichier trop volumineux"));
        assertThat(executor.publishVideo(alice.getId(), workspace, "c", video("old.mp4", "Old")).content())
                .contains("trop ancien");
        when(runner.readFileBytes(org.mockito.ArgumentMatchers.any(), anyString(), eq("old2.mp4"), anyLong(), anyInt()))
                .thenReturn(RunnerCallResult.backendError(RunnerErrorCodes.UNSUPPORTED_TOOL));
        assertThat(executor.publishVideo(alice.getId(), workspace, "c", video("old2.mp4", "Old")).content())
                .contains("trop ancien");

        Workspace hosted = workspaceRepository.save(Workspace.builder().userId(alice.getId()).name("hébergé")
                .executionTarget(WorkspaceExecutionTarget.SANDBOX).build());
        assertThat(executor.publishVideo(alice.getId(), hosted, "c", video("a.mp4", "A")).content())
                .contains("depuis un poste");
    }

    @Test
    @DisplayName("audio_publier : deux pistes libellées, un bloc, aucune galerie")
    void audioTracks() {
        serve("voix1.mp3", VideoTestSupport.mp3(4_000));
        serve("voix2.mp3", VideoTestSupport.mp3(6_000));
        ObjectNode input = mapper.createObjectNode().put("titre", "Auditions de voix");
        ArrayNode tracks = input.putArray("pistes");
        tracks.addObject().put("path", "voix1.mp3").put("libelle", "Voix 1 — grave, posée");
        tracks.addObject().put("path", "voix2.mp3").put("libelle", "Voix 2 — claire");

        VideoToolExecutor.AudioOutcome outcome = executor.publishAudio(alice.getId(), workspace, "a", input);

        assertThat(outcome.error()).as(outcome.content()).isFalse();
        assertThat(outcome.block().title()).isEqualTo("Auditions de voix");
        assertThat(outcome.block().tracks()).extracting(AudioBlock.Track::label)
                .containsExactly("Voix 1 — grave, posée", "Voix 2 — claire");
        assertThat(outcome.block().tracks()).extracting(AudioBlock.Track::mime).containsOnly("audio/mpeg");
        assertThat(videoRepository.findByUserIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(alice.getId(),
                workspace.getId(), VideoKind.VIDEO)).isEmpty();
        assertThat(videoRepository.findByUserIdAndWorkspaceIdAndKindOrderByCreatedAtDesc(alice.getId(),
                workspace.getId(), VideoKind.AUDIO)).hasSize(2);

        ObjectNode bad = mapper.createObjectNode();
        bad.putArray("pistes").addObject().put("path", "x.mp4").put("libelle", "x");
        assertThat(executor.publishAudio(alice.getId(), workspace, "b", bad).error()).isTrue();
    }

    @Test
    @DisplayName("Effacer le sujet efface ses vidéos (lignes et objets)")
    void deletingWorkspaceDeletesVideos() {
        serve("d.mp4", VideoTestSupport.mp4(2_000));
        VideoBlock block = executor.publishVideo(alice.getId(), workspace, "d", video("d.mp4", "À effacer")
                .put("serie", "Série")).block();
        String key = videoService.requireVersion(alice.getId(), block.videoId(), null).getStorageKey();

        videoService.deleteWorkspace(alice.getId(), workspace.getId());

        assertThat(videoRepository.findById(block.videoId())).isEmpty();
        assertThat(seriesRepository.findByUserIdAndWorkspaceId(alice.getId(), workspace.getId())).isEmpty();
        assertThat(store.size(key)).isEmpty();
    }
}
