package fr.claudegateway.git;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration de l'accès Git/GitHub (F-31). Aucune valeur secrète ici : le jeton appartient à
 * l'utilisateur, il est chiffré en base et n'est jamais configuré côté serveur.
 *
 * @param githubApiUrl base de l'API GitHub (surchargeable pour les tests / GitHub Enterprise)
 * @param timeout      délai de connexion et de lecture de la vérification du jeton
 */
@ConfigurationProperties(prefix = "app.git")
public record GitProperties(String githubApiUrl, Duration timeout) {

    public GitProperties {
        if (githubApiUrl == null || githubApiUrl.isBlank()) {
            githubApiUrl = "https://api.github.com";
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(10);
        }
    }
}
