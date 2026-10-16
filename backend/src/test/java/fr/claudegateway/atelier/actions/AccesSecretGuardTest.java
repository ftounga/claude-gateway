package fr.claudegateway.atelier.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Aucun secret dans le registre (F-180 / SF-180-04) : les mots de passe et jetons sont refusés, les noms
 * de systèmes et de comptes ordinaires passent — et le refus ne recopie jamais la valeur.
 */
class AccesSecretGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "mot de passe : Azerty123!",
            "password=hunter22",
            "mdp Soleil2026!",
            "token: abcdef123456",
            "jeton = 9f8e7d6c",
            "api_key: xyz-123-abc",
            "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abc",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0",
            "ghp_0123456789abcdefghijABCDEFGHIJ",
            "github_pat_11ABCDEFG0123456789_abcdefghijk",
            "xoxb-123456789012-abcdefghijk",
            "AKIAIOSFODNN7EXAMPLE",
            "sk-ant-api03-abcdefghijklmnopqrstuvwxyz",
            "glpat-abcdefghijklmnopqrst",
            "-----BEGIN RSA PRIVATE KEY-----",
            "q8ZpL2mN7vX4rT9kW3bY6cH1dF5gJ0sA",
    })
    @DisplayName("un secret est refusé")
    void refusesSecrets(String value) {
        assertThat(AccesSecretGuard.looksSecret(value)).as(value).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "VPN Fortinet",
            "Forge CAPFM (prod)",
            "k.dupont",
            "compte de service svc-deploy",
            "arn:aws:iam::123456789012:role/AdministratorAccess",
            "https://forge.example.com/projects/data-platform",
            "remi.dupont@client.fr",
            "mot de passe envoyé par Rémi",
            "Jeton GitLab du projet",
            "Secret Server",
    })
    @DisplayName("un nom de système, de compte, une URL, un ARN passent")
    void acceptsOrdinaryValues(String value) {
        assertThat(AccesSecretGuard.looksSecret(value)).as(value).isFalse();
    }

    @Test
    @DisplayName("le refus ne recopie pas la valeur")
    void refusalDoesNotEchoTheValue() {
        assertThatThrownBy(() -> AccesSecretGuard.check("password=hunter22"))
                .isInstanceOf(InvalidTerminalActionException.class)
                .hasMessageContaining("aucun secret")
                .hasMessageNotContaining("hunter22");
    }
}
