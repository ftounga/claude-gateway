package fr.claudegateway.teams.meeting;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.teams.meeting.MeetingMediaService.StoredMedia;
import fr.claudegateway.teams.meeting.dto.MeetingResponse;
import fr.claudegateway.teams.meeting.stt.TranscriptionProperties;
import fr.claudegateway.teams.meeting.stt.TranscriptionProvider;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderException;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderUnavailableException;

/**
 * La transcription d'une réunion (F-128 / SF-128-04) — <b>Gateway-First</b> : la gateway <b>orchestre</b>
 * (enfile la demande, réclame le travail, lit l'audio, stocke le transcript), elle ne transcrit pas.
 *
 * <p><b>Asynchrone.</b> {@link #requestTranscription} n'émet <b>aucun appel STT</b> : elle valide,
 * garde l'opt-in (STT configuré), et passe la réunion en {@code PENDING}. Le travail lourd (l'appel au
 * service) vit dans {@link #transcribePending()}, exécuté par le worker hors du thread HTTP.</p>
 *
 * <p><b>Isolation.</b> Les gestes utilisateur reçoivent un {@link RadarScope} résolu ; toute lecture est
 * filtrée {@code user_id} + {@code host_id}. Le worker traite par statut et lit l'audio à la clé du
 * couple de la réunion (isolation par construction).</p>
 */
@Service
public class TranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionService.class);

    /** Borne défensive de la taille du transcript stocké (le clob reste raisonnable). */
    static final int MAX_TRANSCRIPT_CHARS = 500_000;

    private final MeetingRepository repository;
    private final MeetingMediaService media;
    private final TranscriptionProvider provider;
    private final TranscriptionProperties properties;

    public TranscriptionService(MeetingRepository repository, MeetingMediaService media,
            TranscriptionProvider provider, TranscriptionProperties properties) {
        this.repository = repository;
        this.media = media;
        this.provider = provider;
        this.properties = properties;
    }

    /**
     * Déclenche la transcription d'une réunion capturée (geste utilisateur, opt-in).
     *
     * @throws MeetingNotFoundException                    réunion inconnue / hors périmètre
     * @throws MeetingStateException                       la réunion n'a pas d'audio à transcrire
     * @throws TranscriptionProviderUnavailableException   STT non configuré (rien n'est enfilé, aucun appel)
     */
    public MeetingResponse requestTranscription(RadarScope scope, UUID meetingId) {
        Meeting meeting = require(scope, meetingId);
        if (meeting.getAudioKey() == null) {
            throw new MeetingStateException("Cette réunion n'a pas d'audio à transcrire.");
        }
        if (meeting.getTranscriptStatus() != null && meeting.getTranscriptStatus().isInFlight()) {
            return MeetingResponse.of(meeting); // idempotent : une transcription est déjà en route
        }
        if (!properties.isConfigured()) {
            // DRAPEAU FORT : sans service STT configuré, rien n'est enfilé et aucun octet ne part.
            throw new TranscriptionProviderUnavailableException(
                    "STT non configuré : la transcription est désactivée tant qu'aucun service n'est paramétré.");
        }
        meeting.setTranscriptStatus(TranscriptStatus.PENDING);
        meeting.setTranscriptError(null);
        return MeetingResponse.of(repository.save(meeting));
    }

    /** Le texte du transcript d'une réunion du propriétaire, ou vide (→ 404 côté controller). */
    public Optional<String> transcript(RadarScope scope, UUID meetingId) {
        Meeting meeting = require(scope, meetingId);
        return Optional.ofNullable(meeting.getTranscript()).filter(t -> !t.isBlank());
    }

    /**
     * Le travail lourd, hors thread HTTP : réclame les réunions {@code PENDING}, appelle le service STT,
     * rattache le transcript. Chaque réunion est isolée dans son propre {@code try} — un échec n'arrête
     * pas le lot. Rend le nombre de réunions traitées.
     */
    public int transcribePending() {
        var pending = repository.findByTranscriptStatus(TranscriptStatus.PENDING);
        int processed = 0;
        for (Meeting meeting : pending) {
            // Réclame la ligne avant de travailler (comme IngestionService) : un seul passage la prend.
            meeting.setTranscriptStatus(TranscriptStatus.TRANSCRIBING);
            repository.save(meeting);
            try {
                transcribeOne(meeting);
                processed++;
            } catch (RuntimeException e) {
                // Ne devrait pas remonter (transcribeOne encapsule) — filet ultime pour ne pas tuer le lot.
                log.warn("Transcription interrompue pour la réunion {}", meeting.getId());
                fail(meeting, "Échec inattendu de la transcription.");
            }
        }
        return processed;
    }

    private void transcribeOne(Meeting meeting) {
        Optional<StoredMedia> audio = media.findAudio(meeting.getUserId(), meeting.getHostId(), meeting.getId());
        if (audio.isEmpty()) {
            fail(meeting, "Audio de la réunion introuvable pour la transcription.");
            return;
        }
        try {
            TranscriptionProvider.Transcript result =
                    provider.transcribe(audio.get().content(), audio.get().contentType(), properties.language());
            meeting.setTranscript(cut(result.text()));
            meeting.setTranscriptLang(result.language());
            meeting.setTranscriptError(null);
            meeting.setTranscriptStatus(TranscriptStatus.TRANSCRIBED);
            repository.save(meeting);
        } catch (TranscriptionProviderUnavailableException e) {
            fail(meeting, "STT non configuré.");
        } catch (TranscriptionProviderException e) {
            fail(meeting, "Le service de transcription a échoué : réessayez plus tard.");
        }
    }

    private void fail(Meeting meeting, String message) {
        meeting.setTranscriptStatus(TranscriptStatus.FAILED);
        meeting.setTranscriptError(message);
        repository.save(meeting);
    }

    private Meeting require(RadarScope scope, UUID meetingId) {
        return repository.findByIdAndUserIdAndHostId(meetingId, scope.userId(), scope.hostId())
                .orElseThrow(() -> new MeetingNotFoundException("Réunion introuvable : " + meetingId));
    }

    private static String cut(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_TRANSCRIPT_CHARS ? text : text.substring(0, MAX_TRANSCRIPT_CHARS);
    }
}
