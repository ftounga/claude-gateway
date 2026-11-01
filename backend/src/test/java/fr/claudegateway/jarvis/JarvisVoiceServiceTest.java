package fr.claudegateway.jarvis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.speech.SpeechProperties;
import fr.claudegateway.speech.SpeechProvider;
import fr.claudegateway.speech.SpeechProviderUnavailableException;
import fr.claudegateway.speech.SpeechUsage;

/** La voix de Jarvis (F-231 / SF-231-01) : coupe à la phrase, plafond en euros, comptage, rattachement. */
class JarvisVoiceServiceTest {

    private final UUID alice = UUID.randomUUID();
    private SpeechProvider provider;
    private SpeechUsage ledger;
    private JarvisUsageService usage;
    private WorkspaceRepository workspaces;

    @BeforeEach
    void setUp() {
        provider = mock(SpeechProvider.class);
        ledger = mock(SpeechUsage.class);
        usage = mock(JarvisUsageService.class);
        workspaces = mock(WorkspaceRepository.class);
        when(usage.monthSpeechCostUsd(any())).thenReturn(BigDecimal.ZERO);
    }

    private JarvisVoiceService service(String key, BigDecimal budgetEur) {
        SpeechProperties speech = new SpeechProperties(true, "http://localhost", key, null, Duration.ofSeconds(5),
                null, null, null, null, null, null, null);
        return new JarvisVoiceService(provider, speech, new JarvisVoiceProperties(null, null, null, null, budgetEur),
                ledger, usage, workspaces);
    }

    @Test
    @DisplayName("coupe à la dernière fin de phrase avant la borne ; sinon au dernier mot, avec une ellipse")
    void cutsAtSentenceEnd() {
        assertThat(JarvisVoiceService.cut("  Court.  ", 600)).isEqualTo("Court.");
        assertThat(JarvisVoiceService.cut("Première phrase ici. Deuxième phrase un peu longue.", 30))
                .isEqualTo("Première phrase ici.");
        assertThat(JarvisVoiceService.cut("un texte sans aucune ponctuation qui déborde", 20))
                .isEqualTo("un texte sans aucune…");
    }

    @Test
    @DisplayName("défauts D2 : voix onyx, ton posé, 600 caractères, 15 € par mois")
    void defaults() {
        JarvisVoiceProperties props = new JarvisVoiceProperties(null, null, null, null, null);
        assertThat(props.voice()).isEqualTo("onyx");
        assertThat(props.instructions()).contains("posée");
        assertThat(props.maxChars()).isEqualTo(600);
        assertThat(props.monthlyBudgetEur()).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("lecture : flux du fournisseur avec onyx, puis journal d'usage et journée Jarvis comptés")
    void speaksAndCounts() throws Exception {
        UUID ws = UUID.randomUUID();
        UUID host = UUID.randomUUID();
        when(workspaces.findByIdAndUserId(ws, alice))
                .thenReturn(Optional.of(Workspace.builder().id(ws).userId(alice).hostId(host).name("x").build()));
        when(provider.synthesizeStream(anyString(), eq("onyx"), anyString(), any())).thenReturn(3L);
        JarvisVoiceService service = service("sk", null);

        JarvisVoiceService.Reading reading = service.prepare(alice, ws, "C'est fait.");
        service.speak(reading, new ByteArrayOutputStream());

        verify(provider).synthesizeStream(eq("C'est fait."), eq("onyx"), eq(JarvisVoiceProperties.DEFAULT_INSTRUCTIONS),
                any());
        verify(ledger).record(alice, ws, host, 11L, 0d);
        verify(usage).addSpeech(eq(alice), eq(11L), eq(new BigDecimal("0.000165")));
    }

    @Test
    @DisplayName("le sujet d'autrui est ignoré pour le rattachement, sans erreur")
    void foreignWorkspaceIgnored() {
        UUID foreign = UUID.randomUUID();
        when(workspaces.findByIdAndUserId(foreign, alice)).thenReturn(Optional.empty());
        JarvisVoiceService.Reading reading = service("sk", null).prepare(alice, foreign, "Bonjour.");
        assertThat(reading.workspaceId()).isNull();
        assertThat(reading.hostId()).isNull();
    }

    @Test
    @DisplayName("plafond atteint (en euros) : refus avant tout appel ; budget 0 = voix coupée")
    void budgetReached() throws Exception {
        when(usage.monthSpeechCostUsd(alice)).thenReturn(new BigDecimal("20"));
        assertThatThrownBy(() -> service("sk", new BigDecimal("15")).prepare(alice, null, "Bonjour."))
                .isInstanceOf(JarvisVoiceBudgetException.class);
        when(usage.monthSpeechCostUsd(alice)).thenReturn(BigDecimal.ZERO);
        assertThatThrownBy(() -> service("sk", BigDecimal.ZERO).prepare(alice, null, "Bonjour."))
                .isInstanceOf(JarvisVoiceBudgetException.class);
        verify(provider, never()).synthesizeStream(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("état : dépense du mois convertie en euros (taux 0,92 par défaut)")
    void statusInEuros() {
        when(usage.monthSpeechCostUsd(alice)).thenReturn(new BigDecimal("1.00"));
        JarvisVoiceService.VoiceStatus status = service("sk", null).status(alice);
        assertThat(status.enabled()).isTrue();
        assertThat(status.voice()).isEqualTo("onyx");
        assertThat(status.monthSpentEur()).isEqualByComparingTo("0.92");
        assertThat(status.monthlyBudgetEur()).isEqualByComparingTo("15.00");
        assertThat(status.budgetReached()).isFalse();
    }

    @Test
    @DisplayName("sans clé : indisponible ; texte vide ou trop long : refus")
    void validation() {
        assertThatThrownBy(() -> service(null, null).prepare(alice, null, "Bonjour."))
                .isInstanceOf(SpeechProviderUnavailableException.class);
        assertThatThrownBy(() -> service("sk", null).prepare(alice, null, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service("sk", null).prepare(alice, null, "x".repeat(4_001)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
