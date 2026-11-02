package fr.claudegateway.jarvis.pieces;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Les contenus manquants des pièces</b> (F-237 / SF-237-02) : vignette, corps texte d'un courriel, pièce d'un
 * besoin. Sous JWT, pour le seul propriétaire ; 404 sinon.
 */
@RestController
public class JarvisPieceContentController {

    /** Types servis en ligne ; tout le reste se télécharge (jamais d'HTML rendu). */
    private static final Set<String> INLINE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp",
            "video/mp4", "video/webm", "video/quicktime", "application/pdf", "text/plain", "text/markdown",
            "text/csv", "application/json");

    private final JarvisPieceContentService service;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public JarvisPieceContentController(JarvisPieceContentService service, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.service = service;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    @GetMapping("/jarvis/pieces/{pieceId}/thumb")
    public ResponseEntity<byte[]> thumb(@PathVariable String pieceId) {
        atelierAccess.requireAccess();
        byte[] png = service.thumb(currentUser.requireId(), pieceId);
        return ResponseEntity.ok()
                .contentType(png.length > 8 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G'
                        ? MediaType.IMAGE_PNG : MediaType.APPLICATION_OCTET_STREAM)
                .cacheControl(CacheControl.noCache().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(png);
    }

    @GetMapping("/jarvis/pieces/{pieceId}/text")
    public ResponseEntity<String> text(@PathVariable String pieceId) {
        atelierAccess.requireAccess();
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .header("X-Content-Type-Options", "nosniff")
                .body(service.mailText(currentUser.requireId(), pieceId));
    }

    /** L'image d'un schéma : SVG servi comme image, sans script possible (CSP fermée), jamais comme page. */
    @GetMapping("/jarvis/pieces/{pieceId}/image")
    public ResponseEntity<byte[]> diagramImage(@PathVariable String pieceId) {
        atelierAccess.requireAccess();
        JarvisPieceContentService.Content content = service.diagramImage(currentUser.requireId(), pieceId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src data:")
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(content.bytes());
    }

    @GetMapping("/jarvis/pieces/{pieceId}/spec")
    public ResponseEntity<String> diagramSpec(@PathVariable String pieceId) {
        atelierAccess.requireAccess();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Content-Type-Options", "nosniff")
                .body(service.diagramSpec(currentUser.requireId(), pieceId));
    }

    @GetMapping("/jarvis/pieces/{pieceId}/attachments/{position}")
    public ResponseEntity<byte[]> attachment(@PathVariable String pieceId, @PathVariable int position) {
        atelierAccess.requireAccess();
        JarvisPieceContentService.Content content = service.attachment(currentUser.requireId(), pieceId, position);
        boolean inline = content.contentType() != null && INLINE_TYPES.contains(content.contentType());
        MediaType type = inline ? MediaType.parseMediaType(content.contentType()) : MediaType.APPLICATION_OCTET_STREAM;
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(content.name() == null ? "piece" : content.name(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(content.bytes());
    }

    @ExceptionHandler(JarvisPieceContentService.PieceNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(JarvisPieceContentService.PieceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
    }
}
