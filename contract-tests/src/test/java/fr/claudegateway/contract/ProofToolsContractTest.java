package fr.claudegateway.contract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.proof.ProofToolCatalog;
import fr.claudegateway.runner.ProofRun;

/**
 * Les captures de preuve (F-212) : l'outil que la gateway émet, la capacité qu'elle exige et la borne
 * de commandes sont ceux que le runner sert et annonce. Une dérive ferait dire « mettez à jour le
 * poste » à un poste à jour, ou émettre un appel que le runner refuse.
 */
class ProofToolsContractTest {

    @Test
    @DisplayName("outil, capacité et borne identiques des deux côtés")
    void lesNomsSontLesMemes() {
        assertThat(ProofToolCatalog.RUNNER_TOOL).isEqualTo(ProofRun.TOOL);
        assertThat(ProofToolCatalog.CAPABILITY).isEqualTo(ProofRun.CAPABILITY);
        assertThat(ProofToolCatalog.MAX_COMMANDS).isEqualTo(ProofRun.MAX_COMMANDS);
    }
}
