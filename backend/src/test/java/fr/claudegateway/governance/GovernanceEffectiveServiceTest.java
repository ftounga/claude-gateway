package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.claudegateway.governance.dto.GovernanceEffectiveView.PackageLag;

/** F-177 / SF-177-04 — l'état de dépôt d'un paquet, d'après les empreintes laissées sur le poste. */
class GovernanceEffectiveServiceTest {

    private static GovernanceDepositedFile file(int version) {
        return GovernanceDepositedFile.builder().packageVersion(version).path("f" + version).build();
    }

    @Test
    void neverDeposited() {
        PackageLag lag = GovernanceEffectiveService.lagOf(UUID.randomUUID(), "P", 17, List.of());
        assertThat(lag.state()).isEqualTo("JAMAIS_DEPOSE");
        assertThat(lag.filesMinVersion()).isNull();
    }

    @Test
    void upToDate() {
        PackageLag lag = GovernanceEffectiveService.lagOf(UUID.randomUUID(), "P", 17, List.of(file(17), file(17)));
        assertThat(lag.state()).isEqualTo("A_JOUR");
        assertThat(lag.depositedFiles()).isEqualTo(2);
    }

    @Test
    void lateWithASingleVersion() {
        PackageLag lag = GovernanceEffectiveService.lagOf(UUID.randomUUID(), "P", 17, List.of(file(6)));
        assertThat(lag.state()).isEqualTo("EN_RETARD");
        assertThat(lag.message()).isEqualTo("En retard : fichiers en v6, paquet en v17.");
    }

    @Test
    void lateWhenOneFileIsBehind() {
        PackageLag lag = GovernanceEffectiveService.lagOf(UUID.randomUUID(), "P", 17, List.of(file(17), file(4)));
        assertThat(lag.state()).isEqualTo("EN_RETARD");
        assertThat(lag.filesMinVersion()).isEqualTo(4);
        assertThat(lag.filesMaxVersion()).isEqualTo(17);
    }
}
