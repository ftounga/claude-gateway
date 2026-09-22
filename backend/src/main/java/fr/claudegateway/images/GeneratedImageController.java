package fr.claudegateway.images;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.images.dto.GeneratedImageResponse;

/**
 * <b>Les images générées telles que l'écran les lit</b> (F-142 / SF-142-04). Lecture seule côté REST : la
 * <b>production</b> passe par l'outil {@code generate_image} de l'agent, jamais par une route publique.
 * Toute route est scellée par {@link CurrentUser} (isolation {@code user_id}) : une image d'un autre
 * compte est <b>introuvable</b> (404).
 */
@RestController
@RequestMapping("/generated-images")
public class GeneratedImageController {

    private final ImageGenerationService images;
    private final CurrentUser currentUser;

    public GeneratedImageController(ImageGenerationService images, CurrentUser currentUser) {
        this.images = images;
        this.currentUser = currentUser;
    }

    /** Les images d'un lieu (poste/client, espace). */
    @GetMapping
    public List<GeneratedImageResponse> list(@RequestParam UUID hostId, @RequestParam String space) {
        UUID userId = currentUser.requireId();
        return images.list(userId, hostId, parseSpace(space)).stream()
                .map(GeneratedImageResponse::of).toList();
    }

    /** Une image (statut compris). */
    @GetMapping("/{id}")
    public GeneratedImageResponse get(@PathVariable UUID id) {
        return GeneratedImageResponse.of(images.get(currentUser.requireId(), id));
    }

    /** Le PNG d'une image, scellé par le propriétaire. */
    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable UUID id) {
        byte[] content = images.bytes(currentUser.requireId(), id);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(content);
    }

    /** Supprime une image et son contenu. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        images.delete(currentUser.requireId(), id);
        return ResponseEntity.noContent().build();
    }

    private static ImageSpace parseSpace(String space) {
        if (space == null) {
            throw new ImageRejectedException("Espace inconnu : FORGE ou VIGIE.");
        }
        try {
            return ImageSpace.valueOf(space.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ImageRejectedException("Espace inconnu : FORGE ou VIGIE.");
        }
    }
}
