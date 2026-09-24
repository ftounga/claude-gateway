package fr.claudegateway.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>La garde de la carte des capacités</b> (F-156 / SF-156-01).
 *
 * <p>Une carte qui ment est <b>pire qu'une carte absente</b> : elle ferait conclure « capacité
 * dormante » sur une capacité supprimée, et on chercherait un branchement à réparer là où il n'y a
 * plus rien. Ces tests la tiennent vraie.</p>
 */
class CapabilityMapTest {

    /** La racine du dépôt, depuis le répertoire de travail de Maven ({@code backend/}). */
    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();

    @Test
    @DisplayName("LA GARDE : chaque chemin déclaré existe VRAIMENT dans le dépôt")
    void everyDeclaredPathExists() {
        for (ProductCapability capability : CapabilityMap.capabilities()) {
            assertThat(capability.paths())
                    .as("la capacité « %s » doit dire où elle vit", capability.id())
                    .isNotEmpty();
            for (String path : capability.paths()) {
                assertThat(Files.exists(REPO.resolve(path)))
                        .as("« %s » déclare %s, qui n'existe pas — la carte ment, "
                                + "et le diagnostic conclurait « dormante » sur du vide",
                                capability.id(), path)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("les identifiants sont uniques — un doublon fausserait tout comptage")
    void idsAreUnique() {
        List<String> ids = CapabilityMap.capabilities().stream()
                .map(ProductCapability::id)
                .toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("chaque capacité a AU MOINS un signal — sans lui, on ne saurait jamais si elle se déclenche")
    void everyCapabilityHasASignal() {
        for (ProductCapability capability : CapabilityMap.capabilities()) {
            assertThat(capability.signals())
                    .as("« %s » sans signal : le diagnostic ne pourrait rien en dire", capability.id())
                    .isNotEmpty();
            assertThat(capability.signals())
                    .allSatisfy(signal -> assertThat(signal.value()).isNotBlank());
        }
    }

    @Test
    @DisplayName("chaque capacité dit ce qu'elle ÉVITE et à quelle condition elle s'active")
    void everyCapabilityExplainsItself() {
        for (ProductCapability capability : CapabilityMap.capabilities()) {
            assertThat(capability.name()).as(capability.id()).isNotBlank();
            assertThat(capability.avoids())
                    .as("« %s » doit dire quel gaspillage elle supprime", capability.id())
                    .isNotBlank();
            assertThat(capability.activates())
                    .as("« %s » doit dire à quelle condition elle s'active — c'est ce qui permet "
                            + "de distinguer dormante d'absente", capability.id())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("les capacités de référence du cadrage sont toutes déclarées")
    void theReferenceCapabilitiesAreThere() {
        assertThat(CapabilityMap.capabilities()).extracting(ProductCapability::id)
                .contains("sous-agents", "exploration-parallele", "lecture-seule", "cache-de-prompt",
                        "index-du-depot", "compaction", "plan", "memoire-de-resolutions",
                        "carte-du-poste");
    }

    @Test
    @DisplayName("la carte est lisible par identifiant, et son ordre est stable")
    void theMapIsReadableAndStable() {
        assertThat(CapabilityMap.byId("cache-de-prompt"))
                .get()
                .extracting(ProductCapability::name)
                .isEqualTo("Réutiliser le cache de prompt");
        assertThat(CapabilityMap.byId("inexistante")).isEmpty();

        assertThat(CapabilityMap.capabilities())
                .isEqualTo(CapabilityMap.capabilities()); // même ordre, d'un appel à l'autre
    }
}
