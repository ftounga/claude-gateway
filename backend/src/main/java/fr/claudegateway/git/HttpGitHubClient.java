package fr.claudegateway.git;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

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

    @Override
    public GitHubRepository getRepository(String token, String owner, String repo) {
        GitHubRepoResponse response = get(
                builder -> builder.path("/repos/{owner}/{repo}").build(owner, repo),
                token, GitHubRepoResponse.class, "résolution du dépôt");
        if (response == null || response.defaultBranch() == null || response.defaultBranch().isBlank()) {
            // Un dépôt sans branche par défaut est un dépôt vide : rien à monter, rien à comparer.
            throw new InvalidGitRepositoryException(
                    "Ce dépôt est vide : il n'a pas encore de branche à ouvrir.");
        }
        String fullName = response.fullName() == null || response.fullName().isBlank()
                ? owner + "/" + repo
                : response.fullName();
        return new GitHubRepository(fullName, response.defaultBranch());
    }

    @Override
    public GitTreeListing listTree(String token, String owner, String repo, String ref, int maxEntries) {
        // `ref`, `owner` et `repo` sont validés en amont sur un alphabet sûr pour une URL : ils sont
        // insérés littéralement, faute de quoi le « / » d'une branche `feat/x` serait encodé et la
        // branche introuvable.
        GitTreeResponse response = get(
                builder -> builder.path("/repos/{owner}/{repo}/git/trees/" + ref)
                        .queryParam("recursive", "1")
                        .build(owner, repo),
                token, GitTreeResponse.class, "arborescence du dépôt");

        List<String> paths = new ArrayList<>();
        boolean truncated = response != null && Boolean.TRUE.equals(response.truncated());
        if (response != null && response.tree() != null) {
            for (GitTreeEntry entry : response.tree()) {
                if (!"blob".equals(entry.type()) || entry.path() == null || entry.path().isBlank()) {
                    continue; // dossiers et sous-modules : rien à ouvrir
                }
                if (paths.size() >= maxEntries) {
                    // Notre propre plafond : la liste devient partielle, et doit le dire.
                    truncated = true;
                    break;
                }
                paths.add(entry.path());
            }
        }
        return new GitTreeListing(List.copyOf(paths), truncated);
    }

    @Override
    public String readFile(String token, String owner, String repo, String ref, String path, long maxBytes) {
        GitContentResponse response = get(
                builder -> builder.path("/repos/{owner}/{repo}/contents")
                        .pathSegment(path.split("/"))
                        .queryParam("ref", ref)
                        .build(owner, repo),
                token, GitContentResponse.class, "lecture d'un fichier du dépôt");

        if (response == null || !"file".equals(response.type())) {
            throw new InvalidGitRepositoryException("Fichier introuvable sur cette branche.");
        }
        if (response.size() != null && response.size() > maxBytes) {
            throw new GitFileNotReadableException(
                    "Ce fichier est trop volumineux pour être affiché ici.");
        }
        // Au-delà de sa propre limite, l'API renvoie la métadonnée sans le contenu : servir une chaîne
        // vide la présenterait comme un fichier vide.
        if (response.content() == null || response.content().isBlank()) {
            throw new GitFileNotReadableException(
                    "Ce fichier est trop volumineux pour être affiché ici.");
        }
        byte[] bytes = decode(response.content());
        if (bytes.length > maxBytes) {
            throw new GitFileNotReadableException(
                    "Ce fichier est trop volumineux pour être affiché ici.");
        }
        if (isBinary(bytes)) {
            throw new GitFileNotReadableException("Ce fichier est binaire : il n'est pas affichable.");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public boolean branchExists(String token, String owner, String repo, String branch) {
        try {
            get(builder -> builder.path("/repos/{owner}/{repo}/branches/" + branch).build(owner, repo),
                    token, GitBranchResponse.class, "vérification de la branche poussée");
            return true;
        } catch (InvalidGitRepositoryException absent) {
            // 404 : la branche n'existe pas. Ce n'est pas une panne — c'est la réponse à la question.
            return false;
        }
    }

    @Override
    public java.util.Optional<GitPullRequest> findOpenPullRequest(String token, String owner, String repo,
            String branch) {
        // `head` se qualifie du propriétaire du dépôt : sans lui, GitHub ignore le filtre et
        // renverrait la première PR ouverte venue — on annoncerait alors la PR de quelqu'un d'autre.
        GitPullRequestResponse[] found = get(builder -> builder
                        .path("/repos/{owner}/{repo}/pulls")
                        .queryParam("head", owner + ":" + branch)
                        .queryParam("state", "open")
                        .queryParam("per_page", 1)
                        .build(owner, repo),
                token, GitPullRequestResponse[].class, "vérification de la pull request");
        if (found == null || found.length == 0 || found[0].number() == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new GitPullRequest(found[0].number(), found[0].htmlUrl()));
    }

    /** Décode le base64 de l'API (qui insère des retours à la ligne) ; contenu illisible ⇒ refus net. */
    private static byte[] decode(String content) {
        try {
            return Base64.getMimeDecoder().decode(content);
        } catch (IllegalArgumentException ex) {
            throw new GitFileNotReadableException("Ce fichier n'est pas affichable.");
        }
    }

    /**
     * Détecte un contenu binaire par la présence d'un octet nul — le critère qu'utilise git lui-même.
     * Décoder du binaire en UTF-8 produirait un charabia présenté comme du code source.
     */
    private static boolean isBinary(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Appel {@code GET} authentifié sur l'API GitHub, avec la traduction d'erreurs commune :
     * {@code 401/403} ⇒ jeton refusé, {@code 404} ⇒ ressource introuvable <b>ou</b> hors de portée du
     * jeton (GitHub ne distingue pas, et nous non plus), toute autre erreur ⇒ indisponibilité
     * temporaire. Ni le jeton ni le corps de la réponse ne sont journalisés.
     *
     * @param <T>       type de la projection attendue
     * @param uri       construction du chemin appelé (segments encodés par le {@code UriBuilder})
     * @param token     jeton en clair (jamais journalisé)
     * @param type      classe de la projection
     * @param operation libellé de l'opération, pour les journaux (jamais de secret)
     * @return le corps désérialisé, éventuellement {@code null} si GitHub répond sans corps
     */
    private <T> T get(Function<UriBuilder, URI> uri, String token, Class<T> type, String operation) {
        try {
            return restClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HttpHeaders.ACCEPT, ACCEPT)
                    .header(API_VERSION_HEADER, API_VERSION)
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.UNAUTHORIZED.value()
                                    || status.value() == HttpStatus.FORBIDDEN.value(),
                            (request, clientResponse) -> {
                                log.info("GitHub ({}) : jeton refusé (statut {})", operation,
                                        clientResponse.getStatusCode().value());
                                throw new InvalidGitTokenException(
                                        "GitHub a refusé ce jeton (invalide, révoqué ou expiré).");
                            })
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(),
                            (request, clientResponse) -> {
                                log.info("GitHub ({}) : ressource introuvable ou hors de portée du jeton",
                                        operation);
                                throw new InvalidGitRepositoryException(
                                        "Dépôt, branche ou fichier introuvable, ou hors de portée de votre jeton GitHub.");
                            })
                    .onStatus(status -> status.isError(),
                            (request, clientResponse) -> {
                                log.warn("GitHub ({}) indisponible (statut {})", operation,
                                        clientResponse.getStatusCode().value());
                                throw new GitHubUnavailableException(
                                        "GitHub est momentanément indisponible. Réessayez dans un instant.");
                            })
                    .body(type);
        } catch (RestClientException ex) {
            log.warn("GitHub ({}) injoignable : {}", operation, ex.getClass().getSimpleName());
            throw new GitHubUnavailableException(
                    "GitHub est momentanément injoignable. Réessayez dans un instant.");
        }
    }

    /** Projection minimale de {@code GET /user} : seul le login est retenu. */
    private record GitHubUserResponse(String login) {
    }

    /** Projection minimale de {@code GET /repos/{owner}/{repo}/git/trees/{ref}}. */
    private record GitTreeResponse(List<GitTreeEntry> tree, Boolean truncated) {
    }

    /** Entrée d'arborescence : seuls le type ({@code blob}/{@code tree}) et le chemin sont utiles. */
    private record GitTreeEntry(String path, String type) {
    }

    /** Projection minimale de {@code GET /repos/{owner}/{repo}/contents/{path}}. */
    private record GitContentResponse(String type, String content, Long size) {
    }

    /** Projection minimale de {@code GET /repos/{owner}/{repo}/branches/{branch}} : seul le nom sert. */
    private record GitBranchResponse(String name) {
    }

    /**
     * Projection minimale de {@code GET /repos/{owner}/{repo}/pulls} : numéro et URL publique.
     * {@code number} est boxé pour distinguer un champ absent d'un zéro.
     */
    private record GitPullRequestResponse(Integer number,
            @com.fasterxml.jackson.annotation.JsonProperty("html_url") String htmlUrl) {
    }

    /** Projection minimale de {@code GET /repos/{owner}/{repo}}. */
    private record GitHubRepoResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("full_name") String fullName,
            @com.fasterxml.jackson.annotation.JsonProperty("default_branch") String defaultBranch) {
    }
}
