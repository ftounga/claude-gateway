package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.access.AccessGrantService;

/**
 * Tests unitaires de la règle du <b>droit Teams</b> (F-89 / SF-89-01, décision D5 du cadrage).
 *
 * <p>Trois choses y sont figées, et ce sont les trois qui coûteraient cher si elles dérivaient :
 * <b>aucun plan n'inclut Teams</b> (sans quoi on offrirait ce qu'on vend), <b>tout plan mensuel
 * peut porter l'option</b> (sans quoi un abonné Gold ne pourrait pas l'acheter), et <b>l'option
 * n'ajoute aucun jeton</b> — elle ouvre un droit, elle ne touche à aucun quota.</p>
 */
@ExtendWith(MockitoExtension.class)
class TeamsEntitlementServiceTest {

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private AccessGrantService accessGrantService;

    /** F-107 / SF-107-06 : personne n'est administrateur par défaut. */
    @Mock
    private AdministratorEntitlement administratorEntitlement;

    private TeamsEntitlementService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TeamsEntitlementService(subscriptionService, accessGrantService,
                administratorEntitlement);
    }

    private Subscription subscription(PlanCode plan, SubscriptionStatus status,
            SubscriptionStatus teamsOption) {
        return Subscription.builder()
                .userId(userId)
                .planCode(plan)
                .status(status)
                .teamsOptionStatus(teamsOption)
                .build();
    }

    /** Le droit lu par le chemin complet, accès offert compris. */
    private boolean entitled(Subscription subscription, boolean grant) {
        when(subscriptionService.getOrCreateForUser(userId)).thenReturn(subscription);
        lenient().when(accessGrantService.isGrantedWithGrace(userId)).thenReturn(grant);
        return service.isEntitled(userId);
    }

    @Nested
    @DisplayName("Aucun plan n'inclut Teams — c'est une option, et rien d'autre")
    class NoPlanIncludesTeams {

        @Test
        @DisplayName("Gold actif SANS option : refusé, contrairement au droit d'Atelier")
        void goldWithoutOptionIsRefused() {
            assertThat(entitled(subscription(PlanCode.GOLD, SubscriptionStatus.ACTIVE, null), false))
                    .isFalse();
        }

        @Test
        @DisplayName("BYOK actif sans option : refusé — apporter ses jetons n'ouvre pas Teams")
        void byokWithoutOptionIsRefused() {
            assertThat(entitled(subscription(PlanCode.BYOK, SubscriptionStatus.ACTIVE, null), false))
                    .isFalse();
        }

        @Test
        void soloWithoutOptionIsRefused() {
            assertThat(entitled(subscription(PlanCode.SOLO, SubscriptionStatus.ACTIVE, null), false))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Tout plan mensuel en cours peut porter l'option")
    class EveryMonthlyPlanCarriesIt {

        @Test
        void soloWithOption() {
            assertThat(entitled(
                    subscription(PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE),
                    false)).isTrue();
        }

        @Test
        void proWithOption() {
            assertThat(entitled(
                    subscription(PlanCode.PRO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE),
                    false)).isTrue();
        }

        @Test
        @DisplayName("Gold aussi : il n'inclut pas Teams, donc il peut l'acheter")
        void goldWithOption() {
            assertThat(entitled(
                    subscription(PlanCode.GOLD, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE),
                    false)).isTrue();
        }

        @Test
        void byokWithOption() {
            assertThat(entitled(
                    subscription(PlanCode.BYOK, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE),
                    false)).isTrue();
        }

        @Test
        @DisplayName("le sursis de paiement vaut « en cours », des deux côtés")
        void pastDueIsStillLive() {
            assertThat(entitled(subscription(PlanCode.PRO, SubscriptionStatus.PAST_DUE,
                    SubscriptionStatus.PAST_DUE), false)).isTrue();
        }
    }

    @Nested
    @DisplayName("Ce qui ne porte pas l'option")
    class WhatDoesNotCarryIt {

        @Test
        @DisplayName("le pass journée ne porte pas un abonnement mensuel")
        void dailyDoesNotCarryTheOption() {
            assertThat(entitled(
                    subscription(PlanCode.DAILY, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE),
                    false)).isFalse();
        }

        @Test
        @DisplayName("une option active sur un plan résilié ne tient pas : c'est un supplément")
        void optionOnCanceledPlanIsRefused() {
            assertThat(entitled(subscription(PlanCode.PRO, SubscriptionStatus.CANCELED,
                    SubscriptionStatus.ACTIVE), false)).isFalse();
        }

        @Test
        @DisplayName("un essai sans plan (planCode nul) ne porte rien")
        void trialWithoutPlanIsRefused() {
            assertThat(entitled(subscription(null, SubscriptionStatus.TRIALING,
                    SubscriptionStatus.ACTIVE), false)).isFalse();
        }

        @Test
        void canceledOptionIsRefused() {
            assertThat(entitled(subscription(PlanCode.PRO, SubscriptionStatus.ACTIVE,
                    SubscriptionStatus.CANCELED), false)).isFalse();
        }
    }

    @Nested
    @DisplayName("L'essai se donne par code d'accès (F-62, décision D5)")
    class AccessGrantOpensTeams {

        @Test
        @DisplayName("un accès offert en cours ouvre Teams, sans aucune option souscrite")
        void grantOpensTeams() {
            assertThat(entitled(subscription(PlanCode.SOLO, SubscriptionStatus.ACTIVE, null), true))
                    .isTrue();
        }

        @Test
        @DisplayName("un accès offert terminé ne rouvre rien")
        void expiredGrantOpensNothing() {
            assertThat(entitled(subscription(PlanCode.SOLO, SubscriptionStatus.ACTIVE, null), false))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("L'option ouvre l'accès, elle n'ajoute pas de jetons")
    class NoTokensAdded {

        /**
         * Le quota est lu ailleurs, par {@code QuotaProperties}, à partir du <b>plan</b> : ce test
         * fige que le service de droit ne touche à aucun quota, en vérifiant qu'il ne connaît
         * strictement que l'abonnement et l'accès offert.
         */
        @Test
        void theEntitlementNeverReadsAQuota() {
            Subscription pro = subscription(PlanCode.PRO, SubscriptionStatus.ACTIVE,
                    SubscriptionStatus.ACTIVE);

            assertThat(service.isGrantedByOption(pro)).isTrue();

            verifyNoInteractions(subscriptionService, accessGrantService);
        }
    }

    @Nested
    @DisplayName("F-107 / SF-107-06 — l'administrateur a tout, quel que soit son plan")
    class AdministratorHasEverything {

        @Test
        @DisplayName("ADMIN sans option Teams : accès, sans lire l'abonnement ni l'accès offert")
        void adminWithoutOptionIsEntitled() {
            when(administratorEntitlement.isAdministrator(userId)).thenReturn(true);

            assertThat(service.isEntitled(userId)).isTrue();
            verifyNoInteractions(subscriptionService, accessGrantService);
        }

        @Test
        @DisplayName("USER sans option : refus inchangé")
        void userWithoutOptionStaysDenied() {
            when(administratorEntitlement.isAdministrator(userId)).thenReturn(false);

            assertThat(entitled(subscription(PlanCode.GOLD, SubscriptionStatus.ACTIVE, null), false))
                    .isFalse();
        }
    }
}
