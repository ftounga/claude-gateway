package fr.claudegateway.jarvis.companion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.claudegateway.auth.SecureTokenGenerator;
import fr.claudegateway.runner.RunnerPairingCodeGenerator;
import fr.claudegateway.runner.TokenHasher;

/** F-232 / SF-232-03 — la fenêtre des annonces, l'échéance glissante du jeton, la limite de compagnons. */
class JarvisCompanionServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-01T10:00:00Z");
    private final Clock clock = Clock.fixed(Instant.from(NOW), ZoneOffset.UTC);
    private final JarvisCompanionRepository repository = mock(JarvisCompanionRepository.class);
    private final TokenHasher hasher = new TokenHasher();
    private final JarvisCompanionService service = new JarvisCompanionService(repository, hasher,
            new RunnerPairingCodeGenerator(), new SecureTokenGenerator(), clock, Duration.ofMinutes(5),
            Duration.ofDays(60));

    @Test
    void windowOverlapsTheCursorByTwoMinutes() {
        assertThat(JarvisAnnouncementService.windowStart(NOW.minusSeconds(20), NOW))
                .isEqualTo(NOW.minusSeconds(20).minusMinutes(2));
    }

    @Test
    void windowNeverReachesFurtherThanTenMinutes() {
        assertThat(JarvisAnnouncementService.windowStart(NOW.minusHours(5), NOW)).isEqualTo(NOW.minusMinutes(10));
    }

    @Test
    void windowNeverStartsInTheFuture() {
        assertThat(JarvisAnnouncementService.windowStart(NOW.plusHours(1), NOW)).isEqualTo(NOW);
    }

    @Test
    void slidingExpiry() {
        JarvisCompanion fresh = paired("fresh", NOW.minusDays(59));
        JarvisCompanion stale = paired("stale", NOW.minusDays(61));
        when(repository.findByTokenHash(hasher.sha256Hex("fresh"))).thenReturn(Optional.of(fresh));
        when(repository.findByTokenHash(hasher.sha256Hex("stale"))).thenReturn(Optional.of(stale));

        assertThat(service.authenticate("fresh")).contains(fresh);
        assertThat(fresh.getLastSeenAt()).isEqualTo(NOW); // repoussée
        assertThat(service.authenticate("stale")).isEmpty();
        assertThat(service.authenticate(" ")).isEmpty();
    }

    @Test
    void revokedTokenIsRefused() {
        JarvisCompanion revoked = paired("revoked", NOW.minusMinutes(1));
        revoked.setRevokedAt(NOW.minusSeconds(1));
        when(repository.findByTokenHash(hasher.sha256Hex("revoked"))).thenReturn(Optional.of(revoked));
        assertThat(service.authenticate("revoked")).isEmpty();
    }

    @Test
    void atMostFiveActiveCompanions() {
        UUID userId = UUID.randomUUID();
        when(repository.findByUserIdAndPairedAtIsNotNullAndRevokedAtIsNullOrderByPairedAtDesc(userId))
                .thenReturn(Collections.nCopies(5, paired("x", NOW)));
        assertThatThrownBy(() -> service.createPairingCode(userId))
                .isInstanceOf(JarvisCompanionService.CompanionLimitException.class);
    }

    @Test
    void pairingCodeIsStoredHashedOnly() {
        UUID userId = UUID.randomUUID();
        when(repository.findByUserIdAndPairedAtIsNotNullAndRevokedAtIsNullOrderByPairedAtDesc(userId))
                .thenReturn(List.of());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        JarvisCompanionService.PairingCode code = service.createPairingCode(userId);
        assertThat(code.code()).hasSize(8);
        assertThat(code.expiresAt()).isEqualTo(NOW.plusMinutes(5));
    }

    @Test
    void labelIsBounded() {
        assertThat(JarvisCompanionService.normalizeLabel(null)).isEqualTo("Téléphone Android");
        assertThat(JarvisCompanionService.normalizeLabel("x".repeat(200))).hasSize(80);
    }

    private static JarvisCompanion paired(String token, OffsetDateTime lastSeen) {
        return JarvisCompanion.builder().id(UUID.randomUUID()).userId(UUID.randomUUID()).label("t")
                .tokenHash(new TokenHasher().sha256Hex(token)).pairedAt(lastSeen).lastSeenAt(lastSeen).build();
    }
}
