package fr.claudegateway.videos;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.AtelierProperties;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.quota.ProviderCostCalculator;
import fr.claudegateway.quota.TurnExtras;
import fr.claudegateway.quota.TurnTokens;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * <b>Le devis d'une vidéo</b> (F-216 / SF-216-01), en base H2 : coût d'un tour mesuré (ou défaut), devis rangé ouvert,
 * supersession, retouche, et isolation par compte.
 */
@SpringBootTest
@ActiveProfiles("test")
class VideoQuoteIntegrationTest {

    @Autowired private VideoQuoteService quoteService;
    @Autowired private VideoQuoteRepository quoteRepository;
    @Autowired private VideoTurnCostReader turnCostReader;
    @Autowired private VideoService videoService;
    @Autowired private VideoStore store;
    @Autowired private VideoVersionRepository versionRepository;
    @Autowired private VideoRepository videoRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AtelierProperties atelierProperties;
    @Autowired private ProviderCostCalculator calculator;

    private final ObjectMapper mapper = new ObjectMapper();
    private User alice;
    private User bob;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        quoteRepository.deleteAll();
        versionRepository.deleteAll();
        videoRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM atelier_turn_measures");
        alice = VideoTestSupport.user(userRepository, "alice-video-devis@example.com", UserRole.USER);
        bob = VideoTestSupport.user(userRepository, "bob-video-devis@example.com", UserRole.USER);
        workspace = VideoTestSupport.runnerWorkspace(workspaceRepository, alice.getId());
    }

    private void measure(UUID userId, String model, Instant at, long input, long output, long cacheRead,
            long cacheWrite) {
        jdbcTemplate.update("INSERT INTO atelier_turn_measures (id, user_id, workspace_id, host_id, model, occurred_at, "
                        + "duration_ms, iterations, stop_cause, max_context_tokens, total_context_tokens, "
                        + "first_pass_cache_write, later_cache_write, input_tokens, output_tokens, cache_read_tokens, "
                        + "cache_write_tokens, web_search_requests, runner_refusals, runner_busy_waits) "
                        + "VALUES (?, ?, ?, NULL, ?, ?, 1000, 3, 'fin', 0, 0, 0, 0, ?, ?, ?, ?, 0, 0, 0)",
                UUID.randomUUID(), userId, workspace.getId(), model, Timestamp.from(at), input, output, cacheRead,
                cacheWrite);
    }

    @Test
    @DisplayName("Coût d'un tour : moyenne mesurée du compte sur 7 jours (≥ 5 tours), sinon défaut ; autres comptes, "
            + "autres modèles et tours anciens ignorés")
    void turnCostIsTheMeasuredAverageOfTheAccount() {
        String model = atelierProperties.model();
        Instant now = Instant.now();
        assertThat(turnCostReader.estimate(alice.getId()).measured()).isFalse();
        assertThat(turnCostReader.estimate(alice.getId()).costUsd()).isEqualByComparingTo("1.00");

        for (int i = 0; i < 4; i++) {
            measure(alice.getId(), model, now.minus(1, ChronoUnit.HOURS), 200_000, 4_000, 150_000, 10_000);
        }
        // 4 tours : pas assez — et le bruit (autre compte, autre modèle, tour ancien) ne compte pas.
        measure(bob.getId(), model, now, 9_000_000, 90_000, 0, 0);
        measure(alice.getId(), "un-autre-modele", now, 9_000_000, 90_000, 0, 0);
        measure(alice.getId(), model, now.minus(8, ChronoUnit.DAYS), 9_000_000, 90_000, 0, 0);
        assertThat(turnCostReader.estimate(alice.getId()).measured()).isFalse();

        measure(alice.getId(), model, now.minus(2, ChronoUnit.HOURS), 200_000, 4_000, 150_000, 10_000);
        VideoTurnCostReader.TurnCostEstimate estimate = turnCostReader.estimate(alice.getId());

        BigDecimal expected = calculator.calculate(new TurnTokens(40_000, 4_000, 150_000, 10_000), TurnExtras.NONE,
                model).amountUsd().setScale(4, RoundingMode.HALF_UP);
        assertThat(estimate.measured()).isTrue();
        assertThat(estimate.samples()).isEqualTo(5);
        assertThat(estimate.costUsd()).isEqualByComparingTo(expected);
        // Bob n'a qu'un tour : défaut, et rien d'Alice ne le gonfle.
        assertThat(turnCostReader.estimate(bob.getId()).measured()).isFalse();
    }

    @Test
    @DisplayName("Série : devis rangé ouvert, ligne « Coût estimé », consigne de question unique")
    void seriesQuoteIsStoredOpenWithTheLine() {
        VideoQuoteService.Outcome outcome = quoteService.quote(alice.getId(), workspace, mapper.createObjectNode()
                .put("episodes", 6).put("duree_min", 2.5).put("serie", "Le réseau de CAGIP"));

        assertThat(outcome.error()).as(outcome.content()).isFalse();
        assertThat(outcome.content()).contains("série « Le réseau de CAGIP » de 6 épisodes de 2 min 30")
                .contains("15 min au total").contains("Coût estimé : ").contains("rendu 0 $ — votre poste")
                .contains("valeur par défaut").contains("question UNIQUE (demander)").contains("le plan de la série");
        VideoQuote quote = quoteRepository.findByIdAndUserId(outcome.quoteId(), alice.getId()).orElseThrow();
        assertThat(quote.getStatus()).isEqualTo(VideoQuote.Status.OPEN);
        assertThat(quote.getKind()).isEqualTo(VideoQuoteKind.SERIES);
        assertThat(quote.getEpisodes()).isEqualTo(6);
        assertThat(quote.getEpisodeDurationS()).isEqualTo(150);
        assertThat(quote.getNarrationChars()).isEqualTo(2_250);
        assertThat(quote.getSeriesTitle()).isEqualTo("Le réseau de CAGIP");
        assertThat(quote.isTurnCostMeasured()).isFalse();
        assertThat(quote.getRenderMinutes()).isEqualTo(42);
        assertThat(quote.getLowUsd()).isLessThan(quote.getHighUsd());
        assertThat(quoteRepository.findByIdAndUserId(outcome.quoteId(), bob.getId())).isEmpty();
    }

    @Test
    @DisplayName("Supersession : un devis vidéo clôt les ouverts du sujet ; une retouche ne clôt que les retouches")
    void newQuotesSupersedeOpenOnes() {
        UUID series = quoteService.quote(alice.getId(), workspace, mapper.createObjectNode().put("episodes", 3)
                .put("duree_min", 2)).quoteId();
        UUID video = VideoTestSupport.publish(videoService, store,
                new VideoPlace(alice.getId(), workspace.getId(), workspace.getHostId()), "Ép. 1",
                VideoTestSupport.mp4(2_000), List.of(), null, null).video().getId();

        UUID retouche1 = quoteService.quote(alice.getId(), workspace, mapper.createObjectNode()
                .put("video", video.toString())).quoteId();
        assertThat(status(series)).isEqualTo(VideoQuote.Status.OPEN);
        UUID retouche2 = quoteService.quote(alice.getId(), workspace, mapper.createObjectNode()
                .put("video", video.toString()).put("retouches", 2)).quoteId();
        assertThat(status(retouche1)).isEqualTo(VideoQuote.Status.CLOSED);
        assertThat(status(series)).isEqualTo(VideoQuote.Status.OPEN);

        UUID single = quoteService.quote(alice.getId(), workspace, mapper.createObjectNode().put("duree_min", 1.5))
                .quoteId();
        assertThat(status(series)).isEqualTo(VideoQuote.Status.CLOSED);
        assertThat(status(retouche2)).isEqualTo(VideoQuote.Status.CLOSED);
        assertThat(status(single)).isEqualTo(VideoQuote.Status.OPEN);
        assertThat(quoteRepository.findById(single).orElseThrow().getKind()).isEqualTo(VideoQuoteKind.SINGLE);
        assertThat(quoteRepository.findById(single).orElseThrow().getClosedAt()).isNull();
        assertThat(quoteRepository.findById(series).orElseThrow().getClosedAt()).isNotNull();
    }

    @Test
    @DisplayName("Retouche : durée lue sur la vidéo, consigne selon le seuil ; vidéo d'un autre compte = introuvable")
    void retoucheReadsTheVideoAndIsIsolated() {
        UUID video = VideoTestSupport.publish(videoService, store,
                new VideoPlace(alice.getId(), workspace.getId(), workspace.getHostId()), "Cours EKS",
                VideoTestSupport.mp4(2_000), List.of(), null, null).video().getId();

        VideoQuoteService.Outcome small = quoteService.quote(alice.getId(), workspace,
                mapper.createObjectNode().put("video", video.toString()));
        assertThat(small.error()).as(small.content()).isFalse();
        assertThat(small.content()).contains("retouche de « Cours EKS » (42 s), 1 retouche")
                .contains("Sous le seuil de 3,00 $").contains("sans question");
        VideoQuote quote = quoteRepository.findById(small.quoteId()).orElseThrow();
        assertThat(quote.getKind()).isEqualTo(VideoQuoteKind.RETOUCHE);
        assertThat(quote.getVideoId()).isEqualTo(video);
        assertThat(quote.getEpisodeDurationS()).isEqualTo(42);
        assertThat(quote.getVoiceLowUsd()).isEqualByComparingTo("0");

        VideoQuoteService.Outcome big = quoteService.quote(alice.getId(), workspace,
                mapper.createObjectNode().put("video", video.toString()).put("retouches", 10));
        assertThat(big.content()).contains("Au-dessus du seuil de 3,00 $").contains("demander");

        Workspace bobWorkspace = VideoTestSupport.runnerWorkspace(workspaceRepository, bob.getId());
        long before = quoteRepository.count();
        VideoQuoteService.Outcome foreign = quoteService.quote(bob.getId(), bobWorkspace,
                mapper.createObjectNode().put("video", video.toString()));
        assertThat(foreign.error()).isTrue();
        assertThat(foreign.content()).contains("Vidéo introuvable").doesNotContain("Cours EKS");
        assertThat(quoteRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("Erreurs : durée absente, bornes, nombre illisible — rien n'est rangé")
    void invalidRequestsAreRefused() {
        assertThat(quoteService.quote(alice.getId(), workspace, mapper.createObjectNode()).content())
                .contains("Donne duree_min");
        assertThat(quoteService.quote(alice.getId(), workspace, mapper.createObjectNode().put("duree_min", 45))
                .content()).contains("entre 0,25 et 30");
        assertThat(quoteService.quote(alice.getId(), workspace, mapper.createObjectNode().put("duree_min", 2)
                .put("episodes", 31)).content()).contains("episodes : entre 1 et 30");
        assertThat(quoteService.quote(alice.getId(), workspace, mapper.createObjectNode().put("duree_min", 2)
                .put("retouches", 11)).content()).contains("retouches : entre 0 et 10");
        assertThat(quoteService.quote(alice.getId(), workspace, mapper.createObjectNode().put("duree_min", 2)
                .put("caracteres", 10)).content()).contains("caracteres : entre 50");
        assertThat(quoteService.quote(alice.getId(), workspace, mapper.createObjectNode().put("duree_min", "deux"))
                .content()).contains("un nombre est attendu");
        assertThat(quoteService.quote(alice.getId(), workspace, mapper.createObjectNode().put("video", "abc"))
                .content()).contains("Vidéo introuvable");
        assertThat(quoteRepository.count()).isZero();
    }

    private VideoQuote.Status status(UUID id) {
        return quoteRepository.findById(id).orElseThrow().getStatus();
    }
}
