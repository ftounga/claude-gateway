package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-96 / SF-96-01 — <b>l'empreinte de ce qu'on a déposé</b>.
 *
 * <p>C'est elle qui répond à la seule question qui autorise une mise à jour : <i>le fichier présent
 * est-il celui qu'on y avait mis ?</i> Deux promesses sont vérifiées ici et nulle part ailleurs :
 * une <b>réécriture des fins de ligne n'est pas une modification</b>, et <b>tout le reste en est
 * une</b>.</p>
 */
class GovernanceDigestTest {

    @Test
    @DisplayName("la même chaîne donne toujours la même empreinte, de 64 caractères")
    void isStableAndHexadecimal() {
        String digest = GovernanceDigest.of("# État\n");

        assertThat(digest).hasSize(GovernanceDigest.LENGTH).matches("[0-9a-f]{64}");
        assertThat(GovernanceDigest.of("# État\n")).isEqualTo(digest);
    }

    @Test
    @DisplayName("des fins de ligne réécrites ne changent pas l'empreinte")
    void lineEndingsAreNormalised() {
        assertThat(GovernanceDigest.of("a\r\nb\r\n")).isEqualTo(GovernanceDigest.of("a\nb\n"));
        assertThat(GovernanceDigest.of("a\rb\r")).isEqualTo(GovernanceDigest.of("a\nb\n"));
        assertThat(GovernanceDigest.sameContent("a\r\nb", "a\nb")).isTrue();
    }

    @Test
    @DisplayName("tout le reste EST une modification — un espace compris")
    void everythingElseIsAModification() {
        assertThat(GovernanceDigest.of("a\nb\n")).isNotEqualTo(GovernanceDigest.of("a\nb \n"));
        assertThat(GovernanceDigest.of("a\nb\n")).isNotEqualTo(GovernanceDigest.of("a\nb"));
        assertThat(GovernanceDigest.of("État")).isNotEqualTo(GovernanceDigest.of("Etat"));
        assertThat(GovernanceDigest.sameContent("a", "A")).isFalse();
    }

    @Test
    @DisplayName("le vide a une empreinte ; l'absence n'en a pas")
    void emptyIsNotAbsent() {
        assertThat(GovernanceDigest.of("")).isNotNull().hasSize(GovernanceDigest.LENGTH);
        // Un contenu absent ne vaut PAS un contenu vide : une empreinte inventée autoriserait une
        // écriture sur un fichier qu'on n'a pas su lire.
        assertThat(GovernanceDigest.of(null)).isNull();
        assertThat(GovernanceDigest.sameContent(null, "")).isFalse();
        assertThat(GovernanceDigest.sameContent("", null)).isFalse();
    }
}
