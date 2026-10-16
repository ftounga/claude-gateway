package fr.claudegateway.notifications;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import fr.claudegateway.atelier.actions.AccesObtenu;
import fr.claudegateway.atelier.actions.AccesObtenuRepository;
import fr.claudegateway.atelier.actions.TerminalAction;
import fr.claudegateway.atelier.actions.TerminalActionFollowUp;
import fr.claudegateway.atelier.actions.TerminalActionKind;
import fr.claudegateway.atelier.actions.TerminalActionRepository;
import fr.claudegateway.atelier.actions.TerminalActionStatus;
import fr.claudegateway.push.PushEvent;
import fr.claudegateway.push.PushNotificationService;

/**
 * Le point du matin dans le récapitulatif de 9 h (F-180 / SF-180-06) : une échéance atteinte ou un accès qui
 * expire prévient le compte concerné — une fois par jour, jamais un autre compte.
 */
@SpringBootTest
@ActiveProfiles("test")
class AttentesMorningDigestIntegrationTest {

    @Autowired private TerminalActionRepository actions;
    @Autowired private AccesObtenuRepository acces;
    @Autowired private TerminalActionFollowUp followUp;
    @Autowired private NotificationDigestWriter digests;
    @Autowired private JdbcTemplate jdbcTemplate;

    private PushNotificationService push;
    private final UUID withDue = UUID.randomUUID();
    private final UUID withExpiring = UUID.randomUUID();
    private final UUID quiet = UUID.randomUUID();

    /** Vendredi 2026-10-16, 9 h à Paris. */
    private static final Instant NOW = Instant.parse("2026-10-16T07:00:00Z");

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from notification_digests");
        push = mock(PushNotificationService.class);
        OffsetDateTime at = OffsetDateTime.parse("2026-10-14T10:00:00Z");
        actions.save(TerminalAction.builder().userId(withDue).workspaceId(UUID.randomUUID())
                .description("Valider le budget").kind(TerminalActionKind.ACTION).status(TerminalActionStatus.A_FAIRE)
                .dueAt(LocalDate.parse("2026-10-15")).createdAt(at).updatedAt(at).build());
        actions.save(TerminalAction.builder().userId(quiet).workspaceId(UUID.randomUUID())
                .description("Plus tard").kind(TerminalActionKind.ACTION).status(TerminalActionStatus.A_FAIRE)
                .dueAt(LocalDate.parse("2026-11-30")).createdAt(at).updatedAt(at).build());
        acces.save(AccesObtenu.builder().userId(withExpiring).workspaceId(UUID.randomUUID()).system("VPN")
                .normalizedSystem("vpn").obtainedAt(LocalDate.parse("2026-01-01"))
                .expiresAt(LocalDate.parse("2026-10-20")).createdAt(at).updatedAt(at).build());
    }

    private AttentesDigestService at(Instant now) {
        AttentesDigestService service = new AttentesDigestService(actions, followUp, digests, push,
                Clock.fixed(now, ZoneOffset.UTC));
        service.setAcces(acces);
        return service;
    }

    @Test
    @DisplayName("échéance atteinte, accès qui expire : point du matin, une fois par jour, au seul compte concerné")
    void morningPoint() {
        at(NOW).run();
        verify(push).notify(eq(withDue), isNull(), eq(PushEvent.ATTENTES_POINT_DU_MATIN));
        verify(push).notify(eq(withExpiring), isNull(), eq(PushEvent.ATTENTES_POINT_DU_MATIN));
        verify(push, never()).notify(eq(quiet), any(), any());

        at(NOW.plusSeconds(120)).run();
        verify(push, times(1)).notify(eq(withDue), isNull(), eq(PushEvent.ATTENTES_POINT_DU_MATIN));
    }
}
