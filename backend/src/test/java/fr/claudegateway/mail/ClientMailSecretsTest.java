package fr.claudegateway.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Pas de secret dans un courriel (F-110 / SF-110-02). */
class ClientMailSecretsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "-----BEGIN RSA PRIVATE KEY----- MIIEow... | une clé privée",
            "-----BEGIN OPENSSH PRIVATE KEY----- | une clé privée",
            "clé : AKIAIOSFODNN7EXAMPLE | une clé d'accès AWS",
            "ghp_abcdefghijklmnopqrstuvwxyz0123456789 | un jeton GitHub",
            "xoxb-123456789012-abcdefghij | un jeton Slack",
            "sk_live_abcdefghijklmnop1234 | une clé Stripe",
            "sk-ant-api03-abcdefghijklmnopqrstuvwxyz | une clé d'API Anthropic",
            "sk-proj-abcdefghijklmnopqrstuvwxyz0123456789 | une clé d'API",
            "AIzaSyA1234567890abcdefghijklmnopqrstuv | une clé d'API Google",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U | un jeton JWT",
            "Authorization: Bearer abcdefghijklmnop0123 | un jeton d'authentification",
            "mot de passe : Hunter2024! | un mot de passe",
            "password=SuperSecret99 | un mot de passe",
            "MDP: azerty123 | un mot de passe"})
    void detectsManifestSecrets(String text, String label) {
        assertThat(ClientMailSecrets.find(text.replace("\\n", "\n"))).contains(label);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Compte rendu : le mot de passe oublié se réinitialise depuis le portail.",
            "Procédure de déploiement LZI : 1. terraform plan 2. terraform apply",
            "La clé du sujet MFA est la double authentification.",
            "Relance Julie pour le token de la PKI (ticket 4521).",
            "sk-court"})
    void leavesOrdinaryProseAlone(String text) {
        assertThat(ClientMailSecrets.find(text)).isEmpty();
    }
}
