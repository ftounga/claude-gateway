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
    @DisplayName("LA GARDE DES TÉMOINS : chaque fragment déclaré se trouve VRAIMENT dans son fichier")
    void everyWiringFragmentIsActuallyThere() throws Exception {
        for (ProductCapability capability : CapabilityMap.capabilities()) {
            for (ProductCapability.Wiring wiring : capability.wirings()) {
                Path file = REPO.resolve(wiring.path());
                assertThat(Files.exists(file))
                        .as("« %s » : le témoin pointe %s, qui n'existe pas",
                                capability.id(), wiring.path())
                        .isTrue();
                assertThat(Files.readString(file))
                        .as("« %s » : le fragment « %s » est ABSENT de %s — la carte mentirait, et "
                                + "le diagnostic conclurait « débranchée » sur une capacité qui marche",
                                capability.id(), wiring.fragment(), wiring.path())
                        .contains(wiring.fragment());
            }
        }
    }

    @Test
    @DisplayName("un témoin pointe l'un des CHEMINS de sa capacité — pas ailleurs")
    void everyWiringPointsInsideItsCapability() {
        for (ProductCapability capability : CapabilityMap.capabilities()) {
            for (ProductCapability.Wiring wiring : capability.wirings()) {
                assertThat(capability.paths())
                        .as("« %s » : le témoin pointe %s, qui n'est pas un chemin de cette "
                                + "capacité — il échapperait à la garde des chemins",
                                capability.id(), wiring.path())
                        .contains(wiring.path());
            }
        }
    }

    @Test
    @DisplayName("un témoin dit toujours CE QU'IL PROUVE — un fragment de code seul est illisible")
    void everyWiringExplainsItself() {
        for (ProductCapability capability : CapabilityMap.capabilities()) {
            for (ProductCapability.Wiring wiring : capability.wirings()) {
                assertThat(wiring.fragment()).as(capability.id()).isNotBlank();
                assertThat(wiring.proves())
                        .as("« %s » : un fragment sans explication ne se lit pas dans un rapport",
                                capability.id())
                        .isNotBlank();
            }
        }
    }

    @Test
    @DisplayName("les capacités dont le branchement est vérifiable déclarent leur témoin")
    void theVerifiableOnesDeclareTheirWiring() {
        assertThat(CapabilityMap.capabilities().stream()
                .filter(c -> !c.wirings().isEmpty())
                .map(ProductCapability::id))
                .contains("cache-de-prompt", "index-du-depot", "compaction", "plan",
                        "memoire-de-resolutions", "carte-du-poste");
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
    @DisplayName("chaque capacité est JUGEABLE : un signal, ou à défaut un témoin de branchement")
    void everyCapabilityCanBeJudged() {
        for (ProductCapability capability : CapabilityMap.capabilities()) {
            // F-161 / SF-161-05 : une capacité peut n'avoir AUCUN signal — sa réussite est alors
            // un événement qui n'a pas lieu (la porte du runner, le journal des ruptures). Mais
            // sans signal NI témoin, on ne pourrait rien en dire du tout : la déclarer
            // n'ajouterait que du bruit au rapport.
            assertThat(capability.signals().isEmpty() && capability.wirings().isEmpty())
                    .as("« %s » n'a ni signal ni témoin : le diagnostic ne pourrait strictement "
                            + "rien en dire", capability.id())
                    .isFalse();
            assertThat(capability.signals())
                    .allSatisfy(signal -> assertThat(signal.value()).isNotBlank());
        }
    }

    @Test
    @DisplayName("F-161 : les capacités non mesurables sont CELLES-LÀ, et elles ont leurs témoins")
    void theUnmeasurableOnesAreKnownAndWired() {
        assertThat(CapabilityMap.capabilities().stream()
                .filter(c -> !c.isMeasurable())
                .map(ProductCapability::id))
                .as("une capacité sans signal est une décision, pas un oubli : elle se déclare ici")
                .containsExactlyInAnyOrder("porte-du-runner", "journal-des-ruptures");

        for (ProductCapability capability : CapabilityMap.capabilities()) {
            if (!capability.isMeasurable()) {
                assertThat(capability.wirings())
                        .as("« %s » ne peut être jugée que par ses témoins", capability.id())
                        .isNotEmpty();
            }
        }
    }

    @Test
    @DisplayName("F-161 : la porte est jugée sur son SITE D'APPEL, pas seulement sur son code")
    void theDoorIsJudgedOnItsCallSite() {
        ProductCapability door = CapabilityMap.byId("porte-du-runner").orElseThrow();

        assertThat(door.wirings()).hasSize(3);
        assertThat(door.wirings()).extracting(ProductCapability.Wiring::path)
                .as("sans le site d'appel, une porte supprimée de la boucle resterait invisible")
                .contains("backend/src/main/java/fr/claudegateway/atelier/AtelierChatService.java");
    }

    @Test
    @DisplayName("F-161 : le journal n'a PAS de signal de table — une table vide y est une bonne nouvelle")
    void theDisconnectJournalHasNoTableSignal() {
        assertThat(CapabilityMap.byId("journal-des-ruptures").orElseThrow().signals())
                .as("`repo_index_paths` vide = jamais amorcé ; `runner_disconnects` vide = rien "
                        + "n'a cassé. La même règle sur les deux dirait une bêtise.")
                .isEmpty();
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
                        "carte-du-poste",
                        // F-161 §2 : « il faudra les y ajouter » — la promesse est tenue ici.
                        "porte-du-runner", "journal-des-ruptures");
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
