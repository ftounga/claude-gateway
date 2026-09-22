package fr.claudegateway.presentations;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.presentations.dto.PresentationResponse;

/**
 * <b>Les présentations telles que l'écran les lit</b> (F-129 / SF-129-02). Lecture seule côté REST : la
 * <b>production</b> passe par l'outil {@code presentation_publish} de l'agent, jamais par une route
 * publique. Toute route est scellée par {@link CurrentUser} (isolation {@code user_id}).
 */
@RestController
@RequestMapping("/presentations")
public class PresentationController {

    private final PresentationService presentations;
    private final CurrentUser currentUser;

    public PresentationController(PresentationService presentations, CurrentUser currentUser) {
        this.presentations = presentations;
        this.currentUser = currentUser;
    }

    /** Les présentations d'un lieu (poste/client, espace). */
    @GetMapping
    public List<PresentationResponse> list(@RequestParam UUID hostId, @RequestParam String space) {
        UUID userId = currentUser.requireId();
        return presentations.list(userId, hostId, parseSpace(space)).stream()
                .map(PresentationResponse::of).toList();
    }

    /** Une présentation. */
    @GetMapping("/{id}")
    public PresentationResponse get(@PathVariable UUID id) {
        return PresentationResponse.of(presentations.get(currentUser.requireId(), id));
    }

    /** Le vrai {@code .pptx}, en pièce jointe (téléchargement). */
    @GetMapping("/{id}/pptx")
    public ResponseEntity<byte[]> pptx(@PathVariable UUID id) {
        UUID userId = currentUser.requireId();
        Presentation presentation = presentations.get(userId, id);
        byte[] content = presentations.pptx(userId, id);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName(presentation.getTitle()), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(PresentationStore.PPTX_CONTENT_TYPE))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(content);
    }

    /** Une image de slide (F-129 / SF-129-03), 1-based, PNG, scellée par le propriétaire. */
    @GetMapping("/{id}/slides/{index}")
    public ResponseEntity<byte[]> slide(@PathVariable UUID id, @PathVariable int index) {
        byte[] content = presentations.slide(currentUser.requireId(), id, index);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(content);
    }

    /** Supprime une présentation et tout son contenu. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        presentations.delete(currentUser.requireId(), id);
        return ResponseEntity.noContent().build();
    }

    private static PresentationSpace parseSpace(String space) {
        if (space == null) {
            throw new PresentationRejectedException("Espace inconnu : FORGE ou VIGIE.");
        }
        try {
            return PresentationSpace.valueOf(space.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PresentationRejectedException("Espace inconnu : FORGE ou VIGIE.");
        }
    }

    /** Un nom de fichier sûr, dérivé du titre : ASCII simple, jamais vide. */
    private static String fileName(String title) {
        String base = title == null ? "" : title.strip();
        StringBuilder sb = new StringBuilder();
        for (char c : base.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ' ') {
                sb.append(c == ' ' ? '-' : c);
            }
        }
        String safe = sb.toString().replaceAll("-{2,}", "-").strip();
        if (safe.isBlank()) {
            safe = "presentation";
        }
        return safe + ".pptx";
    }
}
