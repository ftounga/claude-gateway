package fr.claudegateway.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>La garde de la liste de référence</b> (F-156 / SF-156-04).
 *
 * <p>Une liste qui pointe une capacité disparue ferait conclure « absente » sur une capacité
 * livrée — ou l'inverse. Ces tests la tiennent vraie, comme {@code CapabilityMapTest} tient la
 * carte.</p>
 */
class ParityReferenceTest {

    @Test
    @DisplayName("LA GARDE : toute capacité pointée EXISTE dans la carte")
    void everyPointedCapabilityExists() {
        for (ReferenceCapability reference : ParityReference.references()) {
            if (reference.isCarried()) {
                assertThat(CapabilityMap.byId(reference.capabilityId()))
                        .as("« %s » pointe « %s », qui n'est pas dans la carte — la parité "
                                + "conclurait à tort", reference.id(), reference.capabilityId())
                        .isPresent();
            }
        }
    }

    @Test
    @DisplayName("aucune référence n'est À LA FOIS portée et écartée")
    void noReferenceIsBothCarriedAndExcluded() {
        for (ReferenceCapability reference : ParityReference.references()) {
            assertThat(reference.isCarried() && reference.isExcluded())
                    .as("« %s » ne peut pas être portée ET écartée", reference.id())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("les identifiants sont uniques et chaque référence dit ce qu'elle apporte")
    void referencesAreWellFormed() {
        List<String> ids = ParityReference.references().stream()
                .map(ReferenceCapability::id).toList();
        assertThat(ids).doesNotHaveDuplicates();

        for (ReferenceCapability reference : ParityReference.references()) {
            assertThat(reference.name()).as(reference.id()).isNotBlank();
            assertThat(reference.gives())
                    .as("« %s » doit dire ce qu'elle apporte", reference.id())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("une exclusion porte TOUJOURS sa raison — sans elle, elle passerait pour un manque")
    void everyExclusionCarriesItsReason() {
        for (ReferenceCapability reference : ParityReference.references()) {
            if (reference.capabilityId() == null) {
                assertThat(reference.excludedBecause())
                        .as("« %s » n'est portée par rien : ou elle a une raison, ou c'est un "
                                + "manque réel — mais ce doit être un choix, pas un oubli",
                                reference.id())
                        .isNotNull();
            }
        }
    }

    @Test
    @DisplayName("les capacités de référence du cadrage sont toutes là")
    void theCadrageReferencesAreThere() {
        assertThat(ParityReference.references()).extracting(ReferenceCapability::id)
                .contains("deleguer", "explorer-en-parallele", "lecture-seule", "cache",
                        "indexer", "compacter", "planifier");
    }
}
