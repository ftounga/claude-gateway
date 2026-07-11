package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.auth.AuthenticatedUser;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionService;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.user.UserRole;

/**
 * Tests unitaires du gating de l'Atelier (F-28 / SF-28-06) : accès réservé aux administrateurs
 * (bypass) et aux abonnés Gold actifs ({@code ACTIVE}/{@code PAST_DUE}), fail-closed sinon.
 */
@ExtendWith(MockitoExtension.class)
class AtelierAccessServiceTest {

    @Mock private CurrentUser currentUser;
    @Mock private SubscriptionService subscriptionService;

    private AtelierAccessService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AtelierAccessService(currentUser, subscriptionService);
    }

    private AuthenticatedUser principal(UserRole role) {
        return new AuthenticatedUser(userId, "u@example.com", role);
    }

    private Subscription subscription(PlanCode plan, SubscriptionStatus status) {
        return Subscription.builder().userId(userId).planCode(plan).status(status).build();
    }

    @Test
    void adminBypassesGatingWithoutConsultingSubscription() {
        when(currentUser.principal()).thenReturn(Optional.of(principal(UserRole.ADMIN)));

        assertThat(service.hasAccess()).isTrue();
        assertThatCode(service::requireAccess).doesNotThrowAnyException();
        verify(subscriptionService, never()).getOrCreateForUser(any());
    }

    @Test
    void goldActiveIsAllowed() {
        when(currentUser.principal()).thenReturn(Optional.of(principal(UserRole.USER)));
        when(subscriptionService.getOrCreateForUser(userId))
                .thenReturn(subscription(PlanCode.GOLD, SubscriptionStatus.ACTIVE));

        assertThat(service.hasAccess()).isTrue();
        assertThatCode(service::requireAccess).doesNotThrowAnyException();
    }

    @Test
    void goldPastDueIsAllowedAsGrace() {
        when(currentUser.principal()).thenReturn(Optional.of(principal(UserRole.USER)));
        when(subscriptionService.getOrCreateForUser(userId))
                .thenReturn(subscription(PlanCode.GOLD, SubscriptionStatus.PAST_DUE));

        assertThat(service.hasAccess()).isTrue();
    }

    @Test
    void proActiveIsDenied() {
        when(currentUser.principal()).thenReturn(Optional.of(principal(UserRole.USER)));
        when(subscriptionService.getOrCreateForUser(userId))
                .thenReturn(subscription(PlanCode.PRO, SubscriptionStatus.ACTIVE));

        assertThat(service.hasAccess()).isFalse();
        assertThatThrownBy(service::requireAccess).isInstanceOf(AtelierAccessDeniedException.class);
    }

    @Test
    void trialingIsDenied() {
        when(currentUser.principal()).thenReturn(Optional.of(principal(UserRole.USER)));
        when(subscriptionService.getOrCreateForUser(userId))
                .thenReturn(subscription(null, SubscriptionStatus.TRIALING));

        assertThat(service.hasAccess()).isFalse();
        assertThatThrownBy(service::requireAccess).isInstanceOf(AtelierAccessDeniedException.class);
    }

    @Test
    void goldCanceledIsDenied() {
        when(currentUser.principal()).thenReturn(Optional.of(principal(UserRole.USER)));
        when(subscriptionService.getOrCreateForUser(userId))
                .thenReturn(subscription(PlanCode.GOLD, SubscriptionStatus.CANCELED));

        assertThat(service.hasAccess()).isFalse();
        assertThatThrownBy(service::requireAccess).isInstanceOf(AtelierAccessDeniedException.class);
    }

    @Test
    void anonymousIsDenied() {
        when(currentUser.principal()).thenReturn(Optional.empty());

        assertThat(service.hasAccess()).isFalse();
        assertThatThrownBy(service::requireAccess).isInstanceOf(AtelierAccessDeniedException.class);
        verify(subscriptionService, never()).getOrCreateForUser(any());
    }
}
