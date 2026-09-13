package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-96 / SF-96-02 — le <b>registre des empreintes publiées</b> d'un chemin.
 *
 * <p>C'est la deuxième façon de reconnaître un artefact que personne n'a touché : son contenu est
 * mot pour mot l'un de ceux que le produit a publiés ici. Ce qui est vérifié : l'ordre (les plus
 * récentes d'abord), la borne, et qu'un registre vide se stocke comme <b>rien</b>.</p>
 */
class GovernanceKnownDigestsTest {

    @Test
    @DisplayName("la fusion garde l'ordre des arguments et écarte les doublons")
    void mergeKeepsOrderAndDropsDuplicates() {
        List<String> merged =
                GovernanceKnownDigests.merge(List.of("a", "b"), List.of("b", "c"), List.of("a"));

        assertThat(merged).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("la fusion s'arrête à la borne : ce sont les PLUS RÉCENTES qui survivent")
    void mergeStopsAtTheLimit() {
        List<String> many = java.util.stream.IntStream.range(0, 40).mapToObj(Integer::toString)
                .toList();

        List<String> merged = GovernanceKnownDigests.merge(many);

        assertThat(merged).hasSize(GovernanceKnownDigests.MAX).startsWith("0", "1")
                .doesNotContain("39");
    }

    @Test
    @DisplayName("un registre vide se stocke comme RIEN — une chaîne vide ne dit rien")
    void anEmptyRegisterIsNull() {
        assertThat(GovernanceKnownDigests.join(List.of())).isNull();
        assertThat(GovernanceKnownDigests.join(null)).isNull();
        assertThat(GovernanceKnownDigests.parse(null)).isEmpty();
        assertThat(GovernanceKnownDigests.parse("   ")).isEmpty();
    }

    @Test
    @DisplayName("ce qui est écrit se relit à l'identique, blancs et doublons en moins")
    void roundTrips() {
        String stored = GovernanceKnownDigests.join(List.of("a", "b"));

        assertThat(stored).isEqualTo("a\nb");
        assertThat(GovernanceKnownDigests.parse(" a \n\n b \n a \n")).containsExactly("a", "b");
    }

    @Test
    @DisplayName("la colonne est assez large pour le registre plein")
    void theColumnFitsAFullRegister() {
        // Sans cette garantie, un registre plein serait tronqué à l'écriture — et une empreinte
        // coupée en deux reconnaîtrait n'importe quoi, ou plus rien.
        assertThat(GovernanceKnownDigests.MAX_LENGTH)
                .isGreaterThanOrEqualTo(GovernanceKnownDigests.MAX * (GovernanceDigest.LENGTH + 1));
    }
}
