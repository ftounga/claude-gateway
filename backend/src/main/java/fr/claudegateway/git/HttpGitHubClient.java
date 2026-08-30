package fr.claudegateway.git;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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

    @Override
    public List<String> listBranches(String token, String owner, String repo) {
        // 100 branches par page : au-delà, l'utilisateur cherchera par le nom plutôt que par la liste.
        GitBranchResponse[] branches = get(builder -> builder
                        .path("/repos/{owner}/{repo}/branches")
                        .queryParam("per_page", 100)
                        .build(owner, repo),
                token, GitBranchResponse[].class, "liste des branches");
        if (branches == null) {
            return List.of();
        }
        return java.util.Arrays.stream(branches)
                .map(GitBranchResponse::name)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
    }

    @Override
    public void createBranch(String token, String owner, String repo, String fromBranch, String newBranch) {
        GitRefResponse start = get(builder -> builder
                        .path("/repos/{owner}/{repo}/git/ref/heads/" + fromBranch).build(owner, repo),
                token, GitRefResponse.class, "lecture de la branche de départ");
        if (start == null || start.object() == null || start.object().sha() == null) {
            throw new InvalidGitRepositoryException("Branche de départ introuvable sur ce dépôt.");
        }
        post(builder -> builder.path("/repos/{owner}/{repo}/git/refs").build(owner, repo),
                token, Map.of("ref", "refs/heads/" + newBranch, "sha", start.object().sha()),
                GitShaResponse.class, "création de la branche");
    }

    @Override
    public GitCommitResult commitFiles(String token, String owner, String repo, String baseBranch,
            String branch, String message, List<GitFileEdit> files) {
        // 1. Point de départ : la tête de la branche cible si elle existe, sinon celle de la base.
        //    C'est ce qui rend l'opération idempotente à la création comme à la mise à jour.
        boolean exists = branchExists(token, owner, repo, branch);
        String startRef = exists ? branch : baseBranch;
        GitRefResponse start = get(builder -> builder
                        .path("/repos/{owner}/{repo}/git/ref/heads/" + startRef).build(owner, repo),
                token, GitRefResponse.class, "lecture de la tête de branche");
        if (start == null || start.object() == null || start.object().sha() == null) {
            throw new InvalidGitRepositoryException("Branche de départ introuvable sur ce dépôt.");
        }
        String baseSha = start.object().sha();

        // 2. Un blob par fichier, puis UN arbre, puis UN commit : l'atomicité tient à ce que le
        //    commit ne référence qu'un seul arbre, quelle que soit la quantité de fichiers.
        List<Map<String, Object>> entries = new ArrayList<>();
        for (GitFileEdit file : files) {
            GitShaResponse blob = post(builder -> builder.path("/repos/{owner}/{repo}/git/blobs").build(owner, repo),
                    token, Map.of("content", file.content(), "encoding", "utf-8"),
                    GitShaResponse.class, "création du contenu");
            entries.add(Map.of("path", file.path(), "mode", "100644", "type", "blob",
                    "sha", requireSha(blob, "contenu")));
        }
        GitShaResponse tree = post(builder -> builder.path("/repos/{owner}/{repo}/git/trees").build(owner, repo),
                token, Map.of("base_tree", baseSha, "tree", entries),
                GitShaResponse.class, "création de l'arborescence");
        GitShaResponse commit = post(builder -> builder.path("/repos/{owner}/{repo}/git/commits").build(owner, repo),
                token, Map.of("message", message, "tree", requireSha(tree, "arborescence"),
                        "parents", List.of(baseSha)),
                GitShaResponse.class, "création du commit");
        String commitSha = requireSha(commit, "commit");

        // 3. Déplacer la référence. Jamais de `force` : si la branche a bougé entre-temps, GitHub
        //    refuse et l'échec remonte — écraser le travail d'un autre serait pire que l'erreur.
        if (exists) {
            patch(builder -> builder.path("/repos/{owner}/{repo}/git/refs/heads/" + branch).build(owner, repo),
                    token, Map.of("sha", commitSha, "force", false), "mise à jour de la branche");
        } else {
            post(builder -> builder.path("/repos/{owner}/{repo}/git/refs").build(owner, repo),
                    token, Map.of("ref", "refs/heads/" + branch, "sha", commitSha),
                    GitShaResponse.class, "création de la branche");
        }
        return new GitCommitResult(branch, commitSha, !exists);
    }

    private static String requireSha(GitShaResponse response, String what) {
        if (response == null || response.sha() == null || response.sha().isBlank()) {
            throw new GitHubUnavailableException("GitHub n'a pas renvoyé l'identifiant du " + what + ".");
        }
        return response.sha();
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

    /** {@code POST} authentifié, avec la même traduction d'erreurs que {@link #get}. */
    private <T> T post(Function<UriBuilder, URI> uri, String token, Object body, Class<T> type,
            String operation) {
        return send(HttpMethod.POST, uri, token, body, type, operation);
    }

    /** {@code PATCH} authentifié (mise à jour d'une référence de branche). */
    private void patch(Function<UriBuilder, URI> uri, String token, Object body, String operation) {
        send(HttpMethod.PATCH, uri, token, body, GitShaResponse.class, operation);
    }

    private <T> T send(HttpMethod method, Function<UriBuilder, URI> uri, String token, Object body,
            Class<T> type, String operation) {
        try {
            return restClient.method(method)
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HttpHeaders.ACCEPT, ACCEPT)
                    .header(API_VERSION_HEADER, API_VERSION)
                    .body(body)
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.UNAUTHORIZED.value()
                                    || status.value() == HttpStatus.FORBIDDEN.value(),
                            (request, clientResponse) -> {
                                // 403 sur une écriture = jeton sans `Contents: Read and write`. Le
                                // dire ainsi évite de renvoyer l'utilisateur vers un jeton « invalide »
                                // qui fonctionne pourtant très bien en lecture.
                                log.info("GitHub ({}) : écriture refusée (statut {})", operation,
                                        clientResponse.getStatusCode().value());
                                throw new InvalidGitTokenException(
                                        "GitHub a refusé l'écriture : votre jeton n'a pas le droit "
                                                + "« Contents: Read and write » sur ce dépôt.");
                            })
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(),
                            (request, clientResponse) -> {
                                log.info("GitHub ({}) : ressource introuvable", operation);
                                throw new InvalidGitRepositoryException(
                                        "Dépôt ou branche introuvable, ou hors de portée de votre jeton GitHub.");
                            })
                    .onStatus(status -> status.isError(),
                            (request, clientResponse) -> {
                                log.warn("GitHub ({}) en échec (statut {})", operation,
                                        clientResponse.getStatusCode().value());
                                throw new GitHubUnavailableException(
                                        "GitHub a refusé la publication. Réessayez dans un instant.");
                            })
                    .body(type);
        } catch (RestClientException ex) {
            log.warn("GitHub ({}) injoignable : {}", operation, ex.getClass().getSimpleName());
            throw new GitHubUnavailableException(
                    "GitHub est momentanément injoignable. Réessayez dans un instant.");
        }
    }

    /** Projection d'une réponse portant un {@code sha} (blob, arbre, commit, référence). */
    private record GitShaResponse(String sha) {
    }

    /** Projection de {@code GET /git/ref/...} : la tête de la branche. */
    private record GitRefResponse(GitRefObject object) {
    }

    private record GitRefObject(String sha) {
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
