package fr.claudegateway.videos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.quota.TurnCost;
import fr.claudegateway.quota.TurnExtras;
import fr.claudegateway.quota.TurnTokens;
import fr.claudegateway.quota.UsageLedgerService;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * <b>Le coût réel d'une vidéo</b> (F-216 / SF-216-02), en base H2 : imputation des relevés du journal au devis ouvert
 * du sujet, rattachement à la publication, coût du tour qui publie, clôtures, API, et isolation par compte.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VideoCostTrackerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private UsageLedgerService ledger;
    @Autowired private VideoCostTracker tracker;
    @Autowired private VideoQuoteService quoteService;
    @Autowired private VideoQuoteRepository quoteRepository;
    @Autowired private VideoService videoService;
    @Autowired private VideoStore store;
    @Autowired private VideoVersionRepository versionRepository;
    @Autowired private VideoRepository videoRepository;
    @Autowired private VideoSeriesRepository seriesRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;

    private final ObjectMapper mapper = new ObjectMapper();
    private User alice;
    private User bob;
    private Workspace subject;
    private Workspace bobSubject;

    @BeforeEach
    void setUp() {
        quoteRepository.deleteAll();
        versionRepository.deleteAll();
        videoRepository.deleteAll();
        seriesRepository.deleteAll();
        alice = VideoTestSupport.user(userRepository, "alice-video-cost@example.com", UserRole.USER);
        bob = VideoTestSupport.user(userRepository, "bob-video-cost@example.com", UserRole.USER);
        subject = VideoTestSupport.runnerWorkspace(workspaceRepository, alice.getId());
        bobSubject = VideoTestSupport.runnerWorkspace(workspaceRepository, bob.getId());
    }

    private void spend(User user, Workspace workspace, String usd) {
        ledger.recordTurn(user.getId(), workspace.getId(), workspace.getHostId(), TurnTokens.of(1_000L, 100L),
                TurnExtras.NONE, new TurnCost(new BigDecimal(usd), TurnCost.Source.CALCULATED, "claude-opus-5-5",
                        "test", false));
    }

    private UUID quote(User user, Workspace workspace, String json) throws Exception {
        VideoQuoteService.Outcome outcome = quoteService.quote(user.getId(), workspace,
                mapper.readTree(json));
        assertThat(outcome.error()).as(outcome.content()).isFalse();
        return outcome.quoteId();
    }

    private Video publish(Workspace workspace, String title, String series, UUID replace) {
        return VideoTestSupport.publish(videoService, store,
                new VideoPlace(workspace.getUserId(), workspace.getId(), workspace.getHostId()), title,
                VideoTestSupport.mp4(2_000), List.of(), series, replace).video();
    }

    private VideoQuote quoteRow(UUID id) {
        return quoteRepository.findById(id).orElseThrow();
    }

    private Video videoRow(UUID id) {
        return videoRepository.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("Vidéo unique : plan et voix imputés, rattachés à la publication, puis le tour qui publie ; clôture")
    void singleVideoEndToEnd() throws Exception {
        UUID quoteId = quote(alice, subject, "{\"duree_min\": 2}");
        spend(alice, subject, "1.10");      // le tour du plan et du devis
        spend(alice, subject, "0.05");      // la voix off
        Video video = publish(subject, "Cours", null, null);

        VideoVersion version = versionRepository.findByVideoIdAndUserIdAndVersion(video.getId(), alice.getId(), 1)
                .orElseThrow();
        assertThat(version.getQuoteId()).isEqualTo(quoteId);
        assertThat(version.getCostUsd()).isEqualByComparingTo("1.15");
        assertThat(version.isCostPending()).isTrue();

        spend(alice, subject, "0.90");      // le tour qui publie, inscrit à sa fin
        tracker.turnEnded(alice.getId(), subject.getId());

        Video row = videoRow(video.getId());
        VideoQuote quote = quoteRow(quoteId);
        assertThat(row.getCostUsd()).isEqualByComparingTo("2.05");
        assertThat(row.getEstimateLowUsd()).isEqualByComparingTo(quote.getLowUsd());
        assertThat(row.getEstimateHighUsd()).isEqualByComparingTo(quote.getHighUsd());
        assertThat(quote.getSpentUsd()).isEqualByComparingTo("2.05");
        assertThat(quote.getStatus()).isEqualTo(VideoQuote.Status.CLOSED);

        // Clos : un coût ultérieur du sujet ne lui est plus imputé.
        spend(alice, subject, "3.00");
        assertThat(quoteRow(quoteId).getSpentUsd()).isEqualByComparingTo("2.05");
        assertThat(videoRow(video.getId()).getCostUsd()).isEqualByComparingTo("2.05");
    }

    @Test
    @DisplayName("Série : chaque épisode reçoit sa part, deux épisodes d'un même tour partagent son coût ; clôture au dernier")
    void seriesSplitsAndClosesWhenComplete() throws Exception {
        UUID quoteId = quote(alice, subject, "{\"episodes\": 3, \"duree_min\": 2, \"serie\": \"Cours EKS\"}");
        spend(alice, subject, "1.00");
        Video ep1 = publish(subject, "Ép. 1", "Cours EKS", null);
        spend(alice, subject, "0.40");
        tracker.turnEnded(alice.getId(), subject.getId());
        assertThat(quoteRow(quoteId).getStatus()).isEqualTo(VideoQuote.Status.OPEN);

        spend(alice, subject, "0.60");
        Video ep2 = publish(subject, "Ép. 2", "Cours EKS", null);
        Video ep3 = publish(subject, "Ép. 3", "Cours EKS", null);
        spend(alice, subject, "2.00");      // le tour qui a publié les deux : parts égales
        tracker.turnEnded(alice.getId(), subject.getId());

        assertThat(videoRow(ep1.getId()).getCostUsd()).isEqualByComparingTo("1.40");
        assertThat(videoRow(ep2.getId()).getCostUsd()).isEqualByComparingTo("1.60");
        assertThat(videoRow(ep3.getId()).getCostUsd()).isEqualByComparingTo("1.00");
        VideoQuote quote = quoteRow(quoteId);
        assertThat(quote.getSpentUsd()).isEqualByComparingTo("4.00");
        assertThat(quote.getStatus()).isEqualTo(VideoQuote.Status.CLOSED);
        // L'estimation d'un épisode est le tiers de celle de la série.
        assertThat(videoRow(ep1.getId()).getEstimateHighUsd().multiply(BigDecimal.valueOf(3)))
                .isGreaterThanOrEqualTo(quote.getHighUsd());
    }

    @Test
    @DisplayName("Retouche : se superpose à la série, ne se rattache qu'à sa vidéo ; publication sans devis = non mesurée")
    void retoucheOnlyAttachesToItsVideo() throws Exception {
        Video lonely = publish(subject, "Sans devis", null, null);
        assertThat(videoRow(lonely.getId()).getCostUsd()).isNull();

        UUID seriesQuote = quote(alice, subject, "{\"episodes\": 2, \"duree_min\": 2, \"serie\": \"Cours\"}");
        Video ep1 = publish(subject, "Ép. 1", "Cours", null);
        tracker.turnEnded(alice.getId(), subject.getId());

        UUID retouche = quote(alice, subject, "{\"video\": \"" + ep1.getId() + "\"}");
        assertThat(quoteRow(seriesQuote).getStatus()).isEqualTo(VideoQuote.Status.OPEN);
        spend(alice, subject, "0.50");      // va au devis le plus récent : la retouche
        assertThat(quoteRow(retouche).getSpentUsd()).isEqualByComparingTo("0.50");
        assertThat(quoteRow(seriesQuote).getSpentUsd()).isEqualByComparingTo("0");

        // Un autre épisode publié pendant la retouche se rattache à la série, pas à la retouche.
        Video ep2 = publish(subject, "Ép. 2", "Cours", null);
        assertThat(versionRepository.findByVideoIdAndUserIdAndVersion(ep2.getId(), alice.getId(), 1).orElseThrow()
                .getQuoteId()).isEqualTo(seriesQuote);
        tracker.turnEnded(alice.getId(), subject.getId());

        publish(subject, "Ép. 1", "Cours", ep1.getId());
        VideoVersion v2 = versionRepository.findByVideoIdAndUserIdAndVersion(ep1.getId(), alice.getId(), 2)
                .orElseThrow();
        assertThat(v2.getQuoteId()).isEqualTo(retouche);
        assertThat(v2.getCostUsd()).isEqualByComparingTo("0.50");
        tracker.turnEnded(alice.getId(), subject.getId());
        assertThat(quoteRow(retouche).getStatus()).isEqualTo(VideoQuote.Status.CLOSED);
        assertThat(quoteRow(seriesQuote).getStatus()).isEqualTo(VideoQuote.Status.CLOSED);
        // L'estimation de la retouche s'ajoute à celle de l'épisode.
        assertThat(videoRow(ep1.getId()).getEstimateHighUsd())
                .isGreaterThan(quoteRow(seriesQuote).getHighUsd().divide(BigDecimal.valueOf(2)));
    }

    @Test
    @DisplayName("Inactivité : un devis sans activité depuis 48 h est clos au lieu de recevoir le coût")
    void idleQuoteIsClosed() throws Exception {
        UUID quoteId = quote(alice, subject, "{\"duree_min\": 2}");
        VideoQuote quote = quoteRow(quoteId);
        quote.setLastActivityAt(OffsetDateTime.now().minusHours(49));
        quoteRepository.save(quote);

        spend(alice, subject, "1.00");

        VideoQuote after = quoteRow(quoteId);
        assertThat(after.getStatus()).isEqualTo(VideoQuote.Status.CLOSED);
        assertThat(after.getSpentUsd()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Isolation : les coûts de Bob ne touchent jamais le devis d'Alice ; le total du mois est par compte")
    void costsAreIsolatedPerAccount() throws Exception {
        UUID aliceQuote = quote(alice, subject, "{\"duree_min\": 2}");
        UUID bobQuote = quote(bob, bobSubject, "{\"duree_min\": 1}");
        spend(bob, bobSubject, "4.00");
        // Une ligne de Bob qui porterait le sujet d'Alice : filtrée par (user_id, workspace_id).
        ledger.recordTurn(bob.getId(), subject.getId(), null, TurnTokens.of(10L, 10L), TurnExtras.NONE,
                new TurnCost(new BigDecimal("9.00"), TurnCost.Source.CALCULATED, "m", "t", false));
        spend(alice, subject, "1.25");

        assertThat(quoteRow(aliceQuote).getSpentUsd()).isEqualByComparingTo("1.25");
        assertThat(quoteRow(bobQuote).getSpentUsd()).isEqualByComparingTo("4.00");
        assertThat(tracker.month(alice.getId()).spentUsd()).isEqualByComparingTo("1.25");
        assertThat(tracker.month(alice.getId()).quotes()).isEqualTo(1);
    }

    @Test
    @DisplayName("API : coût et estimation dans la galerie et la fiche, total du mois ; 404 croisé, 401 sans jeton")
    void apiExposesCosts() throws Exception {
        quote(alice, subject, "{\"episodes\": 2, \"duree_min\": 2, \"serie\": \"Cours\"}");
        spend(alice, subject, "1.00");
        Video ep1 = publish(subject, "Ép. 1", "Cours", null);
        spend(alice, subject, "0.234");
        tracker.turnEnded(alice.getId(), subject.getId());
        UUID quoteId = quote(alice, subject, "{\"duree_min\": 1}");
        spend(alice, subject, "0.50");
        Video single = publish(subject, "Seule", null, null);
        tracker.turnEnded(alice.getId(), subject.getId());
        assertThat(quoteRow(quoteId).getStatus()).isEqualTo(VideoQuote.Status.CLOSED);

        String alice = "Bearer " + jwtService.generateToken(this.alice);
        String bob = "Bearer " + jwtService.generateToken(this.bob);
        mockMvc.perform(get("/videos").param("workspaceId", subject.getId().toString()).header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.series[0].costUsd").value(1.23))
                .andExpect(jsonPath("$.series[0].estimateLowUsd").isNumber())
                .andExpect(jsonPath("$.series[0].episodes[0].costUsd").value(1.23))
                .andExpect(jsonPath("$.videos[0].costUsd").value(0.50))
                .andExpect(jsonPath("$.videos[0].estimateHighUsd").isNumber())
                // F-224 / SF-224-06 : les mêmes montants en euros, au taux unique (0,92).
                .andExpect(jsonPath("$.series[0].costEur").value(1.14))
                .andExpect(jsonPath("$.videos[0].costEur").value(0.46))
                .andExpect(jsonPath("$.videos[0].estimateHighEur").isNumber());
        mockMvc.perform(get("/videos/" + single.getId()).header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.costUsd").value(0.50))
                .andExpect(jsonPath("$.costEur").value(0.46))
                .andExpect(jsonPath("$.estimateLowUsd").isNumber());
        mockMvc.perform(get("/videos/costs").header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spentUsd").value(1.73))
                .andExpect(jsonPath("$.spentEur").value(1.60))
                .andExpect(jsonPath("$.quotes").value(2))
                .andExpect(jsonPath("$.month").isString());
        mockMvc.perform(get("/videos/costs").header("Authorization", bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spentUsd").value(0.0))
                .andExpect(jsonPath("$.quotes").value(0));
        mockMvc.perform(get("/videos/" + ep1.getId()).header("Authorization", bob))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/videos/costs")).andExpect(status().isUnauthorized());
    }
}
