package fr.claudegateway.teams.meeting;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarScopeResolver;
import fr.claudegateway.teams.TeamsAccessService;
import fr.claudegateway.teams.meeting.MeetingMediaService.StoredMedia;

/**
 * <b>La lecture des médias d'une réunion</b> (F-128 / SF-128-10) : l'audio (écoute/streaming +
 * téléchargement) et les images clés (deck reconstitué) d'une réunion capturée, servis à leur
 * propriétaire. Miroir de lecture du {@code TeamsMomentController} (F-89), mais rattaché à la réunion.
 *
 * <p><b>Aucun dépôt ici</b> : les octets sont produits sur la machine et remontés par les routes runner
 * ({@code RunnerMeetingAudio/ImageController}) ; ce controller n'ouvre que des {@code GET}.</p>
 *
 * <p><b>L'isolation est double.</b> Mêmes gardes que les autres API Réunions : droit Teams (403),
 * possession du poste + activation Vigie via {@link RadarScopeResolver#requireInVigie} (404/409) ; puis
 * la réunion et ses médias sont résolus par {@code (id, userId, hostId)} — une réunion d'un autre couple
 * est introuvable (404 indiscernable). L'identifiant d'image n'est qu'un dernier segment, jamais un
 * chemin (anti-traversée dans {@link MeetingMediaService}).</p>
 *
 * <p><b>Range.</b> L'audio supporte les requêtes {@code Range} (206 + {@code Content-Range}) pour la
 * lecture progressive, servi depuis les octets stockés (l'audio de réunion est borné à 200 Mo au dépôt).</p>
 */
@RestController
@RequestMapping("/vigie/hosts/{hostId}/meetings/{meetingId}")
public class TeamsMeetingMediaController {

    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final MeetingMediaService media;
    private final RadarScopeResolver scopeResolver;
    private final TeamsAccessService teamsAccess;
    private final CurrentUser currentUser;

    public TeamsMeetingMediaController(MeetingMediaService media, RadarScopeResolver scopeResolver,
            TeamsAccessService teamsAccess, CurrentUser currentUser) {
        this.media = media;
        this.scopeResolver = scopeResolver;
        this.teamsAccess = teamsAccess;
        this.currentUser = currentUser;
    }

    /** L'audio de la réunion : écoute (Range) par défaut, pièce jointe si {@code download=1}. */
    @GetMapping("/audio")
    public ResponseEntity<byte[]> audio(@PathVariable UUID hostId, @PathVariable UUID meetingId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
            @RequestParam(value = "download", required = false) boolean download) {
        RadarScope scope = scope(hostId);
        return media.findAudio(scope.userId(), scope.hostId(), meetingId)
                .map(audio -> serve(audio, range, download, "reunion-" + meetingId + audioSuffix(audio)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Les identifiants des images clés de la réunion (deck), pour construire les URLs côté front. */
    @GetMapping("/images")
    public Map<String, List<String>> images(@PathVariable UUID hostId, @PathVariable UUID meetingId) {
        RadarScope scope = scope(hostId);
        return Map.of("imageIds", media.listFrames(scope.userId(), scope.hostId(), meetingId));
    }

    /** Une image clé de la réunion, ou {@code 404}. */
    @GetMapping("/images/{imageId}")
    public ResponseEntity<byte[]> image(@PathVariable UUID hostId, @PathVariable UUID meetingId,
            @PathVariable String imageId) {
        RadarScope scope = scope(hostId);
        return media.findFrame(scope.userId(), scope.hostId(), meetingId, imageId)
                .map(frame -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(frame.contentType()))
                        .cacheControl(CacheControl.maxAge(CACHE_TTL).cachePrivate())
                        .body(frame.content()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ------------------------------------------------------------------ Range & garde d'accès

    private ResponseEntity<byte[]> serve(StoredMedia audio, String range, boolean download, String filename) {
        byte[] data = audio.content();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(audio.contentType()));
        headers.setCacheControl(CacheControl.maxAge(CACHE_TTL).cachePrivate().getHeaderValue());
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        if (download) {
            headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
        }

        List<HttpRange> ranges = parseRanges(range);
        if (ranges.isEmpty()) {
            headers.setContentLength(data.length);
            return new ResponseEntity<>(data, headers, HttpStatus.OK);
        }

        HttpRange first = ranges.get(0);
        long start = first.getRangeStart(data.length);
        long end = first.getRangeEnd(data.length);
        if (start >= data.length) {
            HttpHeaders unsatisfiable = new HttpHeaders();
            unsatisfiable.set(HttpHeaders.CONTENT_RANGE, "bytes */" + data.length);
            return new ResponseEntity<>(unsatisfiable, HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        }
        byte[] slice = Arrays.copyOfRange(data, (int) start, (int) end + 1);
        headers.setContentLength(slice.length);
        headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + data.length);
        return new ResponseEntity<>(slice, headers, HttpStatus.PARTIAL_CONTENT);
    }

    /** Un {@code Range} mal formé ou absent est traité comme « pas de Range » (réponse complète). */
    private static List<HttpRange> parseRanges(String range) {
        if (range == null || range.isBlank()) {
            return List.of();
        }
        try {
            return HttpRange.parseRanges(range);
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
    }

    private static String audioSuffix(StoredMedia audio) {
        return switch (audio.contentType()) {
            case "audio/webm" -> ".webm";
            case "audio/ogg" -> ".ogg";
            case "audio/mp4" -> ".m4a";
            case "audio/mpeg" -> ".mp3";
            case "audio/wav" -> ".wav";
            default -> "";
        };
    }

    private RadarScope scope(UUID hostId) {
        teamsAccess.requireAccess();
        return scopeResolver.requireInVigie(currentUser.requireId(), hostId);
    }
}
