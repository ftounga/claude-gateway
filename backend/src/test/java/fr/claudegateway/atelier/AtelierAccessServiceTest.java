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
import fr.claudegateway.billing.AtelierEntitlementService;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionService;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.user.UserRole;

/**
 * Tests unitaires du gating de l'Atelier (F-28 / SF-28-06, amendé F-40 / SF-40-01) : accès réservé
 * aux administrateurs (bypass) et aux détenteurs du <b>droit</b> d'Atelier, fail-closed sinon.
 *
 * <p>La règle du droit est ici la <b>vraie</b> ({@link AtelierEntitlementService} branché sur un
 * {@link SubscriptionService} simulé) et non un bouchon : c'est la composition des deux services qui
 * garde le comportement d'avant F-40, et c'est donc elle qu'on veut voir.</p>
 */
@ExtendWith(MockitoExtension.class)
class AtelierAccessServiceTest {

    @Mock private CurrentUser currentUser;
    @Mock private SubscriptionService subscriptionService;

    private AtelierAccessService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AtelierAccessService(currentUser, new AtelierEntitlementService(subscriptionService));
    }

    private AuthenticatedUser principal(UserRole role) {
        return new AuthenticatedUser(userId, "u@example.com", role);
    }

    private Subscription subscription(PlanCode plan, SubscriptionStatus status) {
        return Subscription.builder().userId(userId).planCode(plan).status(status).build();
    }

    private Subscription withAtelierOption(PlanCode plan, SubscriptionStatus status,
            SubscriptionStatus optionStatus) {
        return Subscription.builder().userId(userId).planCode(plan).status(status)
                .atelierOptionStatus(optionStatus).build();
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
    void soloWithAtelierOptionIsAllowed() {
        // F-40 : le droit n'est plus un plan. Solo + option ouvre l'Atelier sans passer par Gold.
        when(currentUser.principal()).thenReturn(Optional.of(principal(UserRole.USER)));
        when(subscriptionService.getOrCreateForUser(userId)).thenReturn(
                withAtelierOption(PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE));

        assertThat(service.hasAccess()).isTrue();
        assertThatCode(service::requireAccess).doesNotThrowAnyException();
    }

    @Test
    void soloWithoutAtelierOptionIsDenied() {
        when(currentUser.principal()).thenReturn(Optional.of(principal(UserRole.USER)));
        when(subscriptionService.getOrCreateForUser(userId))
                .thenReturn(subscription(PlanCode.SOLO, SubscriptionStatus.ACTIVE));

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

    // ------------------------------------------------ F-41 / SF-41-01 : l'offre BYOK ouvre l'Atelier

    @Test
    void byokPlanOpensAtelierForARegularUser() {
        when(currentUser.principal()).thenReturn(Optional.of(principal(UserRole.USER)));
        when(subscriptionService.getOrCreateForUser(userId))
                .thenReturn(subscription(PlanCode.BYOK, SubscriptionStatus.ACTIVE));

        assertThat(service.hasAccess()).isTrue();
        assertThatCode(() -> service.requireAccess()).doesNotThrowAnyException();
    }

    @Test
    void canceledByokPlanClosesAtelierAgain() {
        when(currentUser.principal()).thenReturn(Optional.of(principal(UserRole.USER)));
        when(subscriptionService.getOrCreateForUser(userId))
                .thenReturn(subscription(PlanCode.BYOK, SubscriptionStatus.CANCELED));

        assertThat(service.hasAccess()).isFalse();
        assertThatThrownBy(() -> service.requireAccess())
                .isInstanceOf(AtelierAccessDeniedException.class);
    }
}
