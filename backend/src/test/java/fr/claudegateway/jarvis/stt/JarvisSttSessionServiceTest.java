package fr.claudegateway.jarvis.stt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.jarvis.JarvisVoiceBudgetException;
import fr.claudegateway.jarvis.JarvisVoiceService;
import fr.claudegateway.stt.stream.StreamingTranscriptionProvider;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderUnavailableException;

/** Les sessions d'écoute en flux (F-233 / SF-233-04) : émission, plafond, durée comptée bornée, balayage. */
class JarvisSttSessionServiceTest {

    /** Une horloge qu'on avance à la main. */
    static final class MovingClock extends Clock {
        Instant now = Instant.parse("2026-11-01T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private MovingClock clock;
    private StreamingTranscriptionProvider provider;
    private JarvisVoiceService voice;
    private JarvisUsageService usage;
    private JarvisSttSessionService service;

    @BeforeEach
    void setUp() {
        clock = new MovingClock();
        provider = mock(StreamingTranscriptionProvider.class);
        when(provider.name()).thenReturn("elevenlabs");
        when(provider.isConfigured()).thenReturn(true);
        when(provider.openStreamingSession(any(), anyDouble(), any())).thenReturn(new StreamingTranscriptionProvider
                .StreamingSession("elevenlabs", "wss://x/realtime?model_id=m", "tok", clock.now.plusSeconds(900), 16000));
        voice = mock(JarvisVoiceService.class);
        when(voice.status(any())).thenReturn(new JarvisVoiceService.VoiceStatus(true, "onyx", BigDecimal.TEN,
                BigDecimal.ZERO, false, "openai"));
        usage = mock(JarvisUsageService.class);
        service = service(new JarvisSttStreamProperties(null, null, null, null, 120, new BigDecimal("0.006")));
    }

    private JarvisSttSessionService service(JarvisSttStreamProperties properties) {
        return new JarvisSttSessionService(List.of(provider), properties, voice, usage, clock);
    }

    @Test
    @DisplayName("émet un ticket sans clé ; la clôture compte la durée déclarée bornée par l'écoulé")
    void openAndClose() {
        JarvisSttSessionService.Ticket ticket = service.open(alice);
        assertThat(ticket.url()).doesNotContain("tok");
        assertThat(ticket.token()).isEqualTo("tok");
        assertThat(ticket.maxSeconds()).isEqualTo(120);
        verify(provider).openStreamingSession(eq("fr"), eq(1.2), any());

        clock.now = clock.now.plusSeconds(20);
        service.close(alice, ticket.sessionId(), 999L);
        // 20 s écoulées + 5 s de marge : 25 s, à 0,006 $/min = 0,0025 $.
        verify(usage).addStream(alice, 25L, new BigDecimal("0.002500"));
        assertThat(service.openCount()).isZero();
    }

    @Test
    @DisplayName("F-257 / SF-257-04 — session longue : commit manuel, comptée jusqu'à 30 min, balayée à son maximum")
    void longSession() {
        when(provider.openManualCommitSession(any(), any())).thenReturn(new StreamingTranscriptionProvider
                .StreamingSession("elevenlabs", "wss://x/realtime?commit_strategy=manual", "tok2",
                        clock.now.plusSeconds(900), 16000));
        JarvisSttSessionService.Ticket ticket = service.openLong(alice);
        assertThat(ticket.maxSeconds()).isEqualTo(JarvisSttSessionService.LONG_MAX_SECONDS);
        verify(provider).openManualCommitSession(eq("fr"), any());

        clock.now = clock.now.plusSeconds(600);
        service.close(alice, ticket.sessionId(), 600L);
        // 600 s : au-delà du maximum d'une session courte (120 s), compté en entier.
        verify(usage).addStream(alice, 600L, new BigDecimal("0.060000"));

        service.openLong(alice);
        clock.now = clock.now.plusSeconds(200);
        service.sweep();
        assertThat(service.openCount()).isEqualTo(1);
        clock.now = clock.now.plusSeconds(JarvisSttSessionService.LONG_MAX_SECONDS);
        service.sweep();
        assertThat(service.openCount()).isZero();
        verify(usage).addStream(eq(alice), eq((long) JarvisSttSessionService.LONG_MAX_SECONDS), any());

        when(voice.status(bob)).thenReturn(new JarvisVoiceService.VoiceStatus(true, "onyx", BigDecimal.TEN,
                BigDecimal.TEN, true, "openai"));
        assertThatThrownBy(() -> service.openLong(bob)).isInstanceOf(JarvisVoiceBudgetException.class);
    }

    @Test
    @DisplayName("la session d'autrui, ou déjà close, est introuvable")
    void closeIsolation() {
        JarvisSttSessionService.Ticket ticket = service.open(alice);
        assertThatThrownBy(() -> service.close(bob, ticket.sessionId(), 3L)).isInstanceOf(NoSuchElementException.class);
        service.close(alice, ticket.sessionId(), 3L);
        verify(usage).addStream(eq(alice), eq(3L), any());
        assertThatThrownBy(() -> service.close(alice, ticket.sessionId(), 3L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("plafond atteint : refus avant tout appel ; flux éteint ou non configuré : 503 sans appel")
    void refusals() {
        when(voice.status(alice)).thenReturn(new JarvisVoiceService.VoiceStatus(true, "onyx", BigDecimal.TEN,
                BigDecimal.TEN, true, "openai"));
        assertThatThrownBy(() -> service.open(alice)).isInstanceOf(JarvisVoiceBudgetException.class);
        assertThatThrownBy(() -> service(new JarvisSttStreamProperties("none", null, null, null, null, null))
                .open(bob)).isInstanceOf(TranscriptionProviderUnavailableException.class);
        when(provider.isConfigured()).thenReturn(false);
        assertThatThrownBy(() -> service.open(bob)).isInstanceOf(TranscriptionProviderUnavailableException.class);
        verify(provider, never()).openStreamingSession(any(), anyDouble(), any());
    }

    @Test
    @DisplayName("une session jamais close est comptée au maximum par le balayage, une seule fois")
    void sweep() {
        service.open(alice);
        clock.now = clock.now.plusSeconds(150);
        service.sweep();
        verify(usage, never()).addStream(any(), anyLong(), any());
        clock.now = clock.now.plusSeconds(60);
        service.sweep();
        service.sweep();
        verify(usage).addStream(eq(alice), eq(120L), any());
        assertThat(service.openCount()).isZero();
    }

    @Test
    @DisplayName("durée comptée : jamais négative, jamais au-delà de l'écoulé ni du maximum")
    void billed() {
        assertThat(JarvisSttSessionService.billedSeconds(null, 30, 120)).isZero();
        assertThat(JarvisSttSessionService.billedSeconds(-4L, 30, 120)).isZero();
        assertThat(JarvisSttSessionService.billedSeconds(12L, 30, 120)).isEqualTo(12);
        assertThat(JarvisSttSessionService.billedSeconds(500L, 300, 120)).isEqualTo(120);
    }
}
