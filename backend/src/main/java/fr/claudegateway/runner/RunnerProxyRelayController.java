package fr.claudegateway.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.shared.error.ErrorResponse;

/**
 * Le <b>relais local</b> {@code px}, servi par la gateway elle-même (F-59 / SF-59-01).
 *
 * <p>L'assistant proxy (F-55) fait télécharger {@code px} depuis GitHub. Sur un poste d'entreprise,
 * GitHub est souvent bloqué <b>par catégorie</b> — indépendamment du proxy à authentification.
 * L'utilisateur a alors besoin du relais <b>pour sortir</b>, et d'une sortie <b>pour obtenir le
 * relais</b>. Le domaine de la gateway, lui, est forcément autorisé : sinon rien du produit ne
 * fonctionne — et {@code curl --proxy-ntlm --proxy-user :} l'atteint même derrière un {@code 407}.
 * La gateway sert donc elle-même le relais, exactement comme elle sert le jar du runner
 * (SF-38-03) et ses paquets autonomes (F-44).</p>
 *
 * <ul>
 *   <li>{@code GET /runner/relay/windows} — {@code px-<version>-windows-amd64.zip} ;</li>
 *   <li>{@code GET /runner/relay/macos-aarch64} — l'archive Apple Silicon ;</li>
 *   <li>{@code GET /runner/relay/linux-x64} — l'archive Linux glibc x86_64 ;</li>
 *   <li>{@code GET /runner/relay/license} — la <b>notice MIT</b>, en clair, affichable ;</li>
 *   <li>{@code GET /runner/relay/formats} — ce que <i>cette</i> gateway sert réellement.</li>
 * </ul>
 *
 * <p><b>Licence — la condition, pas une note de bas de page.</b> {@code px} est sous MIT : la
 * redistribution est licite <i>à condition</i> que la notice accompagne la copie. Elle est
 * <b>dans</b> l'archive amont (servie verbatim) <b>et</b> servie à part pour que l'écran puisse la
 * montrer avant de faire télécharger 21 Mo. Et la condition est tenue par construction : <b>sans
 * notice lisible, aucune archive n'est servie</b> (D3) — {@code cntlm}, sous GPL, n'est ni empaqueté
 * ni servi, l'assistant continue d'y renvoyer par lien.</p>
 *
 * <p>Endpoints <b>publics</b>, comme {@code /runner/download} : ce sont des binaires tiers publics,
 * sans jeton ni secret — et l'utilisateur qui en a besoin est précisément celui dont le poste ne
 * sort pas. Ils passent par la chaîne de sécurité dédiée {@code /runner/**}
 * ({@link RunnerSecurityConfig}), déclarés un par un.</p>
 *
 * <p>Un chemin non configuré ou un fichier absent donne un <b>404 explicite</b>, jamais une erreur
 * serveur : un format non empaqueté est un état de déploiement, et l'écran doit pouvoir le lire pour
 * masquer le lien plutôt que d'en offrir un mort.</p>
 */
@RestController
@RequestMapping("/runner/relay")
public class RunnerProxyRelayController {

    private static final Logger log = LoggerFactory.getLogger(RunnerProxyRelayController.class);

    /** Nom sous lequel la notice est servie — le même que dans l'image. */
    private static final String LICENSE_FILENAME = "px-LICENSE.txt";

    /**
     * L'archive n'est pas empaquetée dans cette image. Distinct de {@code runner_jar_unavailable} et
     * de {@code runner_package_unavailable} : trois incidents d'exploitation différents, et les
     * confondre ferait chercher au mauvais endroit.
     */
    private static final String RELAY_UNAVAILABLE = "runner_relay_unavailable";

    /**
     * La notice manque (D2). Ce n'est <b>pas</b> le même incident que l'archive absente : c'est un
     * <b>refus délibéré de servir</b>, parce que redistribuer du MIT sans sa notice serait une
     * violation de licence. Le nommer autrement ferait croire à un simple oubli d'empaquetage.
     */
    private static final String LICENSE_UNAVAILABLE = "runner_relay_license_unavailable";

    private static final String NO_LICENSE_MESSAGE =
            "La notice de licence de px n'est pas disponible sur cette gateway : "
                    + "aucune archive n'est servie sans elle.";

    private final String windowsPath;
    private final String macosAarch64Path;
    private final String linuxX64Path;
    private final String licensePath;
    private final String version;

    public RunnerProxyRelayController(
            @Value("${app.runner.proxy-relay.windows-path:}") String windowsPath,
            @Value("${app.runner.proxy-relay.macos-aarch64-path:}") String macosAarch64Path,
            @Value("${app.runner.proxy-relay.linux-x64-path:}") String linuxX64Path,
            @Value("${app.runner.proxy-relay.license-path:}") String licensePath,
            @Value("${app.runner.proxy-relay.version:}") String version) {
        this.windowsPath = trimmed(windowsPath);
        this.macosAarch64Path = trimmed(macosAarch64Path);
        this.linuxX64Path = trimmed(linuxX64Path);
        this.licensePath = trimmed(licensePath);
        this.version = trimmed(version);
    }

    /** Relais {@code px} pour Windows amd64 — le poste d'où vient le problème. */
    @GetMapping("/windows")
    public ResponseEntity<?> windows() {
        return serveArchive(windowsPath, "Le relais px pour Windows");
    }

    /**
     * Relais {@code px} pour macOS Apple Silicon. Une route par plateforme, jamais un
     * {@code ?platform=} : le cache et les liens directs resteraient ambigus (D1, comme en SF-44-03).
     */
    @GetMapping("/macos-aarch64")
    public ResponseEntity<?> macosAarch64() {
        return serveArchive(macosAarch64Path, "Le relais px pour macOS Apple Silicon");
    }

    /** Relais {@code px} pour Linux glibc x86_64. */
    @GetMapping("/linux-x64")
    public ResponseEntity<?> linuxX64() {
        return serveArchive(linuxX64Path, "Le relais px pour Linux x86_64");
    }

    /**
     * La notice MIT de {@code px}, <b>affichable</b> : {@code text/plain} et pas de
     * {@code attachment}. L'écran doit pouvoir l'ouvrir dans un onglet avant que quiconque
     * télécharge 21 Mo — une licence qu'il faut d'abord télécharger pour la lire n'est pas montrée.
     */
    @GetMapping("/license")
    public ResponseEntity<?> license() {
        Path path = readableFile(licensePath);
        if (path == null) {
            log.warn("Notice de licence px indisponible : chemin '{}' absent ou illisible", licensePath);
            return notFound(LICENSE_UNAVAILABLE, NO_LICENSE_MESSAGE);
        }
        String notice;
        try {
            notice = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Notice de licence px illisible : {}", path, e);
            return notFound(LICENSE_UNAVAILABLE, NO_LICENSE_MESSAGE);
        }
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + LICENSE_FILENAME + "\"")
                .body(notice);
    }

    /**
     * Ce que <i>cette</i> gateway sert réellement, plateforme par plateforme, plus la version amont
     * servie. L'écran le lit pour <b>masquer</b> un lien absent au lieu d'en offrir un mort — une
     * gateway déployée avant F-59 n'empaquette rien, et doit rester utilisable telle quelle.
     *
     * <p>Sans notice, tout est annoncé absent : c'est la même règle qu'au téléchargement, dite en
     * amont.</p>
     */
    @GetMapping("/formats")
    public RelayFormats formats() {
        boolean license = readableFile(licensePath) != null;
        return new RelayFormats(
                license && exists(windowsPath),
                license && exists(macosAarch64Path),
                license && exists(linuxX64Path),
                license,
                version);
    }

    /**
     * Disponibilité des relais servis par cette gateway, et version amont servie.
     *
     * <p>{@code version} n'est qu'un libellé d'affichage : elle ne résout aucun fichier, le
     * déploiement désignant les chemins.</p>
     */
    public record RelayFormats(boolean windows, boolean macosAarch64, boolean linuxX64,
            boolean license, String version) {
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    /** Le fichier configuré s'il est réellement lisible, sinon {@code null}. */
    private Path readableFile(String configuredPath) {
        if (configuredPath.isEmpty()) {
            return null;
        }
        Path path = Path.of(configuredPath);
        return Files.isRegularFile(path) && Files.isReadable(path) ? path : null;
    }

    private boolean exists(String configuredPath) {
        return readableFile(configuredPath) != null;
    }

    /**
     * Sert une archive — <b>si et seulement si</b> la notice de licence est servie elle aussi (D3).
     *
     * <p>C'est la condition MIT tenue par construction plutôt que par discipline : sans notice,
     * ne rien servir n'est pas une violation ; servir en serait une.</p>
     */
    private ResponseEntity<?> serveArchive(String configuredPath, String label) {
        if (readableFile(licensePath) == null) {
            log.warn("{} n'est pas servi : la notice de licence est absente ('{}')", label, licensePath);
            return notFound(LICENSE_UNAVAILABLE, NO_LICENSE_MESSAGE);
        }
        Path path = readableFile(configuredPath);
        if (path == null) {
            log.debug("{} n'est pas disponible : chemin '{}' absent ou illisible", label, configuredPath);
            return notFound(RELAY_UNAVAILABLE, label + " n'est pas disponible sur cette gateway.");
        }
        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            log.warn("{} indisponible : taille de {} illisible", label, path, e);
            return notFound(RELAY_UNAVAILABLE, label + " n'est pas disponible sur cette gateway.");
        }
        Resource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(size)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + path.getFileName() + "\"")
                .body(resource);
    }

    private ResponseEntity<ErrorResponse> notFound(String errorCode, String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(errorCode, message));
    }
}
