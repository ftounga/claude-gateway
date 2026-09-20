package fr.claudegateway.teams.meeting;

import java.io.IOException;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarScopeResolver;
import fr.claudegateway.teams.TeamsAccessService;
import fr.claudegateway.teams.meeting.dto.MeetingResponse;
import fr.claudegateway.teams.meeting.dto.SetExternalTranscriptRequest;

/**
 * La <b>transcription externe (client)</b> d'une réunion (F-128 / SF-128-20a) : l'attacher par
 * <b>collage</b> ({@code PUT}, JSON) ou par <b>dépôt de fichier</b> ({@code POST}, multipart
 * {@code .txt}/{@code .vtt}/{@code .docx}), la lire ({@code GET} texte), la retirer ({@code DELETE}).
 *
 * <p>Mêmes gardes que les autres API Réunions : droit Teams (403), possession du poste + activation
 * Vigie (404/409) via {@link RadarScopeResolver#requireInVigie}. Aucune logique métier ici : tout est
 * dans {@link ExternalTranscriptService}. v1 : une seule transcription externe par réunion, remplaçable.</p>
 */
@RestController
@RequestMapping("/vigie/hosts/{hostId}/meetings/{meetingId}/external-transcript")
public class TeamsMeetingExternalTranscriptController {

    private final ExternalTranscriptService service;
    private final RadarScopeResolver scopeResolver;
    private final TeamsAccessService teamsAccess;
    private final CurrentUser currentUser;

    public TeamsMeetingExternalTranscriptController(ExternalTranscriptService service,
            RadarScopeResolver scopeResolver, TeamsAccessService teamsAccess, CurrentUser currentUser) {
        this.service = service;
        this.scopeResolver = scopeResolver;
        this.teamsAccess = teamsAccess;
        this.currentUser = currentUser;
    }

    /** Coller du texte : attache (ou remplace) la transcription externe. */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public MeetingResponse paste(@PathVariable UUID hostId, @PathVariable UUID meetingId,
            @RequestBody SetExternalTranscriptRequest request) {
        String text = request == null ? null : request.text();
        String source = request == null ? null : request.source();
        return service.attachText(scope(hostId), meetingId, text, source);
    }

    /** Déposer un fichier {@code .txt}/{@code .vtt}/{@code .docx} : attache (ou remplace). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MeetingResponse upload(@PathVariable UUID hostId, @PathVariable UUID meetingId,
            @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new MeetingValidationException("Le fichier de transcription est vide.");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new MeetingValidationException("Le fichier de transcription n'a pas pu être lu.");
        }
        return service.attachFile(scope(hostId), meetingId, file.getOriginalFilename(), content);
    }

    /** Le texte de la transcription externe, ou 404 si la réunion n'en a pas. */
    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> get(@PathVariable UUID hostId, @PathVariable UUID meetingId) {
        return service.externalTranscript(scope(hostId), meetingId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Retire la transcription externe (idempotent). */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable UUID hostId, @PathVariable UUID meetingId) {
        service.clear(scope(hostId), meetingId);
    }

    private RadarScope scope(UUID hostId) {
        teamsAccess.requireAccess();
        return scopeResolver.requireInVigie(currentUser.requireId(), hostId);
    }
}
