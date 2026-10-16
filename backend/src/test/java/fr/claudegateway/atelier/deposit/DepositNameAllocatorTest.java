package fr.claudegateway.atelier.deposit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Nom final d'un dépôt (F-201 / SF-201-01) : captures horodatées, suffixes, jamais d'écrasement. */
class DepositNameAllocatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 10, 5, 9);

    @Test
    void uneCaptureGeneriqueRecoitUnNomHorodate() {
        assertThat(DepositNameAllocator.allocate("image.png", Set.of(), NOW))
                .isEqualTo("capture-20261016-100509.png");
        assertThat(DepositNameAllocator.allocate("image (3).png", Set.of(), NOW))
                .isEqualTo("capture-20261016-100509.png");
        assertThat(DepositNameAllocator.allocate("IMAGE.JPEG", Set.of(), NOW))
                .isEqualTo("capture-20261016-100509.jpeg");
        assertThat(DepositNameAllocator.allocate("blob", Set.of(), NOW))
                .isEqualTo("capture-20261016-100509.png");
    }

    @Test
    void unNomLibreEstConserve() {
        assertThat(DepositNameAllocator.allocate("schema-archi.png", Set.of(), NOW)).isEqualTo("schema-archi.png");
        assertThat(DepositNameAllocator.allocate("Makefile", Set.of(), NOW)).isEqualTo("Makefile");
        assertThat(DepositNameAllocator.isGeneric("mon image.png")).isFalse();
    }

    @Test
    void unNomPrisRecoitUnSuffixeCroissantSansCasse() {
        Set<String> taken = new HashSet<>(Set.of("rapport.pdf", "rapport-2.pdf"));
        assertThat(DepositNameAllocator.allocate("Rapport.pdf", taken, NOW)).isEqualTo("Rapport-3.pdf");
        assertThat(DepositNameAllocator.allocate(".env", Set.of(".env"), NOW)).isEqualTo(".env-2");
        assertThat(DepositNameAllocator.allocate("image.png", Set.of("capture-20261016-100509.png"), NOW))
                .isEqualTo("capture-20261016-100509-2.png");
    }

    @Test
    void leNomFinalTientEn255Caracteres() {
        String longName = "a".repeat(251) + ".pdf"; // 255
        String allocated = DepositNameAllocator.allocate(longName, Set.of(longName), NOW);
        assertThat(allocated).hasSize(255).endsWith("-2.pdf");
    }
}
