package fr.claudegateway.teams.meeting;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.runner.exec.RunnerTargets;
import fr.claudegateway.teams.TeamsToolCatalog;
import fr.claudegateway.teams.meeting.dto.CreateMeetingRequest;
import fr.claudegateway.teams.meeting.dto.MeetingResponse;

/**
 * Le service de l'artefact réunion (F-128 / SF-128-01) — <b>Gateway-First</b> : il orchestre
 * (crée l'artefact, ordonne au runner de rejoindre l'onglet dans le Chrome managé, tient l'isolation),
 * il ne capture ni ne transcrit lui-même.
 *
 * <p><b>Isolation.</b> Toute méthode reçoit un {@link RadarScope} déjà résolu (possession + activation
 * Vigie contrôlées par le controller) ; toute lecture/écriture est filtrée par {@code user_id} ET
 * {@code host_id}.</p>
 *
 * <p><b>Drapeau SF-128-01.</b> L'ordre runner {@code teams_meeting_join} <b>ouvre et navigue</b> l'onglet
 * Teams du Chrome managé vers l'URL de la réunion (§2bis). La capture des octets média (audio onglet +
 * micro) arrive en SF-128-02 ; ici {@code RECORDING} = « session ouverte / onglet rejoint ».</p>
 */
@Service
public class TeamsMeetingService {

    private static final Logger log = LoggerFactory.getLogger(TeamsMeetingService.class);

    private final MeetingRepository repository;
    private final WorkspaceService workspaceService;
    private final RunnerToolGateway runnerToolGateway;
    private final ObjectMapper objectMapper;

    public TeamsMeetingService(MeetingRepository repository, WorkspaceService workspaceService,
            RunnerToolGateway runnerToolGateway, ObjectMapper objectMapper) {
        this.repository = repository;
        this.workspaceService = workspaceService;
        this.runnerToolGateway = runnerToolGateway;
        this.objectMapper = objectMapper;
    }

    /**
     * <b>« Rejoindre »</b> (F-128 / SF-128-16, 1ᵉʳ temps) : valide, ordonne au runner de rejoindre l'onglet
     * dans le Chrome managé (le runner clique « Rejoindre maintenant » puis détecte le <b>vrai</b> in-call),
     * puis — seulement en cas de succès — persiste l'artefact en {@link MeetingState#JOINED} (aucune ligne
     * fantôme si le runner échoue). <b>L'enregistrement ne démarre pas ici</b> : il est lancé au 2ᵉ temps
     * ({@link #startCapture}), une fois l'in-call confirmé — pour que le capteur survive.
     */
    public MeetingResponse create(RadarScope scope, CreateMeetingRequest request) {
        String url = normalizeUrl(request.meetingUrl());
        String title = normalizeTitle(request.title());
        int retentionDays = resolveRetention(request.retentionDays());
        requireConsent(request.consentAcknowledged());

        // Le terminal Teams du poste : l'ancre CDP vers le Chrome managé (F-89 / F-122).
        Workspace teamsTerminal = workspaceService.openTeamsTerminal(scope.userId(), scope.hostId());
        RunnerTarget target = RunnerTargets.of(teamsTerminal);
        String callId = UUID.randomUUID().toString();

        ObjectNode input = objectMapper.createObjectNode();
        input.put("url", url);
        input.put("purpose", "meeting");
        input.put("participants_informed", true);
        RunnerCallResult result = runnerToolGateway.teamsRead(target, callId, TeamsToolCatalog.MEETING_JOIN, input);
        if (!result.ok()) {
            throw mapRunnerFailure(result);
        }

        Meeting meeting = Meeting.builder()
                .userId(scope.userId())
                .hostId(scope.hostId())
                .subjectId(request.subjectId())
                .title(title)
                .meetingUrl(url)
                .state(MeetingState.JOINED)
                .consentAcknowledged(true)
                .inCall(readInCall(result))
                .retentionDays(retentionDays)
                .captureRef(readCaptureRef(result))
                .startedAt(OffsetDateTimeProvider.now())
                .build();
        return MeetingResponse.of(repository.save(meeting));
    }

    /**
     * <b>« Démarrer l'enregistrement »</b> (F-128 / SF-128-16, 2ᵉ temps) : sur une réunion déjà
     * {@link MeetingState#JOINED} (donc in-call, page stabilisée), ordonne au runner de démarrer la
     * capture d'onglet (audio réunion + micro). Comme il n'y a plus de navigation après, le capteur
     * <b>survit</b> — c'est le cœur du correctif. Passe {@code JOINED → RECORDING} en cas de succès ;
     * un échec runner est remonté (la réunion reste {@code JOINED}, l'utilisateur peut réessayer).
     */
    public MeetingResponse startCapture(RadarScope scope, UUID meetingId) {
        Meeting meeting = require(scope, meetingId);
        if (meeting.getState() != MeetingState.JOINED) {
            throw new MeetingStateException(
                    "L'enregistrement ne peut démarrer que sur une réunion rejointe et en cours.");
        }
        Workspace teamsTerminal = workspaceService.openTeamsTerminal(scope.userId(), scope.hostId());
        RunnerTarget target = RunnerTargets.of(teamsTerminal);
        ObjectNode input = objectMapper.createObjectNode();
        input.put("meeting_id", meetingId.toString());
        RunnerCallResult result;
        try {
            result = runnerToolGateway.teamsRead(target, UUID.randomUUID().toString(),
                    TeamsToolCatalog.MEETING_CAPTURE_START, input);
        } catch (RuntimeException e) {
            log.info("Capture non démarrée pour la réunion {} (runner)", meetingId);
            throw new MeetingCaptureException(MeetingCaptureException.MANAGED_CHROME_UNREACHABLE,
                    "Impossible de démarrer l'enregistrement dans le Chrome managé : ouvrez la Vigie sur "
                            + "ce poste et réessayez.");
        }
        if (!result.ok()) {
            throw mapRunnerFailure(result);
        }
        meeting.setState(MeetingState.RECORDING);
        return MeetingResponse.of(repository.save(meeting));
    }

    /** Arrête la capture : passe STOPPED et horodate la fin. (Ordre runner d'arrêt = SF-128-02.) */
    public MeetingResponse stop(RadarScope scope, UUID meetingId) {
        Meeting meeting = require(scope, meetingId);
        if (meeting.getState() == MeetingState.STOPPED) {
            throw new MeetingStateException("La réunion est déjà terminée.");
        }
        meeting.setState(MeetingState.STOPPED);
        meeting.setEndedAt(OffsetDateTimeProvider.now());
        Meeting saved = repository.save(meeting);
        // SF-128-02 : arrête la capture et déclenche la remontée de l'audio. Le runner téléverse
        // PENDANT cet appel (dépôt qui renseigne audio_key sur la ligne déjà STOPPED) ; on recharge
        // ensuite pour rendre l'artefact avec son audio. Best-effort : un échec n'empêche pas l'arrêt.
        stopCapture(scope, saved.getId());
        return MeetingResponse.of(require(scope, saved.getId()));
    }

    private void stopCapture(RadarScope scope, UUID meetingId) {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("meeting_id", meetingId.toString());
        try {
            Workspace teamsTerminal = workspaceService.openTeamsTerminal(scope.userId(), scope.hostId());
            RunnerTarget target = RunnerTargets.of(teamsTerminal);
            RunnerCallResult result = runnerToolGateway.teamsRead(target, UUID.randomUUID().toString(),
                    TeamsToolCatalog.MEETING_CAPTURE_STOP, input);
            if (!result.ok()) {
                log.info("Arrêt de capture sans remontée pour la réunion {} : {}", meetingId,
                        result.errorCode());
            }
        } catch (RuntimeException e) {
            log.info("Arrêt de capture sans remontée pour la réunion {} (runner)", meetingId);
        }
    }

    /** Met la capture en pause (RECORDING → PAUSED). */
    public MeetingResponse pause(RadarScope scope, UUID meetingId) {
        Meeting meeting = require(scope, meetingId);
        if (meeting.getState() != MeetingState.RECORDING) {
            throw new MeetingStateException("Seule une réunion en cours d'enregistrement peut être mise en pause.");
        }
        meeting.setState(MeetingState.PAUSED);
        return MeetingResponse.of(repository.save(meeting));
    }

    /** Reprend une capture en pause (PAUSED → RECORDING). */
    public MeetingResponse resume(RadarScope scope, UUID meetingId) {
        Meeting meeting = require(scope, meetingId);
        if (meeting.getState() != MeetingState.PAUSED) {
            throw new MeetingStateException("Seule une réunion en pause peut être reprise.");
        }
        meeting.setState(MeetingState.RECORDING);
        return MeetingResponse.of(repository.save(meeting));
    }

    /** Les réunions du poste courant, de la plus récente à la plus ancienne. */
    public List<MeetingResponse> list(RadarScope scope) {
        return repository.findByUserIdAndHostIdOrderByStartedAtDesc(scope.userId(), scope.hostId())
                .stream().map(MeetingResponse::of).toList();
    }

    /** Une réunion du poste courant (404 si inconnue ou hors périmètre). */
    public MeetingResponse get(RadarScope scope, UUID meetingId) {
        return MeetingResponse.of(require(scope, meetingId));
    }

    private Meeting require(RadarScope scope, UUID meetingId) {
        return repository.findByIdAndUserIdAndHostId(meetingId, scope.userId(), scope.hostId())
                .orElseThrow(() -> new MeetingNotFoundException("Réunion introuvable : " + meetingId));
    }

    // ------------------------------------------------------------------ validation & mapping

    private static String normalizeUrl(String raw) {
        String url = raw == null ? "" : raw.strip();
        if (url.isEmpty()) {
            throw new MeetingValidationException("L'URL de la réunion est obligatoire.");
        }
        if (url.length() > Meeting.MAX_URL_LENGTH) {
            throw new MeetingValidationException("L'URL de la réunion est trop longue.");
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new MeetingValidationException("L'URL de la réunion doit être une adresse web (http/https).");
        }
        return url;
    }

    private static String normalizeTitle(String raw) {
        if (raw == null) {
            return null;
        }
        String title = raw.strip();
        if (title.isEmpty()) {
            return null;
        }
        if (title.length() > Meeting.MAX_TITLE_LENGTH) {
            throw new MeetingValidationException("Le titre de la réunion est trop long.");
        }
        return title;
    }

    private static int resolveRetention(Integer requested) {
        if (requested == null) {
            return Meeting.DEFAULT_RETENTION_DAYS;
        }
        if (requested < Meeting.MIN_RETENTION_DAYS || requested > Meeting.MAX_RETENTION_DAYS) {
            throw new MeetingValidationException("La durée de conservation doit être comprise entre "
                    + Meeting.MIN_RETENTION_DAYS + " et " + Meeting.MAX_RETENTION_DAYS + " jours.");
        }
        return requested;
    }

    private static void requireConsent(Boolean consent) {
        if (consent == null || !consent) {
            throw new MeetingValidationException(
                    "Confirmez que vous avez prévenu les participants avant de capturer la réunion.");
        }
    }

    private static MeetingCaptureException mapRunnerFailure(RunnerCallResult result) {
        String code = result.errorCode() == null ? "" : result.errorCode();
        if (code.contains("runner_unavailable") || code.contains("runner_not_on_this_node")
                || code.contains("runner_offline")) {
            return new MeetingCaptureException(MeetingCaptureException.RUNNER_UNAVAILABLE,
                    "Le poste n'est pas joignable : lancez le runner puis réessayez.");
        }
        return new MeetingCaptureException(MeetingCaptureException.MANAGED_CHROME_UNREACHABLE,
                "Impossible de rejoindre la réunion dans le Chrome managé : ouvrez la Vigie sur ce poste "
                        + "et relancez « Rejoindre & capturer ».");
    }

    /**
     * Lit l'état {@code inCall} rendu par le runner au join (F-128 / SF-128-16). Best-effort : un
     * contenu illisible ou sans le champ vaut « pas confirmé in-call » ({@code false}).
     */
    private boolean readInCall(RunnerCallResult result) {
        String content = result.content();
        if (content == null || content.isBlank()) {
            return false;
        }
        try {
            return objectMapper.readTree(content).path("inCall").asBoolean(false);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return false;
        }
    }

    private static String readCaptureRef(RunnerCallResult result) {
        String content = result.content();
        if (content == null || content.isBlank() || content.length() > Meeting.MAX_CAPTURE_REF_LENGTH) {
            return null;
        }
        return content;
    }

    /** Petit indirection horaire, pour des tests déterministes si besoin ; par défaut l'horloge système. */
    static final class OffsetDateTimeProvider {
        private OffsetDateTimeProvider() {
        }

        static java.time.OffsetDateTime now() {
            return java.time.OffsetDateTime.now();
        }
    }
}
