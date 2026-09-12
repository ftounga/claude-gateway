package fr.claudegateway.governance.juge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-94 / SF-94-03 — la mémoire des questions déjà posées.
 *
 * <p>Ce que ces tests protègent : qu'elle <b>économise</b> sans jamais confondre deux comptes ni
 * deux projets, et qu'elle reste <b>bornée</b> — un cache de gouvernance qui grossirait sans fin
 * finirait par coûter plus que ce qu'il économise.</p>
 */
class JugeMemoTest {

    private final JugeMemo memo = new JugeMemo();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID projet = UUID.randomUUID();
    private final UUID autreProjet = UUID.randomUUID();

    @Test
    @DisplayName("Une question posée est retenue ; une question nouvelle ne l'est pas")
    void retientEtDistingue() {
        assertThat(memo.dejaJuge(alice, projet, List.of("STATE.md"))).isFalse();

        memo.retenir(alice, projet, List.of("STATE.md"));

        assertThat(memo.dejaJuge(alice, projet, List.of("STATE.md"))).isTrue();
        assertThat(memo.dejaJuge(alice, projet, List.of("STATE.md", "PLAN-ACTION.md"))).isFalse();
    }

    @Test
    @DisplayName("Les écritures s'accumulent : ce qui a déjà été vu reste vu")
    void accumule() {
        memo.retenir(alice, projet, List.of("STATE.md"));
        memo.retenir(alice, projet, List.of("PLAN-ACTION.md"));

        assertThat(memo.dejaJuge(alice, projet, List.of("STATE.md", "PLAN-ACTION.md"))).isTrue();
    }

    @Test
    @DisplayName("ISOLATION : une entrée ne répond ni pour un autre compte ni pour un autre projet")
    void isolation() {
        memo.retenir(alice, projet, List.of("STATE.md"));

        assertThat(memo.dejaJuge(bob, projet, List.of("STATE.md"))).isFalse();
        assertThat(memo.dejaJuge(alice, autreProjet, List.of("STATE.md"))).isFalse();
    }

    @Test
    @DisplayName("Rien d'exploitable : on ne retient rien, et on ne répond jamais « déjà vu »")
    void videOuNul() {
        memo.retenir(null, projet, List.of("STATE.md"));
        memo.retenir(alice, projet, List.of());

        assertThat(memo.dejaJuge(alice, projet, List.of())).isFalse();
        assertThat(memo.dejaJuge(null, projet, List.of("STATE.md"))).isFalse();
        assertThat(memo.dejaJuge(alice, projet, null)).isFalse();
    }

    @Test
    @DisplayName("Bornée en entrées : la plus ancienne sort")
    void borneeEnEntrees() {
        for (int i = 0; i < JugeMemo.MAX_ENTRIES + 5; i++) {
            memo.retenir(UUID.randomUUID(), UUID.randomUUID(), List.of("f" + i + ".md"));
        }
        memo.retenir(alice, projet, List.of("STATE.md"));

        assertThat(memo.dejaJuge(alice, projet, List.of("STATE.md"))).isTrue();
    }

    @Test
    @DisplayName("Bornée en chemins : au-delà, on n'accumule plus")
    void borneeEnChemins() {
        for (int i = 0; i < JugeMemo.MAX_PATHS + 50; i++) {
            memo.retenir(alice, projet, List.of("fichier-" + i + ".md"));
        }

        assertThat(memo.dejaJuge(alice, projet, List.of("fichier-0.md"))).isTrue();
        assertThat(memo.dejaJuge(alice, projet,
                List.of("fichier-" + (JugeMemo.MAX_PATHS + 49) + ".md"))).isFalse();
    }
}
