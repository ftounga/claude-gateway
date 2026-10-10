package fr.claudegateway.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import fr.claudegateway.atelier.actions.TerminalAction;
import fr.claudegateway.atelier.actions.TerminalActionFollowUp;
import fr.claudegateway.atelier.actions.TerminalActionKind;
import fr.claudegateway.atelier.actions.TerminalActionRepository;
import fr.claudegateway.atelier.actions.TerminalActionStatus;
import fr.claudegateway.push.PushEvent;
import fr.claudegateway.push.PushNotificationService;

/**
 * Le récapitulatif quotidien des attentes (F-185 / SF-185-07), sur la base de test : qui est
 * prévenu, une seule fois par jour, et jamais pour le compte d'un autre.
 */
@SpringBootTest
@ActiveProfiles("test")
class AttentesDigestIntegrationTest {

    @Autowired private TerminalActionRepository actions;
    @Autowired private TerminalActionFollowUp followUp;
    @Autowired private NotificationDigestWriter digests;
    @Autowired private JdbcTemplate jdbcTemplate;

    private PushNotificationService push;
    private final UUID late = UUID.randomUUID();
    private final UUID fresh = UUID.randomUUID();

    /** Vendredi 2026-10-09, 9 h à Paris. */
    private static final Instant FRIDAY = Instant.parse("2026-10-09T07:00:00Z");

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from notification_digests");
        push = mock(PushNotificationService.class);
        // Demandée le lundi 05/10 : 4 jours ouvrés au vendredi → relance due.
        actions.save(action(late, OffsetDateTime.parse("2026-10-05T10:00:00Z")));
        // Demandée la veille : pas encore due.
        actions.save(action(fresh, OffsetDateTime.parse("2026-10-08T10:00:00Z")));
    }

    private static TerminalAction action(UUID userId, OffsetDateTime requestedAt) {
        return TerminalAction.builder().userId(userId).workspaceId(UUID.randomUUID())
                .description("Relancer l'accès VPN").kind(TerminalActionKind.ACTION)
                .status(TerminalActionStatus.DEMANDE).requestedAt(requestedAt)
                .createdAt(requestedAt).updatedAt(requestedAt).build();
    }

    private AttentesDigestService at(Instant now) {
        return new AttentesDigestService(actions, followUp, digests, push, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void onlyTheAccountWithADueFollowUpIsToldOncePerDay() {
        // Les comptes se vérifient un à un : la base de test est partagée avec d'autres classes.
        at(FRIDAY).run();
        verify(push).notify(eq(late), isNull(), eq(PushEvent.ATTENTES_TO_FOLLOW_UP));
        verify(push, never()).notify(eq(fresh), any(), any());

        // Second passage le même jour (ou second pod) : rien de plus.
        at(FRIDAY.plusSeconds(60)).run();
        verify(push, times(1)).notify(eq(late), isNull(), eq(PushEvent.ATTENTES_TO_FOLLOW_UP));
    }

    @Test
    void theNextDayAStillDueFollowUpIsToldAgain() {
        at(FRIDAY).run();
        // Mardi 13/10 : l'attente de « late » est toujours due, et celle de « fresh » (jeudi 08/10)
        // l'est devenue — vendredi, lundi, mardi : trois jours ouvrés.
        at(Instant.parse("2026-10-13T07:00:00Z")).run();
        verify(push, times(2)).notify(eq(late), isNull(), eq(PushEvent.ATTENTES_TO_FOLLOW_UP));
        verify(push, times(1)).notify(eq(fresh), isNull(), eq(PushEvent.ATTENTES_TO_FOLLOW_UP));
    }

    @Test
    void theClaimIsTheLockAndOldLinesArePurged() {
        LocalDate day = LocalDate.parse("2026-10-09");
        OffsetDateTime now = OffsetDateTime.now();
        assertThat(digests.claim(late, day, now)).isTrue();
        assertThat(digests.claim(late, day, now)).isFalse();
        assertThat(digests.claim(fresh, day, now)).isTrue();

        digests.claim(late, day.minusDays(40), now);
        at(FRIDAY).run();
        Integer old = jdbcTemplate.queryForObject(
                "select count(*) from notification_digests where digest_day < ?", Integer.class, day.minusDays(30));
        assertThat(old).isZero();
    }

    @Test
    void theEventIsNeutralNotCriticalAndAvailableToPreferences() {
        assertThat(PushEvent.ATTENTES_TO_FOLLOW_UP.critical()).isFalse();
        assertThat(PushEvent.ATTENTES_TO_FOLLOW_UP.alwaysDelivered()).isFalse();
        assertThat(PushEvent.ATTENTES_TO_FOLLOW_UP.title()).isEqualTo("Des attentes sont à relancer");
        assertThat(PushEvent.ATTENTES_TO_FOLLOW_UP.body()).doesNotContainPattern("\\d");
    }
}
