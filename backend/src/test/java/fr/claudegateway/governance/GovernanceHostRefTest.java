package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.runner.host.RunnerHostNotFoundException;

/**
 * F-75 / SF-75-01 — la référence d'un poste : deux formes, et deux seulement.
 *
 * <p>Ce test garde une décision de F-71 qu'il serait facile de défaire sans s'en apercevoir : le
 * poste « Hébergé » <b>n'a pas d'identifiant public</b>. Sa clé technique existe en base, elle
 * n'existe pas dans une URL.</p>
 */
class GovernanceHostRefTest {

    @Test
    @DisplayName("le mot réservé désigne le poste « Hébergé », sans identifiant public")
    void hostedWordResolvesToVirtualHost() {
        GovernanceHostRef host = GovernanceHostRef.parse("hosted");

        assertThat(host.hosted()).isTrue();
        assertThat(host.ref()).isEqualTo("hosted");
        assertThat(host.publicId()).isNull();
    }

    @Test
    @DisplayName("un identifiant désigne un poste réel, et se réécrit tel quel")
    void uuidResolvesToRealHost() {
        UUID id = UUID.randomUUID();
        GovernanceHostRef host = GovernanceHostRef.parse(id.toString());

        assertThat(host.hosted()).isFalse();
        assertThat(host.hostId()).isEqualTo(id);
        assertThat(host.ref()).isEqualTo(id.toString());
        assertThat(host.publicId()).isEqualTo(id);
    }

    @Test
    @DisplayName("la clé technique du poste virtuel n'est pas une adresse")
    void reservedKeyIsNotAnAddress() {
        assertThatThrownBy(
                () -> GovernanceHostRef.parse("00000000-0000-0000-0000-000000000000"))
                .isInstanceOf(RunnerHostNotFoundException.class);
    }

    @Test
    @DisplayName("tout le reste est introuvable — un identifiant mal formé ne mérite pas mieux")
    void anythingElseIsNotFound() {
        assertThatThrownBy(() -> GovernanceHostRef.parse("tous"))
                .isInstanceOf(RunnerHostNotFoundException.class);
        assertThatThrownBy(() -> GovernanceHostRef.parse(""))
                .isInstanceOf(RunnerHostNotFoundException.class);
        assertThatThrownBy(() -> GovernanceHostRef.parse(null))
                .isInstanceOf(RunnerHostNotFoundException.class);
    }
}
