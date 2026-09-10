package fr.claudegateway.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionService;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.user.UserRepository;

/**
 * Tests de l'émission et de la consommation d'un code d'accès (F-62 / SF-62-01).
 *
 * <p>Deux propriétés sont figées ici plus que les autres, parce qu'elles sont la promesse de la
 * feature :</p>
 * <ol>
 *   <li><b>rien n'est écrit dans l'abonnement</b> — le service n'a même pas le repository
 *       d'abonnement en dépendance, et le {@link SubscriptionService} n'est appelé qu'en lecture ;</li>
 *   <li><b>l'usage unique tient sous concurrence</b> — la condition « pas encore consommé » vit dans
 *       le {@code WHERE} de l'{@code UPDATE}, et un retour de zéro ligne est traité comme « déjà
 *       consommé », pas comme une panne.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccessCodeServiceTest {

    @Mock private AccessCodeRepository repository;
    @Mock private AccessGrantService accessGrantService;
    @Mock private SubscriptionService subscriptionService;
    @Mock private UserRepository userRepository;
    @Mock private AdminService adminService;
    @Mock private CurrentUser currentUser;

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 9, 10, 9, 0, 0, 0, ZoneOffset.UTC);

    private final UUID userId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID codeId = UUID.randomUUID();

    private AccessCodeService service;

    @BeforeEach
    void setUp() {
        service = new AccessCodeService(repository, accessGrantService, subscriptionService,
                userRepository, adminService, currentUser,
                new AccessCodeProperties(24, 30, 15),
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
        when(currentUser.requireId()).thenReturn(adminId);
        when(repository.save(any(AccessCode.class))).thenAnswer(inv -> {
            AccessCode saved = inv.getArgument(0);
            saved.setId(codeId);
            saved.setCreatedAt(NOW);
            return saved;
        });
        when(subscriptionService.getOrCreateForUser(userId)).thenReturn(Subscription.builder()
                .userId(userId).planCode(PlanCode.SOLO).status(SubscriptionStatus.ACTIVE).build());
        when(accessGrantService.activeGrant(userId)).thenReturn(Optional.empty());
    }

    private AccessCode storedCode(String clearCode) {
        return AccessCode.builder()
                .id(codeId)
                .codeHash(AccessCodeSecret.hash(AccessCodeSecret.normalize(clearCode)))
                .label("démo prospect")
                .grantedPlanCode(PlanCode.GOLD)
                .durationHours(24)
                .validUntil(NOW.plusDays(30))
                .createdByUserId(adminId)
                .createdAt(NOW)
                .build();
    }

    @Nested
    @DisplayName("Émission")
    class Issue {

        @Test
        @DisplayName("Le code en clair est renvoyé une fois ; la base n'en garde que l'empreinte")
        void issuedCodeIsReturnedOnceAndOnlyHashedIsStored() {
            AccessCodeService.IssuedAccessCode issued = service.issue("  démo prospect  ", null);

            ArgumentCaptor<AccessCode> captor = ArgumentCaptor.forClass(AccessCode.class);
            verify(repository).save(captor.capture());
            AccessCode saved = captor.getValue();

            assertThat(issued.code()).matches("FORGE-[A-Z2-9]{4}-[A-Z2-9]{4}");
            assertThat(saved.getCodeHash()).isEqualTo(AccessCodeSecret.hash(issued.code()));
            // Aucune colonne ne contient le code : c'est la propriété que l'on protège.
            assertThat(saved.getCodeHash()).doesNotContain(issued.code());
            assertThat(saved.getLabel()).isEqualTo("démo prospect");
        }

        @Test
        @DisplayName("Durée et validité sont figées à l'émission depuis la configuration")
        void durationAndValidityAreFrozenAtIssueTime() {
            service.issue("démo", null);

            ArgumentCaptor<AccessCode> captor = ArgumentCaptor.forClass(AccessCode.class);
            verify(repository).save(captor.capture());

            assertThat(captor.getValue().getDurationHours()).isEqualTo(24);
            assertThat(captor.getValue().getValidUntil()).isEqualTo(NOW.plusDays(30));
            assertThat(captor.getValue().getGrantedPlanCode()).isEqualTo(PlanCode.GOLD);
            assertThat(captor.getValue().getCreatedByUserId()).isEqualTo(adminId);
        }

        @Test
        @DisplayName("Un code nominatif range l'e-mail en minuscules")
        void assignedEmailIsNormalized() {
            service.issue("démo", "  Prospect@Example.COM ");

            ArgumentCaptor<AccessCode> captor = ArgumentCaptor.forClass(AccessCode.class);
            verify(repository).save(captor.capture());

            assertThat(captor.getValue().getAssignedEmail()).isEqualTo("prospect@example.com");
        }

        @Test
        @DisplayName("L'émission passe par la garde unique d'administration")
        void issueAssertsAdmin() {
            service.issue("démo", null);

            verify(adminService).assertAdmin();
        }
    }

    @Nested
    @DisplayName("Consommation")
    class Redeem {

        @Test
        @DisplayName("Nominal : le droit s'ouvre pour 24 h et la trace nomme le plan de retour")
        void redeemOpensA24hGrantAndRecordsThePreviousPlan() {
            String clear = "FORGE-AB2C-3D4E";
            when(repository.findByCodeHash(AccessCodeSecret.hash(clear)))
                    .thenReturn(Optional.of(storedCode(clear)));
            when(repository.consume(eq(codeId), eq(userId), any(), any(), any(), any())).thenReturn(1);

            AccessGrant grant = service.redeem(userId, "u@example.com", "  forge-ab2c-3d4e ");

            assertThat(grant.grantedPlanCode()).isEqualTo(PlanCode.GOLD);
            assertThat(grant.grantedUntil()).isEqualTo(NOW.plusHours(24));
            assertThat(grant.previousPlanCode()).isEqualTo(PlanCode.SOLO);
            verify(repository).consume(codeId, userId, NOW, NOW.plusHours(24),
                    PlanCode.SOLO, SubscriptionStatus.ACTIVE);
        }

        @Test
        @DisplayName("La consommation n'écrit RIEN dans l'abonnement")
        void redeemNeverWritesTheSubscription() {
            String clear = "FORGE-AB2C-3D4E";
            Subscription subscription = Subscription.builder()
                    .userId(userId).planCode(PlanCode.SOLO).status(SubscriptionStatus.ACTIVE).build();
            when(subscriptionService.getOrCreateForUser(userId)).thenReturn(subscription);
            when(repository.findByCodeHash(AccessCodeSecret.hash(clear)))
                    .thenReturn(Optional.of(storedCode(clear)));
            when(repository.consume(any(), any(), any(), any(), any(), any())).thenReturn(1);

            service.redeem(userId, "u@example.com", clear);

            // L'abonnement ressort intact — c'est précisément pourquoi il n'y a rien à restaurer au
            // terme, et pourquoi un code ne peut pas changer ce que le client paie.
            assertThat(subscription.getPlanCode()).isEqualTo(PlanCode.SOLO);
            assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
            assertThat(subscription.getStripeSubscriptionId()).isNull();
            assertThat(subscription.getAtelierOptionStatus()).isNull();
            // Une seule lecture, aucune écriture : le service n'a pas de repository d'abonnement.
            verify(subscriptionService).getOrCreateForUser(userId);
        }

        @Test
        @DisplayName("Code inconnu → refus, et aucune consommation tentée")
        void unknownCodeIsRefused() {
            when(repository.findByCodeHash(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.redeem(userId, "u@example.com", "FORGE-XXXX-XXXX"))
                    .isInstanceOf(AccessCodeInvalidException.class);
            verify(repository, never()).consume(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Code vide → refus sans même interroger la base")
        void blankCodeIsRefusedWithoutQuery() {
            assertThatThrownBy(() -> service.redeem(userId, "u@example.com", "   "))
                    .isInstanceOf(AccessCodeInvalidException.class);
            verify(repository, never()).findByCodeHash(any());
        }

        @Test
        @DisplayName("Code déjà consommé → refus")
        void alreadyRedeemedCodeIsRefused() {
            String clear = "FORGE-AB2C-3D4E";
            AccessCode used = storedCode(clear);
            used.setRedeemedAt(NOW.minusHours(2));
            used.setRedeemedByUserId(UUID.randomUUID());
            when(repository.findByCodeHash(AccessCodeSecret.hash(clear))).thenReturn(Optional.of(used));

            assertThatThrownBy(() -> service.redeem(userId, "u@example.com", clear))
                    .isInstanceOf(AccessCodeAlreadyUsedException.class);
        }

        @Test
        @DisplayName("Course : zéro ligne modifiée vaut « déjà consommé », pas une panne")
        void aLostRaceIsReportedAsAlreadyUsed() {
            String clear = "FORGE-AB2C-3D4E";
            when(repository.findByCodeHash(AccessCodeSecret.hash(clear)))
                    .thenReturn(Optional.of(storedCode(clear)));
            when(repository.consume(any(), any(), any(), any(), any(), any())).thenReturn(0);

            assertThatThrownBy(() -> service.redeem(userId, "u@example.com", clear))
                    .isInstanceOf(AccessCodeAlreadyUsedException.class);
        }

        @Test
        @DisplayName("Code périmé avant d'avoir servi → refus")
        void expiredCodeIsRefused() {
            String clear = "FORGE-AB2C-3D4E";
            AccessCode stale = storedCode(clear);
            stale.setValidUntil(NOW.minusDays(1));
            when(repository.findByCodeHash(AccessCodeSecret.hash(clear))).thenReturn(Optional.of(stale));

            assertThatThrownBy(() -> service.redeem(userId, "u@example.com", clear))
                    .isInstanceOf(AccessCodeExpiredException.class);
        }

        @Test
        @DisplayName("Code nominatif : le destinataire passe, un autre compte est refusé")
        void assignedCodeOnlyWorksForItsRecipient() {
            String clear = "FORGE-AB2C-3D4E";
            AccessCode assigned = storedCode(clear);
            assigned.setAssignedEmail("prospect@example.com");
            when(repository.findByCodeHash(AccessCodeSecret.hash(clear)))
                    .thenReturn(Optional.of(assigned));
            when(repository.consume(any(), any(), any(), any(), any(), any())).thenReturn(1);

            assertThatThrownBy(() -> service.redeem(userId, "autre@example.com", clear))
                    .isInstanceOf(AccessCodeNotForAccountException.class);

            // La casse de l'e-mail ne doit pas décider d'un refus.
            assertThat(service.redeem(userId, "Prospect@Example.com", clear)).isNotNull();
        }

        @Test
        @DisplayName("Pas de cumul : un droit déjà en cours refuse le second code")
        void aSecondCodeIsRefusedWhileAGrantIsLive() {
            String clear = "FORGE-AB2C-3D4E";
            when(repository.findByCodeHash(AccessCodeSecret.hash(clear)))
                    .thenReturn(Optional.of(storedCode(clear)));
            when(accessGrantService.activeGrant(userId)).thenReturn(Optional.of(
                    new AccessGrant(PlanCode.GOLD, NOW.plusHours(3), PlanCode.SOLO, "démo")));

            assertThatThrownBy(() -> service.redeem(userId, "u@example.com", clear))
                    .isInstanceOf(AccessCodeAlreadyGrantedException.class);
            verify(repository, never()).consume(any(), any(), any(), any(), any(), any());
        }
    }
}
