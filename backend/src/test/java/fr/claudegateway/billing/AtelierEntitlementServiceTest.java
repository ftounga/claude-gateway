package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests unitaires de la règle du droit d'Atelier (F-40 / SF-40-01).
 *
 * <p>Le premier bloc est le plus important de la feature : il fige que le passage d'un test de
 * <b>plan</b> à un test de <b>droit</b> ne retire rien à un abonné Gold. Le second prouve qu'un
 * Solo sans option reste refusé — l'option doit ouvrir le droit, jamais le plan seul.</p>
 */
@ExtendWith(MockitoExtension.class)
class AtelierEntitlementServiceTest {

    @Mock
    private SubscriptionService subscriptionService;

    private AtelierEntitlementService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AtelierEntitlementService(subscriptionService);
    }

    private Subscription subscription(PlanCode plan, SubscriptionStatus status, SubscriptionStatus option) {
        return Subscription.builder()
                .userId(userId)
                .planCode(plan)
                .status(status)
                .atelierOptionStatus(option)
                .build();
    }

    @Nested
    @DisplayName("Non-régression Gold — l'abonné Gold garde exactement l'accès qu'il avait")
    class GoldNonRegression {

        @Test
        void goldActiveKeepsAccess() {
            Subscription gold = subscription(PlanCode.GOLD, SubscriptionStatus.ACTIVE, null);

            assertThat(service.isEntitled(gold)).isTrue();
            assertThat(service.isIncludedInPlan(gold)).isTrue();
            assertThat(service.isGrantedByOption(gold)).isFalse();
        }

        @Test
        void goldPastDueKeepsAccessAsGrace() {
            assertThat(service.isEntitled(subscription(PlanCode.GOLD, SubscriptionStatus.PAST_DUE, null)))
                    .isTrue();
        }

        @Test
        void goldCanceledStaysDenied() {
            assertThat(service.isEntitled(subscription(PlanCode.GOLD, SubscriptionStatus.CANCELED, null)))
                    .isFalse();
        }

        @Test
        void goldIncompleteStaysDenied() {
            assertThat(service.isEntitled(subscription(PlanCode.GOLD, SubscriptionStatus.INCOMPLETE, null)))
                    .isFalse();
        }

        @Test
        void goldWithOptionIsStillAllowedAndUnaffected() {
            // Cumul sans effet de bord : payer l'option en Gold ne retire rien (et n'ajoute rien).
            Subscription gold = subscription(PlanCode.GOLD, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE);

            assertThat(service.isEntitled(gold)).isTrue();
            assertThat(service.isIncludedInPlan(gold)).isTrue();
        }
    }

    @Nested
    @DisplayName("Un plan sans option reste refusé")
    class WithoutOption {

        @Test
        void soloActiveWithoutOptionIsDenied() {
            Subscription solo = subscription(PlanCode.SOLO, SubscriptionStatus.ACTIVE, null);

            assertThat(service.isEntitled(solo)).isFalse();
            assertThat(service.isIncludedInPlan(solo)).isFalse();
            assertThat(service.isGrantedByOption(solo)).isFalse();
        }

        @Test
        void proActiveWithoutOptionIsDenied() {
            assertThat(service.isEntitled(subscription(PlanCode.PRO, SubscriptionStatus.ACTIVE, null)))
                    .isFalse();
        }

        @Test
        void trialingWithoutPlanIsDenied() {
            assertThat(service.isEntitled(subscription(null, SubscriptionStatus.TRIALING, null))).isFalse();
        }
    }

    @Nested
    @DisplayName("L'option ouvre le droit, sur un plan porteur actif seulement")
    class WithOption {

        @Test
        void soloWithActiveOptionIsAllowed() {
            Subscription solo = subscription(PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE);

            assertThat(service.isEntitled(solo)).isTrue();
            assertThat(service.isGrantedByOption(solo)).isTrue();
            assertThat(service.isIncludedInPlan(solo)).isFalse();
        }

        @Test
        void proWithPastDueOptionIsAllowedAsGrace() {
            assertThat(service.isEntitled(
                    subscription(PlanCode.PRO, SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE)))
                    .isTrue();
        }

        @Test
        void canceledOptionIsDenied() {
            assertThat(service.isEntitled(
                    subscription(PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELED)))
                    .isFalse();
        }

        @Test
        void incompleteOptionIsDenied() {
            assertThat(service.isEntitled(
                    subscription(PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.INCOMPLETE)))
                    .isFalse();
        }

        @Test
        void optionAloneDoesNotHoldWhenPlanIsCanceled() {
            // L'option est un supplément : sans plan actif, aucun jeton ne ferait tourner l'Atelier.
            assertThat(service.isEntitled(
                    subscription(PlanCode.SOLO, SubscriptionStatus.CANCELED, SubscriptionStatus.ACTIVE)))
                    .isFalse();
        }

        @Test
        void optionOnTrialIsDenied() {
            assertThat(service.isEntitled(
                    subscription(null, SubscriptionStatus.TRIALING, SubscriptionStatus.ACTIVE)))
                    .isFalse();
        }

        @Test
        void optionOnDayPassIsDenied() {
            // DAILY n'est pas un plan porteur : un pass journée ne porte pas un abonnement mensuel.
            assertThat(service.isEntitled(
                    subscription(PlanCode.DAILY, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE)))
                    .isFalse();
        }
    }

    @Test
    void byUserIdReadsTheSubscriptionOfThatUserOnly() {
        when(subscriptionService.getOrCreateForUser(userId))
                .thenReturn(subscription(PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE));

        assertThat(service.isEntitled(userId)).isTrue();
    }
}
