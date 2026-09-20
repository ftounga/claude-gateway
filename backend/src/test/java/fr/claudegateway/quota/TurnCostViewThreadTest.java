package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import fr.claudegateway.auth.AuthenticatedUser;
import fr.claudegateway.user.UserRole;

/**
 * Le coût doit survivre au <b>changement de thread</b> (F-133 / SF-133-09).
 *
 * <p><b>Le bug que ce test fige.</b> Le flux SSE de l'Atelier — le chemin nominal de l'écran —
 * s'exécute sur un thread d'exécuteur, et le {@code SecurityContext} de Spring n'y est <b>pas</b>
 * propagé : c'est d'ailleurs pour cela que le contrôleur capture déjà {@code userId} avant d'y
 * entrer. Une garde d'administration évaluée <b>dans</b> ce thread ne trouve donc personne et
 * répond « pas administrateur », si bien que le montant disparaissait du seul chemin qui compte.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class TurnCostViewThreadTest {

    @Autowired
    private TurnCostView costView;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        executor.shutdownNow();
    }

    @Test
    void theAmountSurvivesTheHandOffToAnotherThread() throws Exception {
        asAdmin();

        // La décision est prise ICI, dans le thread de la requête, où le contexte existe…
        boolean admin = costView.callerIsAdmin();
        // …et le montant se formate LÀ-BAS, dans le thread du flux, où il n'existe plus.
        Future<String> label = executor.submit(onTheStreamThread(admin));

        assertThat(label.get()).isEqualTo("0,92 €");
    }

    @Test
    void anAmountEvaluatedInsideTheStreamThreadWouldBeLost() throws Exception {
        asAdmin();

        // Ce que faisait SF-133-02 : évaluer la garde dans le thread du flux. Le contexte n'y est
        // pas, la garde répond non, et le montant s'évapore — sans la moindre erreur.
        Future<String> label = executor.submit(() -> costView.labelFor(new BigDecimal("1.00")));

        assertThat(label.get()).isNull();
    }

    @Test
    void aNonAdminGetsNothingEvenWithTheFlagPath() throws Exception {
        // La nouvelle porte ne doit pas devenir un contournement : sans la décision, pas de montant.
        Future<String> label = executor.submit(onTheStreamThread(false));

        assertThat(label.get()).isNull();
    }

    private Callable<String> onTheStreamThread(boolean admin) {
        return () -> costView.labelFor(new BigDecimal("1.00"), admin);
    }

    private void asAdmin() {
        AuthenticatedUser principal = new AuthenticatedUser(java.util.UUID.randomUUID(),
                "ntounga@gmail.com", UserRole.ADMIN);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
    }
}
