package fr.claudegateway.runner.toolchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.runner.RunnerIdentity;
import fr.claudegateway.runner.RunnerTokenAuthenticator;
import fr.claudegateway.shared.error.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;

/**
 * <b>La chaîne vidéo servie par le portail</b> (F-215 / SF-215-06) : le runner y prend Node, hyperframes, GSAP et
 * ffmpeg au lieu d'internet — le poste du 2026-10-25 s'était vu refuser {@code nodejs.org} par son proxy.
 *
 * <p><b>Authentification (D9)</b>, comme les remontées runner : le jeton voyage dans {@code X-Runner-Token} et est
 * vérifié <b>ici</b> ({@link RunnerTokenAuthenticator}), refus en {@code 401} générique, rien dans le
 * {@code SecurityContext}. Ce qui protège le CONTENU n'est pas l'accès mais la <b>signature</b> du manifeste (clé
 * F-111), que le runner vérifie avant d'écrire quoi que ce soit ; le jeton réserve simplement ces centaines de
 * mégaoctets aux postes appariés.</p>
 *
 * <p>Aucune donnée d'un compte : le paquet est le même pour tous les postes.</p>
 */
@RestController
@RequestMapping("/runner/toolchain/video")
public class VideoToolchainController {

    /** En-tête portant le jeton runner (D9). */
    public static final String TOKEN_HEADER = "X-Runner-Token";
    static final String UNAVAILABLE = "video_toolchain_unavailable";

    private static final Logger log = LoggerFactory.getLogger(VideoToolchainController.class);

    private final RunnerTokenAuthenticator authenticator;
    private final VideoToolchainStore store;
    private final VideoToolchainBundleServer server;

    public VideoToolchainController(RunnerTokenAuthenticator authenticator, VideoToolchainStore store,
            VideoToolchainBundleServer server) {
        this.authenticator = authenticator;
        this.store = store;
        this.server = server;
    }

    @GetMapping("/{version}/manifest")
    public ResponseEntity<?> manifest(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @PathVariable String version) {
        if (authenticate(token).isEmpty()) {
            return unauthorized();
        }
        return store.manifest(version).<ResponseEntity<?>>map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                        .body(bytes))
                .orElseGet(() -> unavailable("La chaîne vidéo " + safe(version)
                        + " n'est pas publiée sur ce portail."));
    }

    @GetMapping("/{version}/signature")
    public ResponseEntity<?> signature(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @PathVariable String version) {
        if (authenticate(token).isEmpty()) {
            return unauthorized();
        }
        return store.signature(version).<ResponseEntity<?>>map(sig -> ResponseEntity.ok()
                        .contentType(MediaType.TEXT_PLAIN)
                        .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                        .body(sig))
                .orElseGet(() -> unavailable("La chaîne vidéo " + safe(version)
                        + " n'est pas signée sur ce portail."));
    }

    @GetMapping("/{version}/{platform}")
    public void bundle(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
            @PathVariable String version, @PathVariable String platform, HttpServletResponse response)
            throws IOException {
        Optional<RunnerIdentity> identity = authenticate(token);
        if (identity.isEmpty()) {
            writeJson(response, HttpStatus.UNAUTHORIZED, "{\"error\":\"Jeton runner refusé.\"}");
            return;
        }
        log.info("Chaîne vidéo {} / {} servie au poste {}{}", safe(version), safe(platform),
                identity.get().hostId(), range == null ? "" : " (reprise " + safe(range) + ")");
        if (!server.serve(version, platform, range, response)) {
            writeJson(response, HttpStatus.NOT_FOUND, "{\"error\":\"" + UNAVAILABLE + "\",\"message\":\""
                    + "Aucun paquet de la chaîne vidéo " + safe(version) + " pour " + safe(platform)
                    + " sur ce portail.\"}");
        }
    }

    private Optional<RunnerIdentity> authenticate(String token) {
        return authenticator.authenticate(token == null ? "" : token.strip());
    }

    private static ResponseEntity<?> unauthorized() {
        // 401 générique : on ne dit pas si le jeton est inconnu, expiré ou révoqué.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("error", "Jeton runner refusé."));
    }

    private static ResponseEntity<ErrorResponse> unavailable(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(UNAVAILABLE, message));
    }

    private static void writeJson(HttpServletResponse response, HttpStatus status, String json) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json);
    }

    /** Une valeur reçue, rendue sûre pour un message ou un journal (bornée, sans guillemet ni contrôle). */
    static String safe(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("[^0-9A-Za-z.=,_-]", "?");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }
}
