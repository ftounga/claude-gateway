package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import fr.claudegateway.admin.AdminForbiddenException;
import fr.claudegateway.admin.AdminService;

/**
 * Ce que l'écran a le droit d'afficher du coût d'un tour (F-133 / SF-133-02).
 *
 * <p>Le test qui compte est {@link #saysNothingToSomeoneWhoIsNotAdmin()} : le montant ne doit pas
 * être <b>masqué</b>, il doit être <b>absent</b>. Masqué, il resterait lisible dans le flux
 * réseau.</p>
 */
class TurnCostViewTest {

    private final AdminService admin = mock(AdminService.class);
    private final AdminService notAdmin = mock(AdminService.class);

    private TurnCostView viewFor(AdminService adminService) {
        return new TurnCostView(adminService,
                new ProviderPricingProperties(null, null, null, null, null, new BigDecimal("0.92")));
    }

    @Test
    void showsTheAmountInEurosToAnAdmin() {
        // 1,00 $ au taux configuré de 0,92.
        assertThat(viewFor(admin).labelFor(new BigDecimal("1.00"))).isEqualTo("0,92 €");
    }

    @Test
    void saysNothingToSomeoneWhoIsNotAdmin() {
        doThrow(new AdminForbiddenException()).when(notAdmin).assertAdmin();

        assertThat(viewFor(notAdmin).labelFor(new BigDecimal("12.34"))).isNull();
    }

    @Test
    void neverShowsZeroEurosForASpendThatHappened() {
        // « 0,00 € » se lit « gratuit ». Un tour à trois millièmes d'euro a bien coûté quelque chose.
        assertThat(viewFor(admin).labelFor(new BigDecimal("0.003"))).isEqualTo("< 0,01 €");
    }

    @Test
    void saysNothingWhenThereIsNothingToSay() {
        assertThat(viewFor(admin).labelFor(null)).isNull();
        assertThat(viewFor(admin).labelFor(BigDecimal.ZERO)).isNull();
        assertThat(viewFor(admin).labelFor(new BigDecimal("-1"))).isNull();
    }

    @Test
    void usesTheFrenchDecimalComma() {
        assertThat(viewFor(admin).labelFor(new BigDecimal("10.00"))).isEqualTo("9,20 €");
    }

    @Test
    void neverBreaksTheTurnWhenSomethingGoesWrong() {
        // Le tour est déjà rendu quand cette méthode s'exécute : un problème d'affichage de coût ne
        // peut pas le faire échouer.
        AdminService broken = mock(AdminService.class);
        doThrow(new IllegalStateException("contexte de sécurité indisponible"))
                .when(broken).assertAdmin();

        assertThatCode(() -> viewFor(broken).labelFor(new BigDecimal("1.00")))
                .doesNotThrowAnyException();
        assertThat(viewFor(broken).labelFor(new BigDecimal("1.00"))).isNull();
    }
}
