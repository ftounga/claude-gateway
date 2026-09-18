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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.SpaceEntitlementService;
import fr.claudegateway.runner.RunnerIdentity;
import fr.claudegateway.runner.RunnerTokenAuthenticator;

/**
 * <b>Le dépôt de l'audio d'une réunion</b> (F-128 / SF-128-02) : la seule route par laquelle l'audio
 * capturé (onglet Teams + micro, mixé) remonte du runner à la gateway. Mêmes gardes que le dépôt des
 * moments (F-90) : jeton runner (401 générique), isolation par le compte du jeton (404 indiscernable),
 * option Teams pour <b>produire</b> (403), terminal Teams (400). L'audio est plus lourd qu'une image —
 * la borne est nommée.
 */
@RestController
@RequestMapping("/runner/teams/meetings")
public class RunnerMeetingAudioController {

    /** En-tête portant le jeton runner (jamais {@code Authorization}, D9). */
    public static final String TOKEN_HEADER = "X-Runner-Token";

    /** Borne d'un dépôt audio : un webm/opus d'une longue réunion reste bien en deçà. */
    static final int MAX_AUDIO_BYTES = 200 * 1024 * 1024;

    private final RunnerTokenAuthenticator authenticator;
    private final WorkspaceService workspaceService;
    private final MeetingMediaService media;
    private final SpaceEntitlementService entitlements;

    public RunnerMeetingAudioController(RunnerTokenAuthenticator authenticator,
            WorkspaceService workspaceService, MeetingMediaService media,
            SpaceEntitlementService entitlements) {
        this.authenticator = authenticator;
        this.workspaceService = workspaceService;
        this.media = media;
        this.entitlements = entitlements;
    }

    @PostMapping(value = "/{meetingId}/audio", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> deposit(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @PathVariable UUID meetingId,
            @RequestParam("workspaceId") UUID workspaceId,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestBody byte[] content) {
        Optional<RunnerIdentity> identity = authenticator.authenticate(trim(token));
        if (identity.isEmpty()) {
            return refuse(HttpStatus.UNAUTHORIZED, "Jeton runner refusé.");
        }
        UUID userId = identity.get().userId();

        if (!entitlements.isEntitled(userId, EntitlementSpace.VIGIE)) {
            return refuse(HttpStatus.FORBIDDEN,
                    "Ce compte n'a pas l'option Teams : l'audio de réunion ne peut pas remonter.");
        }

        Workspace workspace;
        try {
            workspace = workspaceService.requireOwned(userId, workspaceId);
        } catch (WorkspaceNotFoundException e) {
            return refuse(HttpStatus.NOT_FOUND, "Terminal introuvable.");
        }
        if (!workspace.isTeamsTerminal()) {
            return refuse(HttpStatus.BAD_REQUEST,
                    "Ce workspace n'est pas un terminal Teams : on n'y dépose pas d'audio de réunion.");
        }
        if (content == null || content.length == 0) {
            return refuse(HttpStatus.BAD_REQUEST, "Audio vide.");
        }
        if (content.length > MAX_AUDIO_BYTES) {
            return refuse(HttpStatus.PAYLOAD_TOO_LARGE, "Audio trop lourd : "
                    + content.length + " octets pour un maximum de " + MAX_AUDIO_BYTES + ".");
        }
        try {
            Meeting updated = media.storeAudio(userId, workspace.getHostId(), meetingId, contentType, content);
            return ResponseEntity.ok(Map.of("audioBytes", updated.getAudioBytes()));
        } catch (MeetingNotFoundException e) {
            return refuse(HttpStatus.NOT_FOUND, "Réunion introuvable.");
        }
    }

    private static ResponseEntity<Map<String, Object>> refuse(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    private static String trim(String token) {
        return token == null ? "" : token.strip();
    }
}
