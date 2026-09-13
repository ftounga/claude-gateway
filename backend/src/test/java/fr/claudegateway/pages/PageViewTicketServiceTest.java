package fr.claudegateway.pages;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Le ticket de lecture d'une page (F-109 / SF-109-01, D1 et D2). */
class PageViewTicketServiceTest {

    private static final String SECRET = "test-only-jwt-secret-please-override-in-every-real-environment-0123456789";
    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

    private final UUID userId = UUID.randomUUID();
    private final UUID pageId = UUID.randomUUID();

    private PageViewTicketService at(Instant instant) {
        return at(instant, SECRET);
    }

    private PageViewTicketService at(Instant instant, String secret) {
        return new PageViewTicketService(secret, PageLimits.defaults(), Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("un ticket émis se relit : compte, page, version")
    void roundTrip() {
        String token = at(NOW).issue(userId, pageId, 3);

        PageViewTicketService.Ticket ticket = at(NOW.plusSeconds(60)).read(token).orElseThrow();

        assertThat(ticket.userId()).isEqualTo(userId);
        assertThat(ticket.pageId()).isEqualTo(pageId);
        assertThat(ticket.version()).isEqualTo(3);
        assertThat(token).startsWith("t1.").doesNotContain("/").doesNotContain("+").doesNotContain("=");
    }

    @Test
    @DisplayName("un ticket expiré n'ouvre rien")
    void expired() {
        String token = at(NOW).issue(userId, pageId, 0);

        assertThat(at(NOW.plus(Duration.ofMinutes(10))).read(token)).isEmpty();
        assertThat(at(NOW.plus(Duration.ofMinutes(9))).read(token)).isPresent();
    }

    @Test
    @DisplayName("une charge modifiée ou un sceau altéré n'ouvrent rien")
    void tampered() {
        String token = at(NOW).issue(userId, pageId, 1);
        String otherPage = at(NOW).issue(userId, UUID.randomUUID(), 1);
        String payload = token.substring(0, token.lastIndexOf('.'));
        String seal = otherPage.substring(otherPage.lastIndexOf('.'));

        assertThat(at(NOW).read(payload + seal)).isEmpty();
        assertThat(at(NOW).read(token.substring(0, token.length() - 2) + "AA")).isEmpty();
    }

    @Test
    @DisplayName("un format tronqué, vide ou étranger n'ouvre rien — sans exception")
    void malformed() {
        PageViewTicketService service = at(NOW);

        assertThat(service.read(null)).isEmpty();
        assertThat(service.read("")).isEmpty();
        assertThat(service.read("t1.")).isEmpty();
        assertThat(service.read("t1.abc")).isEmpty();
        assertThat(service.read("t1.abc.def.ghi")).isEmpty();
        assertThat(service.read("t1.!!!.???")).isEmpty();
        assertThat(service.read("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.sig")).isEmpty();
    }

    @Test
    @DisplayName("un ticket signé d'une autre clé n'ouvre rien")
    void otherKey() {
        String token = at(NOW, SECRET.replace('0', '9')).issue(userId, pageId, 1);

        assertThat(at(NOW).read(token)).isEmpty();
    }
}
