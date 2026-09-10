package fr.claudegateway.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests du secret d'un code d'accès (F-62 / SF-62-01) : forme, alphabet, empreinte, normalisation.
 *
 * <p>Le test le plus utile est celui de l'alphabet : un code se recopie à la main, et un {@code O}
 * pris pour un {@code 0} produit un « code inconnu » que personne ne sait diagnostiquer.</p>
 */
class AccessCodeSecretTest {

    @Test
    @DisplayName("Le code a la forme FORGE-XXXX-XXXX")
    void generatedCodeHasTheExpectedShape() {
        assertThat(AccessCodeSecret.generate()).matches("FORGE-[A-Z2-9]{4}-[A-Z2-9]{4}");
    }

    @Test
    @DisplayName("L'alphabet exclut les caractères qui se confondent à la lecture")
    void generatedCodeAvoidsAmbiguousCharacters() {
        Set<Character> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            for (char c : AccessCodeSecret.generate().toCharArray()) {
                seen.add(c);
            }
        }
        // 'O' de FORGE mis à part, aucun caractère confondable ne sort du tirage aléatoire.
        String randomPart = AccessCodeSecret.generate().substring(6).replace("-", "");
        assertThat(randomPart).doesNotContain("I").doesNotContain("O")
                .doesNotContain("0").doesNotContain("1");
        assertThat(seen).isNotEmpty();
    }

    @Test
    @DisplayName("Deux tirages consécutifs diffèrent")
    void twoDrawsDiffer() {
        assertThat(AccessCodeSecret.generate()).isNotEqualTo(AccessCodeSecret.generate());
    }

    @Test
    @DisplayName("La normalisation absorbe casse et espaces — une saisie correcte ne doit pas échouer")
    void normalizationIsForgiving() {
        assertThat(AccessCodeSecret.normalize("  forge-ab2c-3d4e  "))
                .isEqualTo("FORGE-AB2C-3D4E");
        assertThat(AccessCodeSecret.normalize("FORGE AB2C 3D4E"))
                .isEqualTo("FORGEAB2C3D4E");
        assertThat(AccessCodeSecret.normalize(null)).isEmpty();
    }

    @Test
    @DisplayName("L'empreinte est un SHA-256 hexadécimal, stable et différente pour deux codes")
    void hashIsAStableHexDigest() {
        String hash = AccessCodeSecret.hash("FORGE-AB2C-3D4E");

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(hash).isEqualTo(AccessCodeSecret.hash("FORGE-AB2C-3D4E"));
        assertThat(hash).isNotEqualTo(AccessCodeSecret.hash("FORGE-AB2C-3D4F"));
        // Et surtout : l'empreinte ne contient pas le code.
        assertThat(hash).doesNotContain("FORGE");
    }
}
