package fr.claudegateway.jarvis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.quota.TurnExtras;
import fr.claudegateway.quota.TurnTokens;
import fr.claudegateway.quota.UsageLedgerService;
import fr.claudegateway.quota.UsageTag;
import fr.claudegateway.quota.UsageTurn;
import fr.claudegateway.quota.UsageTurnRepository;
import fr.claudegateway.teams.meeting.stt.TranscriptionProvider;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-244 / SF-244-01 — <b>chaque euro dépensé par Jarvis porte l'origine {@code JARVIS} et son poste</b> au journal du
 * coût ({@code usage_turns}), et la journée Jarvis n'en perd aucun. Un tour tapé reste {@code GATEWAY}. Isolation : une
 * ligne ne porte que le compte qui l'a causée.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisCostLabelIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwtService;
    @Autowired private JarvisUsageService usage;
    @Autowired private UsageLedgerService ledger;
    @Autowired private QuotaService quota;
    @Autowired private UsageTurnRepository turns;

    @MockBean private TranscriptionProvider dictation;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        reset(dictation);
        when(dictation.transcribe(any(), any(), any())).thenReturn(new TranscriptionProvider.Transcript("bonjour", "fr"));
        alice = users.save(user("jarvis-label-a-"));
        bob = users.save(user("jarvis-label-b-"));
    }

    private static User user(String prefix) {
        return User.builder().email(prefix + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).createdAt(OffsetDateTime.now()).build();
    }

    private List<UsageTurn> rowsOf(UUID userId) {
        return turns.findAll().stream().filter(t -> userId.equals(t.getUserId())).toList();
    }

    @Test
    @DisplayName("voix, flux, retranscription, dictée : une ligne ANNEXE JARVIS par poste, au coût réel, et la journée complète")
    void everyJarvisCostIsLabelledAndCounted() {
        usage.addSpeech(alice.getId(), null, null, "elevenlabs", 1_000L, new BigDecimal("0.300000"));
        usage.addStream(alice.getId(), 60L, new BigDecimal("0.010000"));
        usage.addUtterance(alice.getId(), 2L, new BigDecimal("0.000150"));
        usage.addDictation(alice.getId(), 3L, new BigDecimal("0.000300"));

        List<UsageTurn> rows = rowsOf(alice.getId());
        assertThat(rows).hasSize(4).allMatch(r -> r.getOrigin() == UsageTurn.Origin.JARVIS
                && r.getKind() == UsageTurn.Kind.ANNEXE);
        assertThat(rows).extracting(UsageTurn::getJarvisPart).containsExactlyInAnyOrder(UsageTurn.JarvisPart.VOICE,
                UsageTurn.JarvisPart.STT_STREAM, UsageTurn.JarvisPart.STT_UTTERANCE, UsageTurn.JarvisPart.DICTATION);
        UsageTurn voice = rows.stream().filter(r -> r.getJarvisPart() == UsageTurn.JarvisPart.VOICE).findFirst()
                .orElseThrow();
        // Le tarif réel du fournisseur (ElevenLabs ici), et non le tarif OpenAI d'avant F-244.
        assertThat(voice.getProviderCostUsd()).isEqualByComparingTo("0.30");
        assertThat(voice.getModel()).isEqualTo("elevenlabs");

        JarvisUsageDay day = usage.lastDays(alice.getId(), 1).get(0);
        assertThat(day.getSttStreamSeconds()).isEqualTo(60L);
        assertThat(day.getSttUtteranceSeconds()).isEqualTo(2L);
        assertThat(day.getDictationSeconds()).isEqualTo(3L);
        assertThat(day.voiceCostUsd()).isEqualByComparingTo("0.31045");
        assertThat(day.totalCostUsd()).isEqualByComparingTo("0.31045");
        // L'assiette du plafond voix couvre désormais retranscription et dictée.
        assertThat(usage.monthSpeechCostUsd(alice.getId())).isEqualByComparingTo("0.31045");

        assertThat(rowsOf(bob.getId())).isEmpty();
        assertThat(usage.lastDays(bob.getId(), 1)).isEmpty();
    }

    @Test
    @DisplayName("cerveau rapide et tour Opus délégué : JARVIS / FAST et JARVIS / OPUS ; un tour tapé reste GATEWAY")
    void fastBrainAndOpusAreLabelledAndTypedTurnsStayGateway() {
        ledger.recordAnnex(alice.getId(), null, "claude-haiku-4-5", 1_000L, 100L, 0L, 0L,
                UsageTag.jarvis(UsageTurn.JarvisPart.FAST));
        quota.recordUsage(alice.getId(), new TurnTokens(1_000L, 200L, 0L, 0L), TurnExtras.NONE, null,
                "claude-opus-5", null, null, null, null, UsageTag.jarvis(UsageTurn.JarvisPart.OPUS));
        quota.recordUsage(alice.getId(), new TurnTokens(500L, 50L, 0L, 0L), TurnExtras.NONE, null,
                "claude-opus-5", null, null);

        List<UsageTurn> rows = rowsOf(alice.getId());
        assertThat(rows).hasSize(3);
        assertThat(rows).filteredOn(r -> r.getJarvisPart() == UsageTurn.JarvisPart.FAST).singleElement()
                .matches(r -> r.getOrigin() == UsageTurn.Origin.JARVIS && r.getKind() == UsageTurn.Kind.ANNEXE);
        assertThat(rows).filteredOn(r -> r.getJarvisPart() == UsageTurn.JarvisPart.OPUS).singleElement()
                .matches(r -> r.getOrigin() == UsageTurn.Origin.JARVIS && r.getKind() == UsageTurn.Kind.TOUR
                        && r.getProviderCostUsd() != null && r.getProviderCostUsd().signum() > 0);
        assertThat(rows).filteredOn(r -> r.getOrigin() == UsageTurn.Origin.GATEWAY).singleElement()
                .matches(r -> r.getJarvisPart() == null && r.getKind() == UsageTurn.Kind.TOUR);
        assertThat(rowsOf(bob.getId())).isEmpty();
    }

    @Test
    @DisplayName("dictée de repli : comptée au budget de Jarvis quand elle vient de /jarvis, pas ailleurs")
    void theFallbackDictationCountsOnlyFromJarvis() throws Exception {
        String token = jwtService.generateToken(alice);
        mockMvc.perform(multipart("/api/atelier/transcription")
                .file(new MockMultipartFile("audio", "dictee.webm", "audio/webm", new byte[8_000]))
                .contextPath("/api").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        assertThat(rowsOf(alice.getId())).isEmpty();

        mockMvc.perform(multipart("/api/atelier/transcription")
                .file(new MockMultipartFile("audio", "dictee.webm", "audio/webm", new byte[8_000]))
                .param("via", "JARVIS").contextPath("/api").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(rowsOf(alice.getId())).singleElement()
                .matches(r -> r.getJarvisPart() == UsageTurn.JarvisPart.DICTATION
                        && r.getOrigin() == UsageTurn.Origin.JARVIS);
        // 8 000 octets ≈ 2 s ; 0,006 $/min ⇒ 0,0002 $.
        assertThat(usage.lastDays(alice.getId(), 1).get(0).getDictationSeconds()).isEqualTo(2L);
        assertThat(usage.lastDays(alice.getId(), 1).get(0).getDictationCostUsd()).isEqualByComparingTo("0.0002");
        assertThat(rowsOf(bob.getId())).isEmpty();
    }
}
