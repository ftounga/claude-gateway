package fr.claudegateway.videos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Le ticket de lecture d'une vidéo (F-213 / SF-213-01). */
class VideoViewTicketServiceTest {

    private static final String SECRET = "un-secret-de-test-assez-long-pour-hmac-sha256";
    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    private static VideoViewTicketService at(Instant instant) {
        return new VideoViewTicketService(SECRET, VideoLimits.defaults(), Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    void roundTrip() {
        UUID user = UUID.randomUUID();
        UUID video = UUID.randomUUID();
        String ticket = at(NOW).issue(user, video, 3);
        assertThat(ticket).startsWith("v1.");
        VideoViewTicketService.Ticket read = at(NOW.plusSeconds(60)).read(ticket).orElseThrow();
        assertThat(read.userId()).isEqualTo(user);
        assertThat(read.videoId()).isEqualTo(video);
        assertThat(read.version()).isEqualTo(3);
    }

    @Test
    void expiredForgedOrForeignIsEmpty() {
        String ticket = at(NOW).issue(UUID.randomUUID(), UUID.randomUUID(), 1);
        assertThat(at(NOW.plus(VideoLimits.DEFAULT_TICKET_TTL)).read(ticket)).isEmpty();
        assertThat(at(NOW).read(ticket.substring(0, ticket.length() - 2) + "AA")).isEmpty();
        assertThat(at(NOW).read("t1." + ticket.substring(3))).isEmpty();
        assertThat(at(NOW).read(null)).isEmpty();
        assertThat(at(NOW).read("v1.pas-un-ticket")).isEmpty();
        VideoViewTicketService other = new VideoViewTicketService("un-autre-secret-de-test-assez-long-pour-hmac",
                VideoLimits.defaults(), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(other.read(ticket)).isEmpty();
    }
}
