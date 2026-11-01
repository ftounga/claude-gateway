package fr.claudegateway.jarvis.companion;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.shared.error.ErrorResponse;

/**
 * <b>Ce que le téléphone appelle</b> (F-232 / SF-232-03) — chaîne dédiée {@code /companion/**}
 * ({@link CompanionSecurityConfig}) :
 *
 * <ul>
 *   <li>{@code POST /companion/pair} — échange le code affiché par l'application contre le jeton dédié ;</li>
 *   <li>{@code GET /companion/announcements} — les annonces à dire, par attente longue, avec l'en-tête
 *       {@value #TOKEN_HEADER}.</li>
 * </ul>
 *
 * <p>Le jeton est vérifié <b>ici</b>, par le contrôleur ; rien n'est posé dans le {@code SecurityContext} (même
 * patron que le runner, D9 de F-38) : il ne peut authentifier aucune autre route.</p>
 */
@RestController
@RequestMapping("/companion")
public class CompanionFeedController {

    static final String TOKEN_HEADER = "X-Companion-Token";

    private final JarvisCompanionPairingService pairing;
    private final JarvisCompanionAnnouncements announcements;

    public CompanionFeedController(JarvisCompanionPairingService pairing,
            JarvisCompanionAnnouncements announcements) {
        this.pairing = pairing;
        this.announcements = announcements;
    }

    @PostMapping("/pair")
    public ResponseEntity<?> pair(@RequestBody(required = false) PairRequest body) {
        try {
            JarvisCompanionPairingService.Paired paired =
                    pairing.pair(body == null ? null : body.code(), body == null ? null : body.name());
            return ResponseEntity.ok(paired);
        } catch (JarvisCompanionPairingService.InvalidCode e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("companion_pairing_invalid",
                    "Code inconnu, expiré ou déjà utilisé."));
        } catch (JarvisCompanionPairingService.LimitReached e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("companion_limit",
                    "Cinq téléphones sont déjà appairés : révoquez-en un dans l'application."));
        }
    }

    @GetMapping("/announcements")
    public ResponseEntity<?> announcements(@RequestHeader(name = TOKEN_HEADER, required = false) String token,
            @RequestParam(name = "after", required = false) String after,
            @RequestParam(name = "wait", required = false) Integer wait) {
        Optional<JarvisCompanionDevice> device = pairing.authenticate(token);
        if (device.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("companion_token_invalid",
                    "Ce téléphone n'est plus appairé."));
        }
        return ResponseEntity.ok(announcements.feed(device.get().getUserId(), parse(after), wait));
    }

    private static OffsetDateTime parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Corps de {@code POST /companion/pair}. */
    public record PairRequest(String code, String name) {
    }
}
