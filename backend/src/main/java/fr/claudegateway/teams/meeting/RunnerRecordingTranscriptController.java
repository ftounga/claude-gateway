package fr.claudegateway.teams.meeting;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.SpaceEntitlementService;
import fr.claudegateway.runner.RunnerIdentity;
import fr.claudegateway.runner.RunnerTokenAuthenticator;

/**
 * <b>La remontée du texte d'un enregistrement déposé</b> (F-147 / SF-147-02) : la seule route par
 * laquelle le texte transcrit <b>sur le poste</b> rejoint sa réunion.
 *
 * <p><b>Mêmes gardes que l'audio de réunion</b> ({@link RunnerMeetingAudioController}) : jeton runner
 * (401 générique), droit Vigie (403), réunion introuvable hors du couple du jeton (404 indiscernable).
 * La différence tient en une ligne : le couple compte/poste vient <b>du jeton</b>
 * ({@link RunnerIdentity#hostId()}), pas d'un paramètre — il n'y a rien à croire sur parole.</p>
 *
 * <p><b>Ce qui monte est du texte, et rien d'autre.</b> Ni vidéo, ni audio : la transcription a eu lieu
 * sur la machine (F-91), et c'est tout l'intérêt du chemin local.</p>
 *
 * <p><b>Contenu = donnée.</b> Le texte n'est jamais interprété comme une instruction ; il est rangé tel
 * quel, et l'anti-injection s'applique à l'exploitation, comme pour toute transcription.</p>
 */
@RestController
@RequestMapping("/runner/teams/meetings")
public class RunnerRecordingTranscriptController {

    /** En-tête portant le jeton runner (jamais {@code Authorization}, D9). */
    public static final String TOKEN_HEADER = "X-Runner-Token";

    /** Ce que le poste envoie : le texte, ou l'échec qui explique son absence. */
    public record LocalTranscript(String text, String failure) {
    }

    private final RunnerTokenAuthenticator authenticator;
    private final RecordingMeetingService meetings;
    private final SpaceEntitlementService entitlements;

    public RunnerRecordingTranscriptController(RunnerTokenAuthenticator authenticator,
            RecordingMeetingService meetings, SpaceEntitlementService entitlements) {
        this.authenticator = authenticator;
        this.meetings = meetings;
        this.entitlements = entitlements;
    }

    @PostMapping(value = "/{meetingId}/local-transcript", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> deposit(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @PathVariable UUID meetingId,
            @RequestBody(required = false) LocalTranscript body) {
        Optional<RunnerIdentity> identity = authenticator.authenticate(token == null ? "" : token.strip());
        if (identity.isEmpty()) {
            return refuse(HttpStatus.UNAUTHORIZED, "Jeton runner refusé.");
        }
        UUID userId = identity.get().userId();
        UUID hostId = identity.get().hostId();
        if (!entitlements.isEntitled(userId, EntitlementSpace.VIGIE)) {
            return refuse(HttpStatus.FORBIDDEN,
                    "Ce compte n'a pas l'option Teams : le texte d'un enregistrement ne peut pas remonter.");
        }
        String text = body == null || body.text() == null ? "" : body.text().strip();
        String failure = body == null || body.failure() == null ? "" : body.failure().strip();
        try {
            if (text.isEmpty()) {
                // Pas de texte : c'est un échec, et il est dit. Une réunion vide en silence serait pire.
                Meeting failed = meetings.failTranscript(userId, hostId, meetingId, failure);
                return ResponseEntity.ok(Map.of("transcriptStatus", failed.getTranscriptStatus().name()));
            }
            Meeting updated = meetings.attachTranscript(userId, hostId, meetingId, text);
            return ResponseEntity.ok(Map.of("transcriptStatus", updated.getTranscriptStatus().name(),
                    "chars", updated.getTranscript().length()));
        } catch (MeetingNotFoundException e) {
            return refuse(HttpStatus.NOT_FOUND, "Réunion introuvable.");
        } catch (MeetingValidationException e) {
            return refuse(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private static ResponseEntity<Map<String, Object>> refuse(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
