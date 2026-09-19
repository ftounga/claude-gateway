package fr.claudegateway.teams.meeting;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarScopeResolver;
import fr.claudegateway.teams.TeamsAccessService;
import fr.claudegateway.teams.meeting.dto.MeetingResponse;

/**
 * La <b>transcription</b> d'une réunion (F-128 / SF-128-04) : la déclencher (opt-in) et lire le texte.
 *
 * <p>Mêmes gardes que les autres API Réunions : droit Teams (403), possession du poste + activation
 * Vigie (404/409) via {@link RadarScopeResolver#requireInVigie}. Aucune logique métier ici : tout est
 * dans {@link TranscriptionService}. La transcription est <b>asynchrone</b> (le déclenchement enfile,
 * le worker transcrit) et <b>éteinte par défaut</b> (503 {@code stt_not_configured} tant qu'aucun
 * service STT n'est paramétré).</p>
 */
@RestController
@RequestMapping("/vigie/hosts/{hostId}/meetings/{meetingId}")
public class TeamsMeetingTranscriptionController {

    private final TranscriptionService transcription;
    private final RadarScopeResolver scopeResolver;
    private final TeamsAccessService teamsAccess;
    private final CurrentUser currentUser;

    public TeamsMeetingTranscriptionController(TranscriptionService transcription,
            RadarScopeResolver scopeResolver, TeamsAccessService teamsAccess, CurrentUser currentUser) {
        this.transcription = transcription;
        this.scopeResolver = scopeResolver;
        this.teamsAccess = teamsAccess;
        this.currentUser = currentUser;
    }

    /** Déclenche la transcription (enfile ; le worker fait l'appel STT). 202 accepté. */
    @PostMapping("/transcribe")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MeetingResponse transcribe(@PathVariable UUID hostId, @PathVariable UUID meetingId) {
        return transcription.requestTranscription(scope(hostId), meetingId);
    }

    /** Le texte du transcript, ou 404 si la réunion n'en a pas (encore). */
    @GetMapping(value = "/transcript", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> transcript(@PathVariable UUID hostId, @PathVariable UUID meetingId) {
        return transcription.transcript(scope(hostId), meetingId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private RadarScope scope(UUID hostId) {
        teamsAccess.requireAccess();
        return scopeResolver.requireInVigie(currentUser.requireId(), hostId);
    }
}
