package fr.claudegateway.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.SubscriptionStatus;

/**
 * Tests de la lecture du droit offert (F-62 / SF-62-01).
 *
 * <p>Ces tests figent la propriété qui fait toute la feature : <b>rien n'est exécuté pour que le
 * droit se ferme</b>. On avance l'horloge, et la réponse change — aucune méthode d'expiration n'est
 * appelée, parce qu'il n'y en a pas. C'est la traduction en test de l'exigence « le retour au plan
 * précédent survient même si personne ne se connecte ».</p>
 *
 * <p>Le second axe est la <b>grâce de tour</b> : entre le terme et le terme + grâce, le droit reste
 * ouvert — un tour engagé avant le terme ne doit jamais être coupé en plein milieu.</p>
 */
@ExtendWith(MockitoExtension.class)
class AccessGrantServiceTest {

    @Mock
    private AccessCodeRepository repository;

    private final UUID userId = UUID.randomUUID();

    /** Terme du droit dans le scénario : 2026-09-11T10:00Z. */
    private static final OffsetDateTime TERM =
            OffsetDateTime.of(2026, 9, 11, 10, 0, 0, 0, ZoneOffset.UTC);

    private AccessGrantService serviceAt(OffsetDateTime now) {
        return new AccessGrantService(repository,
                new AccessCodeProperties(24, 30, 15),
                Clock.fixed(now.toInstant(), ZoneOffset.UTC));
    }

    private AccessCode redeemedCode() {
        return AccessCode.builder()
                .id(UUID.randomUUID())
                .codeHash("hash")
                .label("démo prospect")
                .grantedPlanCode(PlanCode.GOLD)
                .durationHours(24)
                .validUntil(TERM.minusDays(29))
                .createdByUserId(UUID.randomUUID())
                .redeemedByUserId(userId)
                .redeemedAt(TERM.minusHours(24))
                .grantedUntil(TERM)
                .previousPlanCode(PlanCode.SOLO)
                .previousStatus(SubscriptionStatus.ACTIVE)
                .build();
    }

    private void givenRedeemedCode() {
        when(repository.findFirstByRedeemedByUserIdOrderByGrantedUntilDesc(userId))
                .thenReturn(Optional.of(redeemedCode()));
    }

    @Test
    @DisplayName("Avant le terme : le droit est ouvert et porte le plan de retour")
    void beforeTermTheGrantIsOpen() {
        givenRedeemedCode();
        AccessGrantService service = serviceAt(TERM.minusHours(1));

        Optional<AccessGrant> grant = service.activeGrant(userId);

        assertThat(grant).isPresent();
        assertThat(grant.get().grantedPlanCode()).isEqualTo(PlanCode.GOLD);
        assertThat(grant.get().grantedUntil()).isEqualTo(TERM);
        assertThat(grant.get().previousPlanCode()).isEqualTo(PlanCode.SOLO);
        assertThat(service.isGrantedWithGrace(userId)).isTrue();
    }

    @Test
    @DisplayName("Une seconde après le terme : l'écran n'annonce plus rien — sans qu'aucun job n'ait tourné")
    void afterTermTheGrantIsGoneWithoutAnythingHavingRun() {
        givenRedeemedCode();
        AccessGrantService service = serviceAt(TERM.plusSeconds(1));

        // Aucune méthode d'expiration n'est appelée ici, et il n'en existe aucune à appeler : c'est
        // le passage du temps, et lui seul, qui referme le droit.
        assertThat(service.activeGrant(userId)).isEmpty();
    }

    @Test
    @DisplayName("Grâce de tour : la porte de la Forge reste ouverte jusqu'au terme + 15 min")
    void withinGraceTheForgeDoorStaysOpen() {
        givenRedeemedCode();
        AccessGrantService service = serviceAt(TERM.plusMinutes(10));

        // Le terme est passé — l'écran ne promet plus rien...
        assertThat(service.activeGrant(userId)).isEmpty();
        // ...mais le tour engagé avant le terme peut aller au bout.
        assertThat(service.isGrantedWithGrace(userId)).isTrue();
    }

    @Test
    @DisplayName("Passé la grâce, la porte se ferme aussi")
    void afterGraceTheDoorCloses() {
        givenRedeemedCode();
        AccessGrantService service = serviceAt(TERM.plusMinutes(16));

        assertThat(service.isGrantedWithGrace(userId)).isFalse();
    }

    @Test
    @DisplayName("Une grâce nulle ferme net au terme")
    void zeroGraceClosesExactlyAtTheTerm() {
        givenRedeemedCode();
        AccessGrantService service = new AccessGrantService(repository,
                new AccessCodeProperties(24, 30, 0),
                Clock.fixed(TERM.plusSeconds(1).toInstant(), ZoneOffset.UTC));

        assertThat(service.isGrantedWithGrace(userId)).isFalse();
    }

    @Test
    @DisplayName("Aucun code consommé : aucun droit")
    void noRedeemedCodeMeansNoGrant() {
        when(repository.findFirstByRedeemedByUserIdOrderByGrantedUntilDesc(userId))
                .thenReturn(Optional.empty());
        AccessGrantService service = serviceAt(TERM.minusHours(1));

        assertThat(service.activeGrant(userId)).isEmpty();
        assertThat(service.isGrantedWithGrace(userId)).isFalse();
    }

    @Test
    @DisplayName("Isolation : la lecture nomme toujours l'utilisateur, et un anonyme n'obtient rien")
    void anonymousCallerGetsNothingAndNoQueryIsMade() {
        AccessGrantService service = serviceAt(TERM.minusHours(1));

        assertThat(service.activeGrant(null)).isEmpty();
        assertThat(service.isGrantedWithGrace(null)).isFalse();
        // Aucune requête n'est même tentée : il n'existe pas de lecture de droit sans user_id.
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .findFirstByRedeemedByUserIdOrderByGrantedUntilDesc(any());
    }
}
