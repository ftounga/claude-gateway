package fr.claudegateway.billing;

import static fr.claudegateway.billing.EntitlementSpace.FORGE;
import static fr.claudegateway.billing.EntitlementSpace.VIGIE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * Tests unitaires du <b>droit d'espace</b> (F-107 / SF-107-02).
 *
 * <p>Ce service absorbe {@code AtelierEntitlementService} et {@code TeamsEntitlementService} <b>sans
 * changer leurs réponses</b> : les deux suites qui les figeaient sont portées ici cas par cas — la
 * première sur l'espace {@link EntitlementSpace#FORGE}, la seconde sur {@link EntitlementSpace#VIGIE}.
 * Une assertion qui changerait ici serait une régression de droit.</p>
 */
@ExtendWith(MockitoExtension.class)
class SpaceEntitlementServiceTest {

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private AccessGrantService accessGrantService;

    /** F-107 / SF-107-06 : personne n'est administrateur par défaut. */
    @Mock
    private AdministratorEntitlement administratorEntitlement;

    private SpaceEntitlementService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SpaceEntitlementService(subscriptionService, accessGrantService,
                administratorEntitlement);
    }

    private Subscription forge(PlanCode plan, SubscriptionStatus status, SubscriptionStatus option) {
        return Subscription.builder().userId(userId).planCode(plan).status(status)
                .atelierOptionStatus(option).build();
    }

    private Subscription vigie(PlanCode plan, SubscriptionStatus status, SubscriptionStatus option) {
        return Subscription.builder().userId(userId).planCode(plan).status(status)
                .teamsOptionStatus(option).build();
    }

    @Test
    @DisplayName("un espace absent est une erreur de programmation, jamais un droit")
    void nullSpaceIsRejected() {
        Subscription gold = forge(PlanCode.GOLD, SubscriptionStatus.ACTIVE, null);

        assertThatThrownBy(() -> service.isEntitled(gold, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.isEntitled(userId, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.isOptionCarrier(PlanCode.SOLO, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SF-107-04 : l'accès offert est lu pour l'espace demandé")
    void accessCodesAreReadPerSpace() {
        when(accessGrantService.isGrantedWithGrace(userId, VIGIE)).thenReturn(true);
        when(accessGrantService.isGrantedWithGrace(userId, FORGE)).thenReturn(false);
        Subscription trial = forge(null, SubscriptionStatus.TRIALING, null);

        assertThat(service.isEntitled(trial, VIGIE)).isTrue();
        assertThat(service.isEntitled(trial, FORGE)).isFalse();
    }

    @Test
    @DisplayName("SF-107-04 : le droit par abonnement ignore l'accès offert")
    void entitledBySubscriptionIgnoresAccessCodes() {
        when(subscriptionService.getOrCreateForUser(userId))
                .thenReturn(vigie(PlanCode.SOLO, SubscriptionStatus.ACTIVE, null));
        assertThat(service.isEntitledBySubscription(userId, VIGIE)).isFalse();
        verifyNoInteractions(accessGrantService);

        when(subscriptionService.getOrCreateForUser(userId))
                .thenReturn(vigie(PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE));
        assertThat(service.isEntitledBySubscription(userId, VIGIE)).isTrue();
    }

    @Test
    @DisplayName("l'option d'un espace n'ouvre jamais l'autre")
    void optionsDoNotLeakAcrossSpaces() {
        Subscription forgeOption = forge(PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE);
        Subscription vigieOption = vigie(PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE);

        assertThat(service.isGrantedByOption(forgeOption, VIGIE)).isFalse();
        assertThat(service.isGrantedByOption(vigieOption, FORGE)).isFalse();
    }

    // =====================================================================================
    // FORGE — portage intégral de AtelierEntitlementServiceTest (F-40, F-62, SF-107-01, SF-107-06)
    // =====================================================================================

    @Nested
    @DisplayName("Forge — non-régression Gold : l'abonné Gold garde exactement l'accès qu'il avait")
    class ForgeGoldNonRegression {

        @Test
        void goldActiveKeepsAccess() {
            Subscription gold = forge(PlanCode.GOLD, SubscriptionStatus.ACTIVE, null);

            assertThat(service.isEntitled(gold, FORGE)).isTrue();
            assertThat(service.isIncludedInPlan(gold, FORGE)).isTrue();
            assertThat(service.isGrantedByOption(gold, FORGE)).isFalse();
        }

        @Test
        void goldPastDueKeepsAccessAsGrace() {
            assertThat(service.isEntitled(forge(PlanCode.GOLD, SubscriptionStatus.PAST_DUE, null), FORGE)).isTrue();
        }

        @Test
        void goldCanceledStaysDenied() {
            assertThat(service.isEntitled(forge(PlanCode.GOLD, SubscriptionStatus.CANCELED, null), FORGE)).isFalse();
        }

        @Test
        void goldIncompleteStaysDenied() {
            assertThat(service.isEntitled(forge(PlanCode.GOLD, SubscriptionStatus.INCOMPLETE, null), FORGE))
                    .isFalse();
        }

        @Test
        void goldWithOptionIsStillAllowedAndUnaffected() {
            Subscription gold = forge(PlanCode.GOLD, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE);

            assertThat(service.isEntitled(gold, FORGE)).isTrue();
            assertThat(service.isIncludedInPlan(gold, FORGE)).isTrue();
        }
    }

    @Nested
    @DisplayName("Forge — un plan sans option reste refusé")
    class ForgeWithoutOption {

        @Test
        void soloActiveWithoutOptionIsDenied() {
            Subscription solo = forge(PlanCode.SOLO, SubscriptionStatus.ACTIVE, null);

            assertThat(service.isEntitled(solo, FORGE)).isFalse();
            assertThat(service.isIncludedInPlan(solo, FORGE)).isFalse();
            assertThat(service.isGrantedByOption(solo, FORGE)).isFalse();
        }

        @Test
        void proActiveWithoutOptionIsDenied() {
            assertThat(service.isEntitled(forge(PlanCode.PRO, SubscriptionStatus.ACTIVE, null), FORGE)).isFalse();
        }

        @Test
        void trialingWithoutPlanIsDenied() {
            assertThat(service.isEntitled(forge(null, SubscriptionStatus.TRIALING, null), FORGE)).isFalse();
        }
    }

    @Nested
    @DisplayName("Forge — l'option ouvre le droit, sur un plan porteur actif seulement")
    class ForgeWithOption {

        @Test
        void soloWithActiveOptionIsAllowed() {
            Subscription solo = forge(PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE);

            assertThat(service.isEntitled(solo, FORGE)).isTrue();
            assertThat(service.isGrantedByOption(solo, FORGE)).isTrue();
            assertThat(service.isIncludedInPlan(solo, FORGE)).isFalse();
        }

        @Test
        void proWithPastDueOptionIsAllowedAsGrace() {
            assertThat(service.isEntitled(
                    forge(PlanCode.PRO, SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE), FORGE)).isTrue();
        }

        @Test
        void canceledOptionIsDenied() {
            assertThat(service.isEntitled(
                    forge(PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELED), FORGE)).isFalse();
        }

        @Test
        void incompleteOptionIsDenied() {
            assertThat(service.isEntitled(
                    forge(PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.INCOMPLETE), FORGE)).isFalse();
        }

        @Test
        void optionAloneDoesNotHoldWhenPlanIsCanceled() {
            assertThat(service.isEntitled(
                    forge(PlanCode.SOLO, SubscriptionStatus.CANCELED, SubscriptionStatus.ACTIVE), FORGE)).isFalse();
        }

        @Test
        void optionOnTrialIsDenied() {
            assertThat(service.isEntitled(
                    forge(null, SubscriptionStatus.TRIALING, SubscriptionStatus.ACTIVE), FORGE)).isFalse();
        }

        @Test
        void optionOnDayPassIsDenied() {
            assertThat(service.isEntitled(
                    forge(PlanCode.DAILY, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE), FORGE)).isFalse();
        }
    }

    @Test
    void forgeByUserIdReadsTheSubscriptionOfThatUserOnly() {
        when(subscriptionService.getOrCreateForUser(userId))
                .thenReturn(forge(PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE));

        assertThat(service.isEntitled(userId, FORGE)).isTrue();
    }

    @Nested
    @DisplayName("Forge — offre BYOK (SF-107-01) : la Forge ne s'y comprend plus, elle s'y achète")
    class ForgeByokPlan {

        @Test
        void byokActiveWithoutOptionIsDenied() {
            Subscription byok = forge(PlanCode.BYOK, SubscriptionStatus.ACTIVE, null);

            assertThat(service.isEntitled(byok, FORGE)).isFalse();
            assertThat(service.isIncludedInPlan(byok, FORGE)).isFalse();
            assertThat(service.isGrantedByOption(byok, FORGE)).isFalse();
        }

        @Test
        void byokPastDueWithoutOptionIsDenied() {
            assertThat(service.isEntitled(forge(PlanCode.BYOK, SubscriptionStatus.PAST_DUE, null), FORGE)).isFalse();
        }

        @Test
        void byokWithActiveOptionIsAllowed() {
            Subscription byok = forge(PlanCode.BYOK, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE);

            assertThat(service.isEntitled(byok, FORGE)).isTrue();
            assertThat(service.isGrantedByOption(byok, FORGE)).isTrue();
            assertThat(service.isIncludedInPlan(byok, FORGE)).isFalse();
        }

        @Test
        void byokPastDueWithPastDueOptionKeepsAccessAsGrace() {
            assertThat(service.isEntitled(
                    forge(PlanCode.BYOK, SubscriptionStatus.PAST_DUE, SubscriptionStatus.PAST_DUE), FORGE)).isTrue();
        }

        @Test
        void optionAloneDoesNotHoldWhenByokIsCanceled() {
            Subscription canceled = forge(PlanCode.BYOK, SubscriptionStatus.CANCELED, SubscriptionStatus.ACTIVE);

            assertThat(service.isEntitled(canceled, FORGE)).isFalse();
            assertThat(service.isIncludedInPlan(canceled, FORGE)).isFalse();
        }

        @Test
        void canceledOptionOnByokIsDenied() {
            assertThat(service.isEntitled(
                    forge(PlanCode.BYOK, SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELED), FORGE)).isFalse();
        }

        @Test
        void carrierPlansAreSoloProAndByokOnly() {
            assertThat(service.isOptionCarrier(PlanCode.SOLO, FORGE)).isTrue();
            assertThat(service.isOptionCarrier(PlanCode.PRO, FORGE)).isTrue();
            assertThat(service.isOptionCarrier(PlanCode.BYOK, FORGE)).isTrue();
            assertThat(service.isOptionCarrier(PlanCode.GOLD, FORGE)).isFalse();
            assertThat(service.isOptionCarrier(PlanCode.GOLD_COMPLETE, FORGE)).isFalse();
            assertThat(service.isOptionCarrier(PlanCode.GOLD_VIGIE, FORGE)).as("SF-107-03").isTrue();
            assertThat(service.isOptionCarrier(PlanCode.DAILY, FORGE)).isFalse();
            assertThat(service.isOptionCarrier(null, FORGE)).isFalse();
        }
    }

    @Nested
    @DisplayName("Forge — accès offert par un code (F-62) : une quatrième source de droit, pas un plan")
    class ForgeAccessCodeGrant {

        @Test
        void grantOpensAccessToAnAccountWithNoPlanAtAll() {
            when(accessGrantService.isGrantedWithGrace(userId, FORGE)).thenReturn(true);
            Subscription trial = forge(null, SubscriptionStatus.TRIALING, null);

            assertThat(service.isEntitled(trial, FORGE)).isTrue();
            assertThat(service.isIncludedInPlan(trial, FORGE)).isFalse();
            assertThat(service.isGrantedByOption(trial, FORGE)).isFalse();
        }

        @Test
        void expiredGrantClosesAccessWithoutAnythingHavingRun() {
            when(accessGrantService.isGrantedWithGrace(userId, FORGE)).thenReturn(false);

            assertThat(service.isEntitled(forge(null, SubscriptionStatus.TRIALING, null), FORGE)).isFalse();
        }

        @Test
        void grantOnASoloPlanDoesNotChangeWhereTheRightComesFrom() {
            when(accessGrantService.isGrantedWithGrace(userId, FORGE)).thenReturn(true);
            Subscription solo = forge(PlanCode.SOLO, SubscriptionStatus.ACTIVE, null);

            assertThat(service.isEntitled(solo, FORGE)).isTrue();
            assertThat(solo.getPlanCode()).isEqualTo(PlanCode.SOLO);
            assertThat(solo.getAtelierOptionStatus()).isNull();
        }

        @Test
        void goldNeedsNoGrantAndNoneIsEvenConsulted() {
            Subscription gold = forge(PlanCode.GOLD, SubscriptionStatus.ACTIVE, null);

            assertThat(service.isEntitled(gold, FORGE)).isTrue();
            verifyNoInteractions(accessGrantService);
        }
    }

    @Nested
    @DisplayName("Forge — SF-107-06 : l'administrateur a tout, quel que soit son plan")
    class ForgeAdministratorHasEverything {

        @Test
        void adminWithoutOptionIsEntitledByUserId() {
            when(administratorEntitlement.isAdministrator(userId)).thenReturn(true);

            assertThat(service.isEntitled(userId, FORGE)).isTrue();
            verifyNoInteractions(subscriptionService, accessGrantService);
        }

        @Test
        void adminWithoutOptionIsEntitledBySubscription() {
            when(administratorEntitlement.isAdministrator(userId)).thenReturn(true);
            Subscription expired = forge(null, SubscriptionStatus.TRIALING, null);

            assertThat(service.isEntitled(expired, FORGE)).isTrue();
            assertThat(service.isGrantedByRole(userId)).isTrue();
        }

        @Test
        void theRoleDoesNotPretendToBeAPlanOrAnOption() {
            Subscription solo = forge(PlanCode.SOLO, SubscriptionStatus.ACTIVE, null);

            assertThat(service.isIncludedInPlan(solo, FORGE)).isFalse();
            assertThat(service.isGrantedByOption(solo, FORGE)).isFalse();
            verifyNoInteractions(administratorEntitlement);
        }

        @Test
        void userWithoutOptionStaysDenied() {
            when(administratorEntitlement.isAdministrator(userId)).thenReturn(false);
            when(subscriptionService.getOrCreateForUser(userId))
                    .thenReturn(forge(PlanCode.SOLO, SubscriptionStatus.ACTIVE, null));

            assertThat(service.isEntitled(userId, FORGE)).isFalse();
        }
    }

    // =====================================================================================
    // VIGIE — portage intégral de TeamsEntitlementServiceTest (F-89 D5, F-106, SF-107-06)
    // =====================================================================================

    /** Le droit Vigie lu par le chemin complet, accès offert compris. */
    private boolean vigieEntitled(Subscription subscription, boolean grant) {
        when(subscriptionService.getOrCreateForUser(userId)).thenReturn(subscription);
        lenient().when(accessGrantService.isGrantedWithGrace(userId, VIGIE)).thenReturn(grant);
        return service.isEntitled(userId, VIGIE);
    }

    @Nested
    @DisplayName("Vigie — aucun plan n'inclut la Vigie : c'est une option, et rien d'autre")
    class VigieNoPlanIncludesIt {

        @Test
        void goldWithoutOptionIsRefused() {
            assertThat(vigieEntitled(vigie(PlanCode.GOLD, SubscriptionStatus.ACTIVE, null), false)).isFalse();
        }

        @Test
        void byokWithoutOptionIsRefused() {
            assertThat(vigieEntitled(vigie(PlanCode.BYOK, SubscriptionStatus.ACTIVE, null), false)).isFalse();
        }

        @Test
        void soloWithoutOptionIsRefused() {
            assertThat(vigieEntitled(vigie(PlanCode.SOLO, SubscriptionStatus.ACTIVE, null), false)).isFalse();
        }

        @Test
        void noPlanOfBeforeF107IsIncluding() {
            for (PlanCode plan : new PlanCode[] {PlanCode.SOLO, PlanCode.PRO, PlanCode.DAILY, PlanCode.GOLD,
                    PlanCode.BYOK}) {
                assertThat(service.isIncludedInPlan(vigie(plan, SubscriptionStatus.ACTIVE, null), VIGIE))
                        .as(plan.name()).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("Vigie — tout plan mensuel en cours peut porter l'option")
    class VigieEveryMonthlyPlanCarriesIt {

        @Test
        void soloWithOption() {
            assertThat(vigieEntitled(vigie(PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE),
                    false)).isTrue();
        }

        @Test
        void proWithOption() {
            assertThat(vigieEntitled(vigie(PlanCode.PRO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE),
                    false)).isTrue();
        }

        @Test
        void goldWithOption() {
            assertThat(vigieEntitled(vigie(PlanCode.GOLD, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE),
                    false)).isTrue();
        }

        @Test
        void byokWithOption() {
            assertThat(vigieEntitled(vigie(PlanCode.BYOK, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE),
                    false)).isTrue();
        }

        @Test
        void pastDueIsStillLive() {
            assertThat(vigieEntitled(vigie(PlanCode.PRO, SubscriptionStatus.PAST_DUE, SubscriptionStatus.PAST_DUE),
                    false)).isTrue();
        }

        @Test
        void carriersAreEveryMonthlyPlan() {
            assertThat(service.isOptionCarrier(PlanCode.SOLO, VIGIE)).isTrue();
            assertThat(service.isOptionCarrier(PlanCode.PRO, VIGIE)).isTrue();
            assertThat(service.isOptionCarrier(PlanCode.GOLD, VIGIE)).isTrue();
            assertThat(service.isOptionCarrier(PlanCode.BYOK, VIGIE)).isTrue();
            assertThat(service.isOptionCarrier(PlanCode.DAILY, VIGIE)).isFalse();
            assertThat(service.isOptionCarrier(PlanCode.GOLD_VIGIE, VIGIE)).isFalse();
            assertThat(service.isOptionCarrier(PlanCode.GOLD_COMPLETE, VIGIE)).isFalse();
            assertThat(service.isOptionCarrier(null, VIGIE)).isFalse();
        }
    }

    // =====================================================================================
    // SF-107-03 — Gold Vigie et Gold complet
    // =====================================================================================

    private Subscription both(PlanCode plan, SubscriptionStatus status, SubscriptionStatus forgeOption,
            SubscriptionStatus vigieOption) {
        return Subscription.builder().userId(userId).planCode(plan).status(status)
                .atelierOptionStatus(forgeOption).teamsOptionStatus(vigieOption).build();
    }

    @Nested
    @DisplayName("SF-107-03 — un Gold se distingue par l'espace qu'il inclut")
    class SpaceGolds {

        @Test
        @DisplayName("Gold Forge (GOLD) : Forge incluse, Vigie par l'option seulement")
        void goldForgeIncludesForgeAndCarriesVigie() {
            Subscription gold = both(PlanCode.GOLD, SubscriptionStatus.ACTIVE, null, null);
            assertThat(service.isEntitled(gold, FORGE)).isTrue();
            assertThat(service.isIncludedInPlan(gold, VIGIE)).isFalse();

            Subscription withVigie = both(PlanCode.GOLD, SubscriptionStatus.ACTIVE, null, SubscriptionStatus.ACTIVE);
            assertThat(service.isEntitled(withVigie, VIGIE)).isTrue();
            assertThat(service.isGrantedByOption(withVigie, VIGIE)).isTrue();
        }

        @Test
        @DisplayName("Gold Vigie : Vigie incluse, Forge refusée sans option, ouverte avec")
        void goldVigieIncludesVigieAndCarriesForge() {
            Subscription goldVigie = both(PlanCode.GOLD_VIGIE, SubscriptionStatus.ACTIVE, null, null);
            assertThat(service.isEntitled(goldVigie, VIGIE)).isTrue();
            assertThat(service.isIncludedInPlan(goldVigie, VIGIE)).isTrue();
            assertThat(service.isEntitled(goldVigie, FORGE)).isFalse();

            Subscription withForge = both(PlanCode.GOLD_VIGIE, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE, null);
            assertThat(service.isEntitled(withForge, FORGE)).isTrue();
            assertThat(service.isGrantedByOption(withForge, FORGE)).isTrue();
        }

        @Test
        @DisplayName("Gold complet : les deux espaces inclus, sursis compris")
        void goldCompleteIncludesBothSpaces() {
            Subscription complete = both(PlanCode.GOLD_COMPLETE, SubscriptionStatus.PAST_DUE, null, null);
            assertThat(service.isEntitled(complete, FORGE)).isTrue();
            assertThat(service.isEntitled(complete, VIGIE)).isTrue();
            assertThat(service.isIncludedInPlan(complete, FORGE)).isTrue();
            assertThat(service.isIncludedInPlan(complete, VIGIE)).isTrue();
        }

        @Test
        @DisplayName("Gold complet résilié : plus rien d'inclus")
        void canceledGoldCompleteOpensNothing() {
            when(accessGrantService.isGrantedWithGrace(org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.any())).thenReturn(false);
            Subscription complete = both(PlanCode.GOLD_COMPLETE, SubscriptionStatus.CANCELED, null, null);
            assertThat(service.isEntitled(complete, FORGE)).isFalse();
            assertThat(service.isEntitled(complete, VIGIE)).isFalse();
        }
    }

    @Nested
    @DisplayName("Vigie — ce qui ne porte pas l'option")
    class VigieWhatDoesNotCarryIt {

        @Test
        void dailyDoesNotCarryTheOption() {
            assertThat(vigieEntitled(vigie(PlanCode.DAILY, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE),
                    false)).isFalse();
        }

        @Test
        void optionOnCanceledPlanIsRefused() {
            assertThat(vigieEntitled(vigie(PlanCode.PRO, SubscriptionStatus.CANCELED, SubscriptionStatus.ACTIVE),
                    false)).isFalse();
        }

        @Test
        void trialWithoutPlanIsRefused() {
            assertThat(vigieEntitled(vigie(null, SubscriptionStatus.TRIALING, SubscriptionStatus.ACTIVE), false))
                    .isFalse();
        }

        @Test
        void canceledOptionIsRefused() {
            assertThat(vigieEntitled(vigie(PlanCode.PRO, SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELED),
                    false)).isFalse();
        }
    }

    @Nested
    @DisplayName("Vigie — l'essai se donne par code d'accès (F-62, décision D5)")
    class VigieAccessGrant {

        @Test
        void grantOpensVigie() {
            assertThat(vigieEntitled(vigie(PlanCode.SOLO, SubscriptionStatus.ACTIVE, null), true)).isTrue();
        }

        @Test
        void expiredGrantOpensNothing() {
            assertThat(vigieEntitled(vigie(PlanCode.SOLO, SubscriptionStatus.ACTIVE, null), false)).isFalse();
        }
    }

    @Nested
    @DisplayName("Vigie — l'option ouvre l'accès, elle n'ajoute pas de jetons")
    class VigieNoTokensAdded {

        @Test
        void theEntitlementNeverReadsAQuota() {
            Subscription pro = vigie(PlanCode.PRO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE);

            assertThat(service.isGrantedByOption(pro, VIGIE)).isTrue();

            verifyNoInteractions(subscriptionService, accessGrantService);
        }
    }

    @Nested
    @DisplayName("Vigie — SF-107-06 : l'administrateur a tout, quel que soit son plan")
    class VigieAdministratorHasEverything {

        @Test
        void adminWithoutOptionIsEntitled() {
            when(administratorEntitlement.isAdministrator(userId)).thenReturn(true);

            assertThat(service.isEntitled(userId, VIGIE)).isTrue();
            verifyNoInteractions(subscriptionService, accessGrantService);
        }

        @Test
        void userWithoutOptionStaysDenied() {
            when(administratorEntitlement.isAdministrator(userId)).thenReturn(false);

            assertThat(vigieEntitled(vigie(PlanCode.GOLD, SubscriptionStatus.ACTIVE, null), false)).isFalse();
        }
    }
}
