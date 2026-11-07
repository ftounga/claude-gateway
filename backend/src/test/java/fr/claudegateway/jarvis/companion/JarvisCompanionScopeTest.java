package fr.claudegateway.jarvis.companion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** F-232 / SF-232-05 — la liste fermée de la portée {@code jarvis-compagnon}. */
class JarvisCompanionScopeTest {

    @ParameterizedTest(name = "{0} {1} ouvert")
    @CsvSource({
            "GET,/workspaces",
            "POST,/workspaces/4b0c0f7e-1111-2222-3333-444455556666/chat/stream",
            "POST,/workspaces/x/chat/confirm",
            "post,/jarvis/converse",
            "GET,/jarvis/mail/brief",
            "GET,/jarvis/mail/items/abc",
            "GET,/jarvis/calendar/events",
            "GET,/jarvis/clips/merci",
            "POST,/jarvis/stt-session/abc/close",
            "GET,/jarvis/pieces/p1/thumb",
            "GET,/workspaces/w/deposits/d/content",
            "GET,/runner/download/android/version",
            "GET,/jarvis/preferences/",
            "POST,/jarvis/listen-trace",
            "GET,/jarvis/face",
    })
    void opens(String method, String path) {
        assertThat(JarvisCompanionScope.allows(method, path)).isTrue();
    }

    @ParameterizedTest(name = "{0} {1} fermé")
    @CsvSource({
            "GET,/me",
            "PUT,/me",
            "POST,/me/logout-all",
            "GET,/admin/jarvis/captures",
            "POST,/workspaces",
            "DELETE,/workspaces/w",
            "GET,/workspaces/w/chat/stream",
            "POST,/workspaces/w/chat/steer",
            "POST,/jarvis/companions/pairing-code",
            "DELETE,/jarvis/companions/c",
            "DELETE,/jarvis/portrait",
            "PUT,/jarvis/portrait",
            "PUT,/jarvis/budget",
            "POST,/jarvis/mail/accounts",
            "DELETE,/jarvis/mail/accounts/a",
            "PATCH,/jarvis/mail/items/i",
            "DELETE,/jarvis/calendar/grants/g",
            "GET,/jarvis/calendar/oauth/start",
            "POST,/jarvis/calendar/feeds",
            "GET,/jarvis/mail/items/a/b",
            "GET,/jarvis/clips/../../me",
            "GET,/notifications",
            "GET,/pages/p/pdf",
    })
    void closes(String method, String path) {
        assertThat(JarvisCompanionScope.allows(method, path)).isFalse();
    }

    @Test
    @DisplayName("jamais de joker multi-segments dans la liste")
    void noDoubleWildcard() {
        assertThat(JarvisCompanionScope.ROUTES).noneMatch(r -> r.pattern().contains("**"));
    }

    @Test
    @DisplayName("entrées nulles ou vides : fermé")
    void nulls() {
        assertThat(JarvisCompanionScope.allows(null, "/workspaces")).isFalse();
        assertThat(JarvisCompanionScope.allows("GET", null)).isFalse();
        assertThat(JarvisCompanionScope.allows("GET", "")).isFalse();
    }
}
