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

    /** Ce que l'écran demande pour ouvrir un dépôt. */
    public record DepositRequest(String fileName, Long sizeBytes, String title, String recordedAt) {
    }

    /** Un dépôt ouvert : où envoyer, et par morceaux de quelle taille. */
    public record DepositOpened(UUID uploadId, int chunkBytes, long maxBytes) {
    }

    /** Un morceau reçu par le poste. */
    public record ChunkReceived(long received) {
    }

    /** Un enregistrement déposé dans le dossier du poste. */
    public record DepositDone(String fileName, String title, String recordedAt, long sizeBytes) {
    }

    private final RadarRunnerCalls calls;
    private final RunnerLiveness liveness;
    private final ObjectMapper mapper;

    public RadarRecordingDepositService(RadarRunnerCalls calls, RunnerLiveness liveness, ObjectMapper mapper) {
        this.calls = calls;
        this.liveness = liveness;
        this.mapper = mapper;
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
        requireOnline(scope);
        UUID uploadId = UUID.randomUUID();
        ObjectNode input = base("open", uploadId);
        input.put("file_name", fileName);
        input.put("size", size);
        input.put("title", title);
        input.put("recorded_at", recordedAt.toString());
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

    /** Termine le dépôt : le fichier apparaît dans le dossier, avec son titre et sa date. */
    public DepositDone finish(RadarScope scope, UUID uploadId) {
        requireOnline(scope);
        JsonNode answer = answer(calls.call(scope, DEPOSIT, base("finish", uploadId), FINISH_TIMEOUT_MS));
        return new DepositDone(answer.path("file_name").asText(), answer.path("title").asText(),
                answer.path("recorded_at").asText(), answer.path("size").asLong());
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
