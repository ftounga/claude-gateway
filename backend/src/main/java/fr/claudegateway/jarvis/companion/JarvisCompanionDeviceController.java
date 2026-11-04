package fr.claudegateway.jarvis.companion;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.jarvis.companion.JarvisCompanionService.CompanionPairingInvalidException;
import fr.claudegateway.shared.error.ErrorResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * <b>Le compagnon Android, côté appareil</b> (F-232 / SF-232-03). Servi par la chaîne {@code /runner/**}, isolée de la
 * chaîne utilisateur ; deux routes seulement, déclarées une par une dans {@code RunnerSecurityConfig} :
 *
 * <ul>
 *   <li>{@code POST /runner/jarvis/pair} — authentifié par le <b>code</b> porté dans le corps ;</li>
 *   <li>{@code GET /runner/jarvis/announcements} — authentifié par le <b>jeton dédié</b> dans l'en-tête
 *   {@value #TOKEN_HEADER}, vérifié ICI ; rien n'est posé dans le {@code SecurityContext} ;</li>
 *   <li>{@code POST /runner/jarvis/session} (SF-232-05) — même jeton, même vérification : rend une <b>session courte à
 *   portée {@code jarvis-compagnon}</b> pour l'écran Jarvis ouvert dans le compagnon.</li>
 * </ul>
 *
 * <p>Le jeton du compagnon n'est lu nulle part ailleurs : il n'ouvre aucune autre route. Il n'est jamais journalisé.</p>
 */
@RestController
@RequestMapping("/runner/jarvis")
public class JarvisCompanionDeviceController {

    /** L'en-tête du jeton du compagnon. Ni {@code Authorization} (chaîne principale) ni {@code X-Runner-Token}. */
    public static final String TOKEN_HEADER = "X-Jarvis-Token";

    private final JarvisCompanionService companions;
    private final JarvisAnnouncementService announcements;
    private final JarvisCompanionSessionService sessions;

    public JarvisCompanionDeviceController(JarvisCompanionService companions,
            JarvisAnnouncementService announcements, JarvisCompanionSessionService sessions) {
        this.companions = companions;
        this.announcements = announcements;
        this.sessions = sessions;
    }

    /**
     * F-232 / SF-232-05 — la session de l'écran Jarvis du compagnon. {@code locked} absent vaut verrouillé ; le corps
     * entier est facultatif. 401 générique si le jeton est refusé.
     */
    @PostMapping(value = "/session", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> session(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody(required = false) SessionRequest request) {
        Boolean locked = request == null ? null : request.locked();
        JarvisCompanionOpening.Purpose purpose = request == null ? null : request.purposeOrNull();
        return sessions.open(token, locked, purpose)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ErrorResponse("jarvis_companion_unauthorized", "Jeton du compagnon refusé.")));
    }

    @PostMapping(value = "/pair", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> pair(@Valid @RequestBody PairRequest request) {
        return Map.of("token", companions.redeem(request.code(), request.label()));
    }

    @GetMapping(value = "/announcements", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> announcements(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestParam(name = "cursor", required = false) String cursor) {
        Optional<JarvisCompanion> companion = companions.authenticate(token);
        if (companion.isEmpty()) {
            // 401 générique : on ne dit pas si le jeton est inconnu, révoqué ou expiré.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("jarvis_companion_unauthorized", "Jeton du compagnon refusé."));
        }
        return ResponseEntity.ok(announcements.since(companion.get().getUserId(), parse(cursor)));
    }

    @ExceptionHandler(CompanionPairingInvalidException.class)
    ResponseEntity<ErrorResponse> pairingInvalid(CompanionPairingInvalidException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("jarvis_companion_pairing_invalid", ex.getMessage()));
    }

    static OffsetDateTime parse(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(cursor.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** SF-232-05 — l'état du téléphone et le motif ({@code OPEN}, {@code UNLOCK}, {@code RENEW} ; inconnu ⇒ OPEN). */
    public record SessionRequest(Boolean locked, @Size(max = 16) String purpose) {

        JarvisCompanionOpening.Purpose purposeOrNull() {
            if (purpose == null) {
                return null;
            }
            try {
                return JarvisCompanionOpening.Purpose.valueOf(purpose.strip().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** Le code affiché dans l'écran Jarvis, et le nom du téléphone (facultatif). */
    public record PairRequest(@NotBlank @Size(max = 32) String code, @Size(max = 200) String label) {
    }
}
