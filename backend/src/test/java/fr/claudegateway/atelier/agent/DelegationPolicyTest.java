package fr.claudegateway.atelier.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Vérifie la normalisation de {@link DelegationPolicy} (F-35 / SF-35-01) : il ne doit exister
 * <b>qu'une seule</b> représentation du « pas de délégation », faute de quoi un roster vide pourrait
 * partir chez le fournisseur en se faisant passer pour une délégation active.
 */
class DelegationPolicyTest {

    @Test
    void disabledFlagYieldsTheSingleDisabledRepresentation() {
        assertThat(DelegationPolicy.of(false, 3)).isEqualTo(DelegationPolicy.DISABLED);
        assertThat(DelegationPolicy.of(false, 3).maxSubagents()).isZero();
    }

    @Test
    void anEmptyOrNegativeRosterIsNotADelegation() {
        assertThat(DelegationPolicy.of(true, 0)).isEqualTo(DelegationPolicy.DISABLED);
        assertThat(DelegationPolicy.of(true, -2)).isEqualTo(DelegationPolicy.DISABLED);
    }

    @Test
    void anEnabledPolicyKeepsItsCap() {
        DelegationPolicy policy = DelegationPolicy.of(true, 3);

        assertThat(policy.enabled()).isTrue();
        assertThat(policy.maxSubagents()).isEqualTo(3);
    }
}
