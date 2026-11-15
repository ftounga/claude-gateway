package fr.claudegateway.diagrams;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * F-238 / SF-238-08 — {@code GET /api/diagrams/icons/{chemin}.png} : proxy des icônes officielles pour la scène 3D.
 * Authentifié comme le reste de {@code /api} ; aucune donnée utilisateur, donc cache public et immuable.
 */
@RestController
public class DiagramIconController {

    static final String PREFIX = "/diagrams/icons/";
    private static final String SUFFIX = ".png";

    private final DiagramIconProxy proxy;

    public DiagramIconController(DiagramIconProxy proxy) {
        this.proxy = proxy;
    }

    @GetMapping(PREFIX + "**")
    public ResponseEntity<byte[]> icon(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith(PREFIX) || !path.endsWith(SUFFIX)) {
            throw new DiagramIconProxy.InvalidIconNameException();
        }
        // Le chemin BRUT (non décodé) : un « %2e%2e » ou « %2f » ne passe jamais le motif.
        String name = path.substring(PREFIX.length(), path.length() - SUFFIX.length());
        byte[] png = proxy.icon(name).orElse(null);
        if (png == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).header("X-Content-Type-Options", "nosniff").build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(png);
    }

    @ExceptionHandler(DiagramIconProxy.InvalidIconNameException.class)
    ResponseEntity<Map<String, String>> invalid(DiagramIconProxy.InvalidIconNameException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(DiagramRendererUnavailableException.class)
    ResponseEntity<Map<String, String>> unavailable(DiagramRendererUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "Icônes indisponibles."));
    }
}
