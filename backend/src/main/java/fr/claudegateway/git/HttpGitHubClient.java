package fr.claudegateway.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Implémentation {@link GitHubClient} appelant {@code GET /user} sur l'API GitHub avec le jeton en
 * en-tête {@code Authorization}. Seul point du code couplé à GitHub.
 *
 * <p>Le jeton n'est <b>jamais</b> journalisé, et aucune réponse brute de GitHub n'est renvoyée au
 * client : les erreurs sont traduites en {@link InvalidGitTokenException} (jeton refusé) ou
 * {@link GitHubUnavailableException} (panne temporaire), avec des messages métier neutres.</p>
 */
@Component
public class HttpGitHubClient implements GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(HttpGitHubClient.class);

    private static final String API_VERSION_HEADER = "X-GitHub-Api-Version";
    private static final String API_VERSION = "2022-11-28";
    private static final String ACCEPT = "application/vnd.github+json";

    private final RestClient restClient;

    public HttpGitHubClient(GitProperties properties, RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder
                .baseUrl(properties.githubApiUrl())
                .requestFactory(requestFactory(properties))
                .build();
    }

    private static ClientHttpRequestFactory requestFactory(GitProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int millis = (int) Math.min(Integer.MAX_VALUE, properties.timeout().toMillis());
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        return factory;
    }

    @Override
    public GitHubAccount verifyToken(String token) {
        try {
            GitHubUserResponse response = restClient.get()
                    .uri("/user")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HttpHeaders.ACCEPT, ACCEPT)
                    .header(API_VERSION_HEADER, API_VERSION)
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.UNAUTHORIZED.value()
                                    || status.value() == HttpStatus.FORBIDDEN.value(),
                            (request, clientResponse) -> {
                                // Ni le jeton ni le corps de la réponse ne sont journalisés.
                                log.info("Vérification GitHub : jeton refusé (statut {})", clientResponse.getStatusCode().value());
                                throw new InvalidGitTokenException(
                                        "GitHub a refusé ce jeton (invalide, révoqué ou expiré).");
                            })
                    .onStatus(status -> status.isError(),
                            (request, clientResponse) -> {
                                log.warn("Vérification GitHub indisponible (statut {})", clientResponse.getStatusCode().value());
                                throw new GitHubUnavailableException(
                                        "GitHub est momentanément indisponible. Réessayez dans un instant.");
                            })
                    .body(GitHubUserResponse.class);
            return new GitHubAccount(response == null ? null : response.login());
        } catch (RestClientException ex) {
            // Panne réseau / timeout : échec temporaire, jamais confondu avec un jeton refusé.
            log.warn("Vérification GitHub impossible : {}", ex.getClass().getSimpleName());
            throw new GitHubUnavailableException(
                    "GitHub est momentanément injoignable. Réessayez dans un instant.");
        }
    }

    /** Projection minimale de {@code GET /user} : seul le login est retenu. */
    private record GitHubUserResponse(String login) {
    }
}
