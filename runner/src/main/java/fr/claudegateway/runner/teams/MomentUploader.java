package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * <b>Ce qui remonte</b> (F-90 / SF-90-03) : les images retenues, <b>une par une</b>, et rien
 * d'autre.
 *
 * <h2>Ce qui reste sur la machine</h2>
 *
 * <p>L'enregistrement vidéo (des centaines de Mo), l'audio, les fichiers bruts, les images écartées,
 * les cookies et les jetons Microsoft : <b>tout cela reste ici</b>. Seules montent les 20 à 60
 * images retenues et, plus tard, le compte rendu que l'agent écrira.
 * <b>La gateway orchestre, elle ne devient pas un entrepôt de vidéos de réunions.</b></p>
 *
 * <h2>Pourquoi par le canal runner et pas par le résultat d'outil</h2>
 *
 * <p>Soixante images en base64 dans une réponse d'outil <b>traverseraient le modèle</b> : des
 * millions de jetons pour des octets qu'il n'a aucune raison de lire, et qui coûteraient plus cher
 * que tout le reste de la fonctionnalité réunie. Le résultat d'outil ne porte donc que des
 * <b>identifiants d'image</b> ; les octets passent par une route dédiée du canal runner, authentifiée
 * par le jeton du poste.</p>
 *
 * <h2>Une image refusée n'arrête pas le travail</h2>
 *
 * <p>Perdre une image sur soixante et <b>le dire</b> vaut mieux que perdre le compte rendu. Le refus
 * remonte donc comme un {@link RefusedException} que l'appelant compte — et c'est seulement quand
 * <b>aucune</b> image ne passe que le travail échoue : un compte rendu de moments sans aucune image
 * n'est pas un compte rendu de moments.</p>
 */
public interface MomentUploader {

    /**
     * Fait remonter une image et rend son identifiant.
     *
     * @param workspaceId terminal Teams auquel l'image appartient
     * @param image       fichier local
     * @return l'identifiant à poser dans un moment
     * @throws RefusedException quand la gateway refuse cette image — le travail continue
     * @throws IOException      quand la remontée n'a pas pu être tentée
     */
    String upload(String workspaceId, Path image) throws IOException;

    /** La gateway a refusé cette image, et a dit pourquoi. */
    class RefusedException extends IOException {

        private static final long serialVersionUID = 1L;

        public RefusedException(String message) {
            super(message);
        }
    }

    /** Aucune remontée possible : le volet n'a pas de jeton, ou pas de gateway à joindre. */
    static MomentUploader unavailable(String why) {
        return (workspaceId, image) -> {
            throw new IOException(why);
        };
    }

    /**
     * La remontée réelle : un {@code POST} par image, jeton runner dans l'en-tête dédié.
     *
     * <p>Le jeton voyage dans {@code X-Runner-Token} et <b>jamais en query</b> : une query finit
     * dans les journaux d'accès du proxy et de l'ingress (décision D9, reprise de SF-38-09).</p>
     */
    static MomentUploader over(HttpClient httpClient, String gatewayBaseUrl, String token) {
        ObjectMapper mapper = new ObjectMapper();
        return (workspaceId, image) -> {
            if (token == null || token.isBlank()) {
                throw new IOException("ce poste n'a pas de jeton runner : rien ne peut remonter");
            }
            byte[] content = Files.readAllBytes(image);
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(trimTrailingSlash(gatewayBaseUrl) + "/runner/teams/moments"
                                    + "?workspaceId=" + workspaceId))
                    .timeout(Duration.ofSeconds(60))
                    .header("X-Runner-Token", token)
                    .header("Content-Type", contentTypeOf(image))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(content))
                    .build();
            try {
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 || response.statusCode() == 201) {
                    String id = mapper.readTree(response.body()).path("imageId").asText("");
                    if (id.isBlank()) {
                        throw new RefusedException("la gateway n'a pas rendu d'identifiant d'image");
                    }
                    return id;
                }
                throw new RefusedException(
                        "la gateway a refusé cette image (HTTP " + response.statusCode() + ")");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("remontée interrompue", e);
            }
        };
    }

    /** Le type d'une image, déduit de son extension — la liste close de F-89 et rien d'autre. */
    static String contentTypeOf(Path image) {
        String name = image.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private static String trimTrailingSlash(String url) {
        String value = url == null ? "" : url.strip();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
