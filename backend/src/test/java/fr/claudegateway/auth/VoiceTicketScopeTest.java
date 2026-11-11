package fr.claudegateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-257 / SF-257-02 — la liste fermée de la portée {@code jarvis-voice}. */
class VoiceTicketScopeTest {

    @Test
    @DisplayName("les huit routes de la boucle vocale sont ouvertes")
    void opensTheVoiceRoutes() {
        assertThat(VoiceTicketScope.allows("GET", "/jarvis/voice-session/me")).isTrue();
        assertThat(VoiceTicketScope.allows("POST", "/jarvis/converse")).isTrue();
        assertThat(VoiceTicketScope.allows("post", "/jarvis/converse/verdict")).isTrue();
        assertThat(VoiceTicketScope.allows("POST", "/jarvis/speech")).isTrue();
        assertThat(VoiceTicketScope.allows("POST", "/jarvis/stt-session")).isTrue();
        assertThat(VoiceTicketScope.allows("POST", "/jarvis/stt-session/123/close")).isTrue();
        assertThat(VoiceTicketScope.allows("POST", "/jarvis/timings")).isTrue();
        assertThat(VoiceTicketScope.allows("POST", "/jarvis/listen-trace/")).isTrue();
        assertThat(VoiceTicketScope.ROUTES).hasSize(8);
    }

    @Test
    @DisplayName("tout le reste est fermé : autre méthode, autre route, segments en plus, émission d'un ticket")
    void closesEverythingElse() {
        assertThat(VoiceTicketScope.allows("GET", "/jarvis/converse")).isFalse();
        assertThat(VoiceTicketScope.allows("POST", "/jarvis/voice-session")).isFalse();
        assertThat(VoiceTicketScope.allows("POST", "/jarvis/stt-session/1/2/close")).isFalse();
        assertThat(VoiceTicketScope.allows("GET", "/workspaces")).isFalse();
        assertThat(VoiceTicketScope.allows("GET", "/admin/jarvis/latency")).isFalse();
        assertThat(VoiceTicketScope.allows(null, "/jarvis/converse")).isFalse();
        assertThat(VoiceTicketScope.allows("POST", null)).isFalse();
    }
}
