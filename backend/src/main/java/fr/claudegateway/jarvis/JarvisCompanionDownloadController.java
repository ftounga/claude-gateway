package fr.claudegateway.jarvis;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.shared.error.ErrorResponse;

/**
 * Le compagnon Android de Jarvis, servi par <b>le même chemin que les paquets du runner</b> (F-232 /
 * SF-232-02, D2 ; cf. {@code RunnerDownloadController}, F-44).
 *
 * <ul>
 *   <li>{@code GET /runner/download/android} — l'APK, seulement s'il porte la signature attendue ;</li>
 *   <li>{@code GET /runner/download/android/version} — ce qui est servi (version, empreintes,
 *       taille), lu par l'écran Jarvis pour proposer l'installation ou la mise à jour.</li>
 * </ul>
 *
 * <p>Publiques, comme les paquets du runner : l'APK ne contient ni jeton ni secret. Un APK absent ou
 * non vérifié rend un 404 explicite (et {@code available: false}), jamais une erreur serveur.</p>
 */
@RestController
@RequestMapping("/runner/download/android")
public class JarvisCompanionDownloadController {

    static final String FILENAME = "jarvis-compagnon.apk";
    static final MediaType APK = MediaType.parseMediaType("application/vnd.android.package-archive");

    private final JarvisCompanionApk apk;

    public JarvisCompanionDownloadController(JarvisCompanionApk apk) {
        this.apk = apk;
    }

    @GetMapping
    public ResponseEntity<?> download() {
        return apk.current()
                .<ResponseEntity<?>>map(release -> ResponseEntity.ok()
                        .contentType(APK)
                        .contentLength(release.sizeBytes())
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + FILENAME + "\"")
                        .body(new FileSystemResource(release.path())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new ErrorResponse("jarvis_companion_unavailable",
                                "Le compagnon Android de Jarvis n'est pas disponible sur cette gateway.")));
    }

    @GetMapping("/version")
    public CompanionVersion version() {
        return apk.current()
                .map(r -> new CompanionVersion(true, r.versionCode(), r.versionName(), r.sha256(),
                        r.signingCertSha256(), r.sizeBytes()))
                .orElse(new CompanionVersion(false, null, null, null, null, null));
    }

    /** Ce que l'écran lit. Tous les champs sont {@code null} quand {@code available} est faux. */
    public record CompanionVersion(boolean available, Integer versionCode, String versionName, String sha256,
            String signingCertSha256, Long sizeBytes) {
    }
}
