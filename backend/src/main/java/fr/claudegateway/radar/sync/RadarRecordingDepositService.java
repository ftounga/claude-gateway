package fr.claudegateway.radar.sync;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.radar.InvalidRadarInputException;
import fr.claudegateway.radar.RadarNotFoundException;
import fr.claudegateway.radar.RadarRunnerUnavailableException;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarStateConflictException;
import fr.claudegateway.radar.RadarTeamsDisabledException;
import fr.claudegateway.runner.RunnerLiveness;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.teams.meeting.RecordingMeetingService;

/**
 * <b>Déposer un enregistrement depuis l'écran</b> (F-104 / SF-104-04, cadrage §9) : le fichier va <b>au
 * runner</b> du poste, par morceaux, et n'est jamais stocké par la gateway.
 *
 * <p><b>Sans état.</b> L'identifiant du dépôt est fabriqué ici ; tout le reste — fichier partiel, taille reçue,
 * titre et date — vit sur le runner ({@code RadarDepositReceiver}). Un morceau est lu <b>borné en mémoire</b>,
 * encodé et relayé ; rien n'est écrit sur le disque de la gateway.</p>
 *
 * <p><b>Isolation.</b> La cible est le poste du périmètre, via {@link RadarRunnerCalls}, jamais un identifiant
 * venu de la requête.</p>
 */
@Service
public class RadarRecordingDepositService {

    public static final String DEPOSIT = "teams_radar_deposit";
    public static final long MAX_BYTES = 500L * 1024 * 1024;
    public static final int CHUNK_BYTES = 512 * 1024;
    static final long OPEN_TIMEOUT_MS = 20_000L;
    static final long CHUNK_TIMEOUT_MS = 60_000L;
    static final long FINISH_TIMEOUT_MS = 30_000L;
    static final List<String> EXTENSIONS = List.of(".mp3", ".m4a", ".wav", ".ogg", ".aac", ".flac", ".mp4", ".mov",
            ".mkv", ".webm");

    /**
     * Ce que l'écran demande pour ouvrir un dépôt. Le <b>sujet est obligatoire</b> depuis F-147 /
     * SF-147-02 : c'est au moment du geste que l'utilisateur sait de quel dossier il s'agit.
     */
    public record DepositRequest(String fileName, Long sizeBytes, String title, String recordedAt,
            UUID subjectId) {
    }

    /** Un dépôt ouvert : où envoyer, et par morceaux de quelle taille. */
    public record DepositOpened(UUID uploadId, int chunkBytes, long maxBytes) {
    }

    /** Un morceau reçu par le poste. */
    public record ChunkReceived(long received) {
    }

    /**
     * Un enregistrement déposé dans le dossier du poste, <b>et ce que le poste en fait déjà</b>
     * (F-147 / SF-147-01) : {@code transcription} vaut {@code started}, {@code unavailable} ou
     * {@code refused} ; quand elle a démarré, {@code jobId} sert à suivre l'avancement.
     */
    public record DepositDone(String fileName, String title, String recordedAt, long sizeBytes,
            String transcription, String jobId, String phase, String phaseLabel, UUID meetingId) {
    }

    /**
     * Où en est la transcription d'un dépôt (F-147 / SF-147-01).
     *
     * <p>La phrase d'avancement vient du <b>poste</b> : elle y est écrite une fois, pour être lue par
     * l'utilisateur, et la réécrire ici en ferait une seconde version à maintenir.</p>
     */
    public record RecordingProgress(boolean known, String jobId, String phase, String phaseLabel, boolean over,
            String failure) {
    }

    private final RadarRunnerCalls calls;
    private final RunnerLiveness liveness;
    private final ObjectMapper mapper;
    private final RecordingMeetingService meetings;

    public RadarRecordingDepositService(RadarRunnerCalls calls, RunnerLiveness liveness, ObjectMapper mapper,
            RecordingMeetingService meetings) {
        this.calls = calls;
        this.liveness = liveness;
        this.mapper = mapper;
        this.meetings = meetings;
    }

    /** Ouvre un dépôt sur le poste. */
    public DepositOpened open(RadarScope scope, DepositRequest request) {
        String fileName = request == null || request.fileName() == null ? "" : request.fileName().strip();
        if (fileName.isEmpty() || fileName.length() > 255
                || EXTENSIONS.stream().noneMatch(fileName.toLowerCase(Locale.ROOT)::endsWith)) {
            throw new InvalidRadarInputException("Un enregistrement est un fichier audio ou vidéo ("
                    + String.join(" ", EXTENSIONS) + ").");
        }
        long size = request.sizeBytes() == null ? 0 : request.sizeBytes();
        if (size <= 0 || size > MAX_BYTES) {
            throw new InvalidRadarInputException("Un enregistrement déposé depuis l'écran pèse au plus 500 Mio.");
        }
        String title = request.title() == null ? "" : request.title().strip();
        if (title.isEmpty() || title.length() > 200) {
            throw new InvalidRadarInputException("Le titre de la réunion est requis (200 caractères au plus).");
        }
        OffsetDateTime recordedAt;
        try {
            recordedAt = OffsetDateTime.parse(request.recordedAt() == null ? "" : request.recordedAt().strip());
        } catch (DateTimeParseException e) {
            throw new InvalidRadarInputException("La date et l'heure de la réunion sont requises.");
        }
        if (request.subjectId() == null) {
            throw new InvalidRadarInputException("Le sujet de la réunion est requis : un enregistrement "
                    + "appartient à un dossier.");
        }
        // Le sujet est validé ICI, dans le périmètre de l'appelant : un sujet d'un autre compte ou
        // d'un autre poste est introuvable (404), et rien ne part vers le poste.
        UUID subjectId = meetings.requireLiveSubjectId(scope, request.subjectId());
        requireOnline(scope);
        UUID uploadId = UUID.randomUUID();
        ObjectNode input = base("open", uploadId);
        input.put("file_name", fileName);
        input.put("size", size);
        input.put("title", title);
        input.put("recorded_at", recordedAt.toString());
        input.put("subject_id", subjectId.toString());
        answer(calls.call(scope, DEPOSIT, input, OPEN_TIMEOUT_MS));
        return new DepositOpened(uploadId, CHUNK_BYTES, MAX_BYTES);
    }

    /**
     * Relaie un morceau. Le corps est lu <b>borné</b> : au-delà de {@link #CHUNK_BYTES}, refus sans rien relayer.
     */
    public ChunkReceived chunk(RadarScope scope, UUID uploadId, long offset, InputStream body) {
        if (offset < 0) {
            throw new InvalidRadarInputException("La position du morceau est invalide.");
        }
        byte[] data = readBounded(body);
        requireOnline(scope);
        ObjectNode input = base("chunk", uploadId);
        input.put("offset", offset);
        input.put("data", Base64.getEncoder().encodeToString(data));
        JsonNode answer = answer(calls.relay(scope, DEPOSIT, input, CHUNK_TIMEOUT_MS));
        return new ChunkReceived(answer.path("received").asLong());
    }

    /**
     * Termine le dépôt : le fichier apparaît dans le dossier, avec son titre et sa date, et le poste
     * <b>commence tout de suite</b> à le transcrire (F-147 / SF-147-01) — plus rien n'attend un passage
     * périodique.
     */
    public DepositDone finish(RadarScope scope, UUID uploadId) {
        requireOnline(scope);
        JsonNode answer = answer(calls.call(scope, DEPOSIT, base("finish", uploadId), FINISH_TIMEOUT_MS));
        UUID meetingId = openMeeting(scope, uploadId, answer);
        return new DepositDone(answer.path("file_name").asText(), answer.path("title").asText(),
                answer.path("recorded_at").asText(), answer.path("size").asLong(),
                answer.path("transcription").asText(""), answer.path("job_id").asText(""),
                answer.path("phase").asText(""), answer.path("phase_label").asText(""), meetingId);
    }

    /**
     * La <b>réunion</b> du dépôt (F-147 / SF-147-02) : créée une fois le fichier arrivé — pas avant,
     * sans quoi un transfert abandonné laisserait une réunion fantôme — puis <b>annoncée au poste</b>,
     * qui saura où déposer le texte au terme de la transcription.
     *
     * <p>Le sujet revient du poste : il est <b>revalidé</b> dans le périmètre de l'appelant avant la
     * moindre écriture. Le poste n'a jamais le dernier mot sur le périmètre.</p>
     */
    private UUID openMeeting(RadarScope scope, UUID uploadId, JsonNode answer) {
        String subject = answer.path("subject_id").asText("");
        if (subject.isBlank()) {
            return null;
        }
        UUID meetingId;
        try {
            meetingId = meetings.create(scope, UUID.fromString(subject), answer.path("title").asText(),
                    OffsetDateTime.parse(answer.path("recorded_at").asText())).getId();
        } catch (RuntimeException e) {
            // Le fichier EST arrivé : on ne le perd pas pour une réunion qui n'a pas pu naître.
            return null;
        }
        ObjectNode attach = base("attach", uploadId);
        attach.put("meeting_id", meetingId.toString());
        try {
            answer(calls.call(scope, DEPOSIT, attach, OPEN_TIMEOUT_MS));
        } catch (RuntimeException e) {
            // Le poste n'a pas pris l'adresse : la réunion reste « en attente », elle n'est pas perdue.
            return meetingId;
        }
        return meetingId;
    }

    /**
     * L'avancement de la transcription lancée par {@link #finish}. Poste hors ligne : on ne l'invente pas,
     * on le dit — le travail vit sur la machine, pas ici.
     */
    public RecordingProgress progress(RadarScope scope, UUID uploadId) {
        requireOnline(scope);
        JsonNode answer = answer(calls.call(scope, DEPOSIT, base("status", uploadId), OPEN_TIMEOUT_MS));
        if (!answer.path("known").asBoolean(false)) {
            return new RecordingProgress(false, "", "", "", false, "");
        }
        return new RecordingProgress(true, answer.path("job_id").asText(""), answer.path("phase").asText(""),
                answer.path("phase_label").asText(""), answer.path("over").asBoolean(false),
                answer.path("failure").asText(""));
    }

    /** Abandonne le dépôt ; hors ligne, le poste le purgera à 24 h. */
    public void abort(RadarScope scope, UUID uploadId) {
        if (!liveness.isAlive(scope.userId(), scope.hostId())) {
            return;
        }
        answer(calls.call(scope, DEPOSIT, base("abort", uploadId), OPEN_TIMEOUT_MS));
    }

    // ------------------------------------------------------------------------------------ aides

    private void requireOnline(RadarScope scope) {
        if (!liveness.isAlive(scope.userId(), scope.hostId())) {
            throw new RadarRunnerUnavailableException("Poste hors ligne : lancez le runner, puis recommencez.");
        }
    }

    private ObjectNode base(String op, UUID uploadId) {
        ObjectNode input = mapper.createObjectNode();
        input.put("op", op);
        input.put("upload_id", uploadId.toString());
        return input;
    }

    /** La réponse du poste, ou l'exception qui dit pourquoi il a refusé. */
    private JsonNode answer(RunnerCallResult result) {
        if (result == null || !result.ok()) {
            String code = result == null ? RunnerErrorCodes.RUNNER_UNAVAILABLE : result.errorCode();
            if (RunnerErrorCodes.INVALID_INPUT.equals(code)) {
                throw new InvalidRadarInputException(result.errorMessage() == null
                        ? "Dépôt refusé par le poste." : result.errorMessage());
            }
            if (RunnerErrorCodes.UNSUPPORTED_TOOL.equals(code)) {
                throw new RadarTeamsDisabledException(
                        "Ce poste ne sait pas encore recevoir un enregistrement : mettez le runner à jour.");
            }
            throw new RadarRunnerUnavailableException("Le poste n'a pas répondu : vérifiez que le runner tourne, "
                    + "puis recommencez.");
        }
        JsonNode node;
        try {
            node = mapper.readTree(result.content());
        } catch (IOException e) {
            throw new RadarRunnerUnavailableException("Réponse illisible du poste.");
        }
        if (node.path("accepted").isBoolean() && !node.path("accepted").asBoolean()) {
            String reason = node.path("reason").asText("");
            String sentence = node.path("sentence").asText("Dépôt refusé par le poste.");
            switch (reason) {
                case "TEAMS_DISABLED", "DEPOT_UNAVAILABLE" -> throw new RadarTeamsDisabledException(sentence);
                case "UNKNOWN_UPLOAD" -> throw new RadarNotFoundException(sentence);
                default -> throw new RadarStateConflictException(sentence);
            }
        }
        return node;
    }

    private static byte[] readBounded(InputStream body) {
        if (body == null) {
            throw new InvalidRadarInputException("Le morceau est vide.");
        }
        try {
            byte[] data = body.readNBytes(CHUNK_BYTES + 1);
            if (data.length == 0) {
                throw new InvalidRadarInputException("Le morceau est vide.");
            }
            if (data.length > CHUNK_BYTES) {
                throw new InvalidRadarInputException("Un morceau pèse au plus 512 Kio.");
            }
            return data;
        } catch (IOException e) {
            throw new InvalidRadarInputException("Le morceau n'a pas pu être lu.");
        }
    }
}
