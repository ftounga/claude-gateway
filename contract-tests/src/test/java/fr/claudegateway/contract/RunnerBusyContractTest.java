package fr.claudegateway.contract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.AtelierChatService;
import fr.claudegateway.runner.BashTool;

/**
 * Poste occupé (F-188 / SF-188-02) : la gateway reconnaît le refus « une commande est déjà en cours »
 * à son TEXTE — c'est ce qui la rend compatible avec les runners déjà installés. Si le runner change
 * ce texte sans elle, l'attente ne se déclenche plus et l'agent recommence à relancer en boucle.
 */
class RunnerBusyContractTest {

    @Test
    @DisplayName("le texte « occupé » du runner est celui qu'attend la gateway")
    void leTexteOccupeEstLeMemeDesDeuxCotes() {
        assertThat(BashTool.BUSY_MESSAGE).isEqualTo(AtelierChatService.RUNNER_BUSY_MESSAGE);
    }
}
