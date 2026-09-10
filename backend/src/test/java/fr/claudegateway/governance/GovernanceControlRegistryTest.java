package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;

/**
 * F-51 / SF-51-01 — le registre est l'unique liste qu'un paquet a le droit de citer. Un identifiant
 * n'y entre que par un bean du produit : c'est ce qui interdit d'apporter du code par un paquet.
 */
class GovernanceControlRegistryTest {

    @Test
    @DisplayName("un registre vide est un état normal — c'est celui que F-51 livre")
    void emptyRegistryIsFine() {
        GovernanceControlRegistry registry = GovernanceControlRegistry.empty();

        assertThat(registry.all()).isEmpty();
        assertThat(registry.exists("commit-sans-trace")).isFalse();
        assertThat(registry.resolve(List.of("commit-sans-trace"))).isEmpty();
    }

    @Test
    @DisplayName("un contrôle déclaré est retrouvé par son identifiant")
    void findsDeclaredControl() {
        GovernanceControl control = stub("commit-sans-trace");
        GovernanceControlRegistry registry = new GovernanceControlRegistry(List.of(control));

        assertThat(registry.exists("commit-sans-trace")).isTrue();
        assertThat(registry.find("commit-sans-trace")).containsSame(control);
        assertThat(registry.find("inconnu")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
    }

    @Test
    @DisplayName("deux contrôles sous le même identifiant : le premier gagne, l'autre est ignoré")
    void firstDuplicateWins() {
        GovernanceControl first = stub("doublon");
        GovernanceControl second = stub("doublon");

        GovernanceControlRegistry registry = new GovernanceControlRegistry(List.of(first, second));

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.find("doublon")).containsSame(first);
    }

    @Test
    @DisplayName("un contrôle sans identifiant est ignoré, sans faire échouer le démarrage")
    void ignoresControlWithoutId() {
        GovernanceControlRegistry registry =
                new GovernanceControlRegistry(java.util.Arrays.asList(stub("  "), stub("bon"), null));

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.exists("bon")).isTrue();
    }

    @Test
    @DisplayName("la résolution garde l'ordre, supprime les doublons et ignore l'inconnu")
    void resolveKeepsOrderAndIgnoresUnknown() {
        GovernanceControl a = stub("a");
        GovernanceControl b = stub("b");
        GovernanceControlRegistry registry = new GovernanceControlRegistry(List.of(a, b));

        assertThat(registry.resolve(List.of("b", "inconnu", "a", "b")))
                .containsExactly(b, a);
        assertThat(registry.resolve(null)).isEmpty();
    }

    private static GovernanceControl stub(String id) {
        return new GovernanceControl() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public AtelierCheckpointKind kind() {
                return AtelierCheckpointKind.END_OF_TURN;
            }

            @Override
            public String description() {
                return "Contrôle de test.";
            }

            @Override
            public AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context) {
                return AtelierCheckpointVerdict.proceed();
            }
        };
    }
}
