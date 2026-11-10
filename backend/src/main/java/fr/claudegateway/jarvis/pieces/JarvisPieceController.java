package fr.claudegateway.jarvis.pieces;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>L'index des pièces</b> (F-237 / SF-237-01) : {@code GET /api/jarvis/pieces} — ce que Jarvis peut montrer, pour le
 * seul compte courant. Lecture seule ; un sujet d'un autre compte rend 404 (indiscernable d'un sujet inconnu).
 */
@RestController
public class JarvisPieceController {

    private final JarvisPieceService service;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public JarvisPieceController(JarvisPieceService service, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.service = service;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    @GetMapping("/jarvis/pieces")
    public JarvisPieceService.Result pieces(@RequestParam(required = false) UUID subjectId,
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false) Integer chantier,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime since,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime until,
            @RequestParam(required = false) UUID series,
            @RequestParam(required = false) Integer limit) {
        atelierAccess.requireAccess();
        JarvisPieceService.Filter filter = new JarvisPieceService.Filter(chantier,
                parse(Piece.Category.class, category, "category"), parse(Piece.Kind.class, kind, "kind"), since,
                until, series, limit == null ? JarvisPieceService.DEFAULT_LIMIT : limit);
        // F-253 / SF-253-03 — portée client : les sujets du client, du seul compte courant ; le sujet nommé l'emporte.
        return subjectId == null && clientId != null && !clientId.isBlank()
                ? service.listForClient(currentUser.requireId(), clientId.strip(), filter)
                : service.list(currentUser.requireId(), subjectId, filter);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadFilterException("Valeur inconnue pour " + name + ".");
        }
    }

    /** Un filtre inconnu : 400 lisible, jamais 500. */
    static final class BadFilterException extends RuntimeException {
        BadFilterException(String message) {
            super(message);
        }
    }

    @ExceptionHandler(BadFilterException.class)
    ResponseEntity<Map<String, String>> badFilter(BadFilterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
    }
}
