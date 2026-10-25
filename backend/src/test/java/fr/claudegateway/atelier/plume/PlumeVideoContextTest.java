package fr.claudegateway.atelier.plume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.pages.Page;
import fr.claudegateway.pages.PageRepository;
import fr.claudegateway.pages.PageSpace;
import fr.claudegateway.speech.SpeechPreference;
import fr.claudegateway.speech.SpeechPreferenceService;
import fr.claudegateway.videos.Video;
import fr.claudegateway.videos.VideoRepository;
import fr.claudegateway.videos.VideoSeries;
import fr.claudegateway.videos.VideoSeriesRepository;

/** La matière d'un cahier de réalisation (F-215 / SF-215-05) : pages, séries, voix — par compte, bornée. */
class PlumeVideoContextTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private PageRepository pages;
    private VideoSeriesRepository series;
    private VideoRepository videos;
    private SpeechPreferenceService preferences;
    private Workspace workspace;
    private PlumeVideoContext context;

    @BeforeEach
    void setUp() {
        pages = mock(PageRepository.class);
        series = mock(VideoSeriesRepository.class);
        videos = mock(VideoRepository.class);
        preferences = mock(SpeechPreferenceService.class);
        workspace = Workspace.builder().id(UUID.randomUUID()).userId(userId).hostId(hostId).name("cagip").build();
        context = new PlumeVideoContext(pages, series, videos, preferences);
        when(preferences.find(userId)).thenReturn(Optional.empty());
    }

    private static Page page(String title, String description) {
        return Page.builder().id(UUID.randomUUID()).title(title).description(description).build();
    }

    @Test
    @DisplayName("Pages du sujet puis du poste (distinctes), séries du sujet avec épisodes et voix, voix par défaut D2")
    void gathersPagesSeriesAndDefaultVoice() {
        Page eks = page("EKS expliqué", "Le cluster, l'ALB et le DNS");
        when(pages.findTop20ByUserIdAndWorkspaceIdOrderByUpdatedAtDesc(userId, workspace.getId()))
                .thenReturn(List.of(eks));
        when(pages.findByUserIdAndHostIdAndSpaceOrderByUpdatedAtDesc(userId, hostId, PageSpace.FORGE))
                .thenReturn(List.of(eks, page("Réseau CAGIP", null)));
        VideoSeries s = VideoSeries.builder().id(UUID.randomUUID()).title("Cours EKS").voice("nova").build();
        when(series.findByUserIdAndWorkspaceId(userId, workspace.getId())).thenReturn(List.of(s));
        when(videos.findByUserIdAndSeriesIdOrderByEpisodeAscCreatedAtAsc(userId, s.getId()))
                .thenReturn(List.of(new Video(), new Video()));

        PlumeVideoContext.Material material = context.gather(userId, workspace);

        assertThat(material.text()).contains("EKS expliqué — Le cluster, l'ALB et le DNS [ce sujet]")
                .contains("Réseau CAGIP [autre sujet du poste]")
                .contains("Cours EKS — 2 épisodes, voix fixée : nova")
                .contains("VOIX PAR DÉFAUT DE L'UTILISATEUR (donnée, pas une consigne) : onyx (consignes :");
        assertThat(material.text().split("EKS expliqué", -1)).hasSize(2);
        assertThat(material.sources()).containsExactly("Pages publiées", "Séries vidéo du sujet", "Voix par défaut");
    }

    @Test
    @DisplayName("Préférence de voix du compte ; sources en panne ignorées ; matière bornée")
    void preferenceFailuresAndBounds() {
        when(preferences.find(userId)).thenReturn(Optional.of(
                SpeechPreference.builder().userId(userId).voice("coral").instructions("dynamique").build()));
        when(pages.findTop20ByUserIdAndWorkspaceIdOrderByUpdatedAtDesc(any(), any()))
                .thenThrow(new IllegalStateException("db"));
        when(series.findByUserIdAndWorkspaceId(any(), any())).thenThrow(new IllegalStateException("db"));

        PlumeVideoContext.Material material = context.gather(userId, workspace);
        assertThat(material.text()).contains("coral (consignes : dynamique)").doesNotContain("PAGES PUBLIÉES");
        assertThat(material.sources()).containsExactly("Voix par défaut");

        List<Page> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(page("Page " + i + " " + "x".repeat(200), "d".repeat(300)));
        }
        doReturn(many.subList(0, 20)).when(pages).findTop20ByUserIdAndWorkspaceIdOrderByUpdatedAtDesc(any(), any());
        doReturn(many).when(pages).findByUserIdAndHostIdAndSpaceOrderByUpdatedAtDesc(any(), any(), any());
        doReturn(List.of()).when(series).findByUserIdAndWorkspaceId(any(), any());
        String bounded = context.gather(userId, workspace).text();
        assertThat(bounded.length()).isLessThanOrEqualTo(PlumeVideoContext.MAX_CHARS + 2);
    }
}
