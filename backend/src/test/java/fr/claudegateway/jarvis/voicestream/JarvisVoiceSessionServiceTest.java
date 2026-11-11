package fr.claudegateway.jarvis.voicestream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.UserService;

/** F-257 / SF-257-02 — le coupe-circuit et les bornes du ticket de session vocale. */
class JarvisVoiceSessionServiceTest {

    @Test
    @DisplayName("coupe-circuit fermé (défaut) : aucun ticket, aucune lecture")
    void disabledIssuesNothing() {
        JarvisVoiceStreamProperties off = new JarvisVoiceStreamProperties(null, null, null);
        JwtService jwt = mock(JwtService.class);
        UserService users = mock(UserService.class);
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        JarvisVoiceSessionService service = new JarvisVoiceSessionService(off, jwt, users, workspaces,
                Clock.systemUTC());
        assertThatThrownBy(() -> service.issue(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(JarvisVoiceSessionService.VoiceStreamDisabledException.class);
        verifyNoInteractions(jwt, users, workspaces);
    }

    @Test
    @DisplayName("valeurs par défaut et plafond de durée du ticket")
    void defaultsAndCap() {
        JarvisVoiceStreamProperties defaults = new JarvisVoiceStreamProperties(null, " ", Duration.ZERO);
        assertThat(defaults.isEnabled()).isFalse();
        assertThat(defaults.publicUrl()).isEqualTo("/voice/ws");
        assertThat(defaults.ticketTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(new JarvisVoiceStreamProperties(true, "wss://x/voice/ws", Duration.ofHours(9)).ticketTtl())
                .isEqualTo(JarvisVoiceStreamProperties.MAX_TTL);
    }

    @Test
    @DisplayName("un jeton qui n'est pas un ticket de session vocale n'identifie rien")
    void identifyRejectsOtherTokens() {
        JarvisVoiceSessionService service = new JarvisVoiceSessionService(
                new JarvisVoiceStreamProperties(true, null, null), mock(JwtService.class), mock(UserService.class),
                mock(WorkspaceRepository.class), Clock.systemUTC());
        assertThat(service.identify(null)).isNull();
    }
}
