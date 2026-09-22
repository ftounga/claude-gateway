package fr.claudegateway.teams.meeting;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.radar.RadarRegistry;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarSubject;

/**
 * <b>La réunion née d'un enregistrement déposé</b> (F-147 / SF-147-02).
 *
 * <p>Jusqu'ici, le texte d'un enregistrement remontait en <b>échanges Radar</b> : une preuve parmi
 * d'autres, donc ni résumé, ni décisions, ni actions, ni Q&amp;A, ni promotion vers la carte — alors
 * que tout cela existe déjà pour une <b>réunion</b>. Ce service fait du dépôt une réunion, rattachée
 * au <b>sujet choisi au moment du geste</b>, et laisse l'exploitation à ce qui la fait déjà
 * ({@link MeetingExploitationService}) : rien n'est réimplémenté ici.</p>
 *
 * <p><b>Le sujet n'est jamais cru sur parole.</b> Il voyage jusqu'au poste et en revient — mais il est
 * revalidé ici, dans le périmètre de l'appelant ({@link RadarRegistry#requireLiveSubject}), avant que
 * la moindre ligne soit écrite. Un sujet d'un autre compte ou d'un autre poste est <b>introuvable</b>.</p>
 *
 * <p><b>Pas d'URL.</b> Une réunion venue d'un enregistrement n'a pas d'adresse de visio : elle a eu
 * lieu ailleurs. La colonne est nullable depuis la migration {@code 124} et l'absence <b>est</b>
 * l'information.</p>
 */
@Service
public class RecordingMeetingService {

    /** Au-delà, le texte n'est pas rangé tel quel : une réunion de huit heures pèse bien moins. */
    public static final int MAX_TRANSCRIPT_CHARS = 2_000_000;
    /** La source écrite sur la réunion : elle dit d'où vient ce texte, et qu'il n'est pas sorti du poste. */
    public static final String SOURCE = "Enregistrement déposé, transcrit sur le poste";

    private final MeetingRepository repository;
    private final RadarRegistry registry;

    public RecordingMeetingService(MeetingRepository repository, RadarRegistry registry) {
        this.repository = repository;
        this.registry = registry;
    }

    /**
     * Le sujet, <b>revalidé dans le périmètre de l'appelant</b> : c'est la seule porte par laquelle un
     * identifiant de sujet venu de l'écran — ou revenu du poste — devient utilisable.
     *
     * @throws fr.claudegateway.radar.RadarNotFoundException sujet inconnu, clos ou hors périmètre
     */
    public UUID requireLiveSubjectId(RadarScope scope, UUID subjectId) {
        return registry.requireLiveSubject(scope, subjectId).getId();
    }

    /**
     * Crée la réunion d'un enregistrement qui vient d'arriver sur le poste.
     *
     * @throws fr.claudegateway.radar.RadarNotFoundException sujet inconnu, clos ou hors périmètre
     */
    @Transactional
    public Meeting create(RadarScope scope, UUID subjectId, String title, OffsetDateTime recordedAt) {
        RadarSubject subject = registry.requireLiveSubject(scope, subjectId);
        Meeting meeting = Meeting.builder()
                .userId(scope.userId())
                .hostId(scope.hostId())
                .subjectId(subject.getId())
                .title(cutTitle(title))
                .meetingUrl(null)
                .state(MeetingState.STOPPED)
                // L'enregistrement a été fait par le client, avant nous : le consentement ne se rejoue
                // pas ici, et la gateway n'a capté quoi que ce soit à aucun moment.
                .consentAcknowledged(true)
                .retentionDays(Meeting.DEFAULT_RETENTION_DAYS)
                .startedAt(recordedAt)
                .endedAt(recordedAt)
                .transcriptStatus(TranscriptStatus.PENDING)
                .build();
        return repository.save(meeting);
    }

    /**
     * Range le texte transcrit <b>sur le poste</b> dans la réunion. Appelé par la route runner : le
     * couple compte/poste vient du <b>jeton</b>, jamais de la requête.
     *
     * @throws MeetingNotFoundException   réunion inconnue ou hors du couple du jeton
     * @throws MeetingValidationException texte vide — une réunion vide en silence serait pire que rien
     */
    @Transactional
    public Meeting attachTranscript(UUID userId, UUID hostId, UUID meetingId, String rawText) {
        Meeting meeting = require(userId, hostId, meetingId);
        String text = rawText == null ? "" : rawText.strip();
        if (text.isEmpty()) {
            throw new MeetingValidationException("Le texte transcrit est vide.");
        }
        meeting.setTranscript(text.length() > MAX_TRANSCRIPT_CHARS
                ? text.substring(0, MAX_TRANSCRIPT_CHARS) : text);
        meeting.setTranscriptStatus(TranscriptStatus.TRANSCRIBED);
        meeting.setTranscriptError(null);
        meeting.setExternalTranscriptSource(SOURCE);
        return repository.save(meeting);
    }

    /**
     * Note que la transcription n'a pas abouti. La réunion <b>reste</b> : c'est elle qui porte l'échec,
     * et le fichier, lui, n'a pas bougé de la machine.
     */
    @Transactional
    public Meeting failTranscript(UUID userId, UUID hostId, UUID meetingId, String why) {
        Meeting meeting = require(userId, hostId, meetingId);
        meeting.setTranscriptStatus(TranscriptStatus.FAILED);
        meeting.setTranscriptError(cutError(why));
        return repository.save(meeting);
    }

    private Meeting require(UUID userId, UUID hostId, UUID meetingId) {
        return repository.findByIdAndUserIdAndHostId(meetingId, userId, hostId)
                .orElseThrow(() -> new MeetingNotFoundException("Réunion introuvable : " + meetingId));
    }

    private static String cutTitle(String title) {
        String value = title == null ? "" : title.strip();
        return value.length() > Meeting.MAX_TITLE_LENGTH ? value.substring(0, Meeting.MAX_TITLE_LENGTH) : value;
    }

    private static String cutError(String why) {
        String value = why == null || why.isBlank() ? "La transcription n'a pas abouti sur le poste." : why.strip();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
