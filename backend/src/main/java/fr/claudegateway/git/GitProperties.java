package fr.claudegateway.git;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration de l'accès Git/GitHub (F-31). Aucune valeur secrète ici : le jeton appartient à
 * l'utilisateur, il est chiffré en base et n'est jamais configuré côté serveur.
 *
 * @param githubApiUrl   base de l'API GitHub (surchargeable pour les tests / GitHub Enterprise)
 * @param timeout        délai de connexion et de lecture des appels à GitHub
 * @param maxTreeEntries plafond du nombre de fichiers listés dans l'arborescence d'un dépôt
 *                       (défaut {@code 5000}) : au-delà la liste est tronquée, et le dit
 * @param maxFileBytes   taille maximale d'un fichier lu depuis un dépôt (défaut 1 Mo) : au-delà, refus
 *                       explicite plutôt qu'un contenu tronqué présenté comme complet
 */
@ConfigurationProperties(prefix = "app.git")
public record GitProperties(String githubApiUrl, Duration timeout, Integer maxTreeEntries,
        Long maxFileBytes) {

    public GitProperties {
        if (githubApiUrl == null || githubApiUrl.isBlank()) {
            githubApiUrl = "https://api.github.com";
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(10);
        }
        if (maxTreeEntries == null || maxTreeEntries <= 0) {
            maxTreeEntries = 5_000;
        }
        if (maxFileBytes == null || maxFileBytes <= 0) {
            maxFileBytes = 1_048_576L;
        }
    }
}
