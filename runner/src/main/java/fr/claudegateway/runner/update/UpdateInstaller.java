package fr.claudegateway.runner.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import fr.claudegateway.runner.RunnerBuild;
import fr.claudegateway.runner.launcher.LauncherHome;

/**
 * Télécharger, vérifier, <b>puis seulement</b> installer une version (F-111 / SF-111-03).
 *
 * <p>Le téléchargement passe par le <b>client HTTP du runner</b> — celui qui porte déjà sa connexion :
 * proxy d'entreprise, relais {@code px}, magasin de confiance du système (F-80). Ce qui marche pour les
 * trames marche donc pour la mise à jour, sans autre réglage.</p>
 *
 * <p>Le jar est gardé <b>en mémoire</b> jusqu'à la fin de la vérification : aucun octet non vérifié
 * n'est écrit dans {@code versions/}, pas même dans un fichier temporaire que le lanceur pourrait
 * trouver.</p>
 */
public final class UpdateInstaller implements RunnerUpdater.Installer {

    /** Plafond du jar téléchargé : le runner pèse quelques mégaoctets. */
    public static final int MAX_JAR_BYTES = 64 * 1024 * 1024;

    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private final HttpClient httpClient;
    private final String gatewayBaseUrl;
    private final UpdateVerifier verifier;
    private final LauncherHome home;

    public UpdateInstaller(HttpClient httpClient, String gatewayBaseUrl, UpdateVerifier verifier,
            LauncherHome home) {
        this.httpClient = httpClient;
        this.gatewayBaseUrl = gatewayBaseUrl.replaceAll("/+$", "");
        this.verifier = verifier;
        this.home = home;
    }

    /**
     * Télécharge la version {@code id}, la vérifie, l'installe.
     *
     * @param id             identifiant demandé (celui de la commande de mise à jour)
     * @param expectedSha256 empreinte attendue par la commande, ou {@code null} : l'empreinte servie
     *                       doit alors seulement correspondre au fichier
     * @return le jar installé
     */
    @Override
    public Path install(String id, String expectedSha256) throws UpdateRejectedException {
        if (RunnerBuild.parseId(id).isEmpty()) {
            throw new UpdateRejectedException(UpdateRejectedException.VERSION_MISMATCH,
                    "identifiant de version invalide : " + id);
        }
        String base = gatewayBaseUrl + "/runner/update/" + URLEncoder.encode(id, StandardCharsets.UTF_8);
        String servedSha = text(base + "/sha256");
        if (expectedSha256 != null && !expectedSha256.isBlank()
                && !servedSha.trim().equalsIgnoreCase(expectedSha256.trim())) {
            throw new UpdateRejectedException(UpdateRejectedException.SHA256_MISMATCH,
                    "l'empreinte servie ne correspond pas à celle de la commande de mise à jour");
        }
        String signature = text(base + "/signature");
        byte[] jar = bytes(base);
        verifier.verify(jar, servedSha, signature, id);
        try {
            return home.install(id, jar);
        } catch (IOException | RuntimeException e) {
            throw new UpdateRejectedException(UpdateRejectedException.INSTALL_FAILED,
                    "écriture dans " + home.root() + " impossible (" + e.getMessage() + ")", e);
        }
    }

    private String text(String url) throws UpdateRejectedException {
        byte[] body = fetch(url, 4096);
        return new String(body, StandardCharsets.US_ASCII).trim();
    }

    private byte[] bytes(String url) throws UpdateRejectedException {
        return fetch(url, MAX_JAR_BYTES);
    }

    private byte[] fetch(String url, int max) throws UpdateRejectedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new UpdateRejectedException(UpdateRejectedException.DOWNLOAD_FAILED,
                    "téléchargement impossible (" + e.getMessage() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateRejectedException(UpdateRejectedException.DOWNLOAD_FAILED,
                    "téléchargement interrompu");
        }
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new UpdateRejectedException(UpdateRejectedException.DOWNLOAD_FAILED,
                        "la gateway a répondu " + response.statusCode() + " pour " + url);
            }
            byte[] body = in.readNBytes(max + 1);
            if (body.length > max) {
                throw new UpdateRejectedException(UpdateRejectedException.DOWNLOAD_FAILED,
                        "fichier plus gros que prévu (" + max + " octets au plus)");
            }
            return body;
        } catch (IOException e) {
            throw new UpdateRejectedException(UpdateRejectedException.DOWNLOAD_FAILED,
                    "téléchargement interrompu (" + e.getMessage() + ")", e);
        }
    }
}
