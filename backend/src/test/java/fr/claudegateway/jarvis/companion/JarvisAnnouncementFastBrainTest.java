package fr.claudegateway.jarvis.companion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.veille.JarvisWatchAlertRepository;

/** F-235 / SF-235-04 (D12) — le compagnon n'annonce pas les réponses rapides (déjà dites), seulement les fins de tour. */
class JarvisAnnouncementFastBrainTest {

    @Test
    void fastAnswersAreNotAnnounced() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-01T14:00:00Z"), ZoneOffset.UTC);
        UUID alice = UUID.randomUUID();
        OffsetDateTime at = OffsetDateTime.now(clock).minusMinutes(1);
        AtelierMessage fast = AtelierMessage.builder().id(UUID.randomUUID()).userId(alice).role("ASSISTANT")
                .content("Le chantier en est aux tests.").via(AtelierMessage.VIA_JARVIS)
                .brain(AtelierMessage.BRAIN_FAST).build();
        fast.setCreatedAt(at);
        AtelierMessage opus = AtelierMessage.builder().id(UUID.randomUUID()).userId(alice).role("ASSISTANT")
                .content("🎙 Deux tickets bloqués.\nDétail…").via(AtelierMessage.VIA_JARVIS).build();
        opus.setCreatedAt(at);
        AtelierMessageRepository messages = mock(AtelierMessageRepository.class);
        when(messages.findTop20ByUserIdAndViaAndRoleAndCreatedAtGreaterThanOrderByCreatedAtAsc(eq(alice),
                eq(AtelierMessage.VIA_JARVIS), eq("ASSISTANT"), any())).thenReturn(List.of(fast, opus));
        JarvisWatchAlertRepository alerts = mock(JarvisWatchAlertRepository.class);
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        when(workspaces.findAllById(any())).thenReturn(List.of());

        JarvisAnnouncementService.Feed feed = new JarvisAnnouncementService(messages, alerts, workspaces, clock)
                .since(alice, OffsetDateTime.now(clock).minusMinutes(5));

        assertThat(feed.items()).extracting(JarvisAnnouncementService.Announcement::text)
                .containsExactly("Deux tickets bloqués.");
    }
}
