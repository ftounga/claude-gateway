package fr.claudegateway.governance.integrite;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-95 / SF-95-03 — la mémoire des inspections déjà faites.
 *
 * <p>Ce que ces tests protègent : qu'elle <b>économise</b> des allers-retours vers la machine d'un
 * client sans jamais confondre deux comptes ni deux projets — et qu'une écriture <b>nouvelle</b>
 * rende bien l'inspection, sans quoi une correction ne serait jamais vérifiée.</p>
 */
class IntegriteMemoTest {

    private final IntegriteMemo memo = new IntegriteMemo();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID projet = UUID.randomUUID();
    private final UUID autreProjet = UUID.randomUUID();

    @Test
    @DisplayName("une inspection faite est retenue ; une écriture nouvelle la rend à nouveau")
    void rememberedUntilSomethingNewIsWritten() {
        assertThat(memo.dejaInspecte(alice, projet, List.of("STATE.md"))).isFalse();

        memo.retenir(alice, projet, List.of("STATE.md"));

        assertThat(memo.dejaInspecte(alice, projet, List.of("STATE.md"))).isTrue();
        // Une correction écrit : c'est ce qui doit faire réinspecter.
        assertThat(memo.dejaInspecte(alice, projet, List.of("STATE.md", "acces.md"))).isFalse();
    }

    @Test
    @DisplayName("les écritures s'accumulent : ce qui a déjà été vu reste vu")
    void writesAccumulate() {
        memo.retenir(alice, projet, List.of("STATE.md"));
        memo.retenir(alice, projet, List.of("acces.md"));

        assertThat(memo.dejaInspecte(alice, projet, List.of("STATE.md", "acces.md"))).isTrue();
    }

    @Test
    @DisplayName("une entrée ne répond jamais pour un autre compte ni pour un autre projet")
    void neverAnswersForSomeoneElse() {
        memo.retenir(alice, projet, List.of("STATE.md"));

        assertThat(memo.dejaInspecte(bob, projet, List.of("STATE.md"))).isFalse();
        assertThat(memo.dejaInspecte(alice, autreProjet, List.of("STATE.md"))).isFalse();
    }

    @Test
    @DisplayName("des entrées vides ou nulles ne retiennent rien et ne lèvent pas")
    void emptyInputsAreIgnored() {
        memo.retenir(null, projet, List.of("STATE.md"));
        memo.retenir(alice, projet, List.of());

        assertThat(memo.dejaInspecte(alice, projet, List.of("STATE.md"))).isFalse();
        assertThat(memo.dejaInspecte(alice, projet, null)).isFalse();
    }

    @Test
    @DisplayName("la mémoire est bornée : au-delà, la plus ancienne sort")
    void theMemoryIsBounded() {
        for (int i = 0; i < IntegriteMemo.MAX_ENTRIES + 1; i++) {
            memo.retenir(alice, UUID.randomUUID(), List.of("STATE.md"));
        }
        memo.retenir(alice, projet, List.of("STATE.md"));

        assertThat(memo.dejaInspecte(alice, projet, List.of("STATE.md"))).isTrue();

        memo.clear();

        assertThat(memo.dejaInspecte(alice, projet, List.of("STATE.md"))).isFalse();
    }
}
