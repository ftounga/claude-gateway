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
 * <b>Le dépôt des images clés d'une réunion</b> (F-128 / SF-128-03) : le deck reconstitué du partage
 * d'écran remonte du runner, une image par appel (comme les moments F-90 — un lot rendrait le refus
 * partiel ambigu). Mêmes gardes que le dépôt audio : jeton runner (401), isolation (404), option Teams
 * (403), terminal Teams (400), bornes taille (413) et plafond par réunion (409).
 */
@RestController
@RequestMapping("/runner/teams/meetings")
public class RunnerMeetingImageController {

    public static final String TOKEN_HEADER = "X-Runner-Token";

    /** Une image clé ramenée à taille raisonnable reste bien en deçà. */
    static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    /** Plafond d'images par réunion : un deck, pas une vidéo image par image. */
    static final int MAX_IMAGES_PER_MEETING = 200;

    private final RunnerTokenAuthenticator authenticator;
    private final WorkspaceService workspaceService;
    private final MeetingMediaService media;
    private final SpaceEntitlementService entitlements;

    public RunnerMeetingImageController(RunnerTokenAuthenticator authenticator,
            WorkspaceService workspaceService, MeetingMediaService media,
            SpaceEntitlementService entitlements) {
        this.authenticator = authenticator;
        this.workspaceService = workspaceService;
        this.media = media;
        this.entitlements = entitlements;
    }

    @PostMapping(value = "/{meetingId}/images", produces = MediaType.APPLICATION_JSON_VALUE)
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
                    "Ce compte n'a pas l'option Teams : les images ne peuvent pas remonter.");
        }

        Workspace workspace;
        try {
            workspace = workspaceService.requireOwned(userId, workspaceId);
        } catch (WorkspaceNotFoundException e) {
            return refuse(HttpStatus.NOT_FOUND, "Terminal introuvable.");
        }
        if (!workspace.isTeamsTerminal()) {
            return refuse(HttpStatus.BAD_REQUEST,
                    "Ce workspace n'est pas un terminal Teams : on n'y dépose pas d'images de réunion.");
        }
        if (content == null || content.length == 0) {
            return refuse(HttpStatus.BAD_REQUEST, "Image vide.");
        }
        if (content.length > MAX_IMAGE_BYTES) {
            return refuse(HttpStatus.PAYLOAD_TOO_LARGE, "Image trop lourde : "
                    + content.length + " octets pour un maximum de " + MAX_IMAGE_BYTES + ".");
        }
        if (media.countImages(userId, workspace.getHostId(), meetingId) >= MAX_IMAGES_PER_MEETING) {
            return refuse(HttpStatus.CONFLICT, "Cette réunion porte déjà "
                    + MAX_IMAGES_PER_MEETING + " images clés.");
        }
        try {
            Meeting updated = media.storeImage(userId, workspace.getHostId(), meetingId, contentType, content);
            return ResponseEntity.ok(Map.of("imageCount",
                    updated.getImageCount() == null ? 0 : updated.getImageCount()));
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
