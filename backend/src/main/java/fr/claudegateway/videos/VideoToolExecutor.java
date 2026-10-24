package fr.claudegateway.videos;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.storage.WorkspaceStorage;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerTargets;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * <b>Exécute {@code video_publier} et {@code audio_publier}</b> (F-213 / SF-213-01) : rapatrie le fichier du
 * poste par tranches ({@code read_file_bytes}), le range par un envoi par morceaux, et rend à l'agent ce qui a
 * été rangé.
 *
 * <p>La garde et l'accord d'un clic sont posés <b>avant</b>, par la boucle : cet exécuteur fait. Toute erreur
 * est un <b>résultat d'outil en erreur</b> qui dit quoi corriger ; un envoi commencé et non terminé est
 * annulé — rien de partiel n'est rangé.</p>
 *
 * <h2>Pourquoi la taille d'abord</h2>
 *
 * <p>La première tranche donne la taille totale du fichier : plafond (300 Mo / 20 Mo) et quota du compte
 * (5 Go, D1) sont décidés <b>avant</b> de faire remonter le reste — refuser après une minute de transfert
 * serait du temps et de la bande perdus.</p>
 */
@Component
public class VideoToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(VideoToolExecutor.class);

    /** Tranche lue par appel : la borne du runner (≈ 512 Kio une fois en Base64). */
    static final int CHUNK_BYTES = 393_216;

    /** Nombre maximal de pistes par appel {@code audio_publier}. */
    static final int MAX_TRACKS = 6;

    private final VideoService videoService;
    private final VideoStore store;
    private final VideoLimits limits;
    private final RunnerToolGateway runnerToolGateway;
    private final RunnerAuditService runnerAuditService;

    public VideoToolExecutor(VideoService videoService, VideoStore store, VideoLimits limits,
            RunnerToolGateway runnerToolGateway, RunnerAuditService runnerAuditService) {
        this.videoService = videoService;
        this.store = store;
        this.limits = limits;
        this.runnerToolGateway = runnerToolGateway;
        this.runnerAuditService = runnerAuditService;
    }

    // ------------------------------------------------------------------ video_publier

    /**
     * Publie la vidéo décrite par l'appel.
     *
     * @param userId    propriétaire du terminal (celui du tour)
     * @param workspace terminal du tour, déjà vérifié comme possédé
     * @param callId    identifiant de corrélation de l'appel
     * @param input     paramètres de l'outil
     */
    public VideoOutcome publishVideo(UUID userId, Workspace workspace, String callId, JsonNode input) {
        if (workspace == null || !workspace.isRunnerTarget()) {
            return VideoOutcome.error("La publication de vidéos se fait depuis un poste connecté.");
        }
        String path = text(input, "path");
        if (path.isEmpty()) {
            return VideoOutcome.error("Donne le path du fichier vidéo sur la machine.");
        }
        VideoMediaTypes.MediaType type = VideoMediaTypes.of(path, VideoKind.VIDEO).orElse(null);
        if (type == null) {
            return VideoOutcome.error("Vidéo refusée : « " + path + " ». Formats admis : .mp4, .webm.");
        }
        UUID replaceId;
        List<VideoChapter> chapters;
        try {
            replaceId = optionalUuid(input, "remplace");
            chapters = VideoChapter.parse(input == null ? null : input.get("chapitres"));
        } catch (VideoRejectedException e) {
            return VideoOutcome.error(e.getMessage());
        }
        Integer episode = optionalInt(input, "episode");
        Integer duration = optionalInt(input, "duree_s");
        RunnerTarget target = RunnerTargets.of(workspace);

        // La couverture, petite : lue entière, avant le gros transfert.
        String posterPath = text(input, "couverture");
        byte[] poster = null;
        String posterMime = null;
        if (!posterPath.isEmpty()) {
            posterMime = VideoMediaTypes.posterMime(posterPath).orElse(null);
            if (posterMime == null) {
                return VideoOutcome.error("Couverture refusée : « " + posterPath + " ». Formats admis : png, jpg, webp.");
            }
            Small read = readSmall(userId, target, callId + "#poster", posterPath, limits.maxPosterBytes());
            if (read.error() != null) {
                return VideoOutcome.error(read.error());
            }
            if (!VideoMediaTypes.matchesPoster(posterMime, read.content())) {
                return VideoOutcome.error("La couverture « " + posterPath + " » n'est pas une image " + posterMime + ".");
            }
            poster = read.content();
        }

        VideoPlace place = new VideoPlace(userId, workspace.getId(), workspace.getHostId());
        VideoService.PublishRequest request = new VideoService.PublishRequest(place, type, replaceId,
                text(input, "titre"), text(input, "description"), chapters, text(input, "serie"), episode, duration,
                null);
        Transfer transfer = transfer(userId, target, callId, VideoToolCatalog.VIDEO, path, request,
                poster == null ? 0 : poster.length);
        if (transfer.error() != null) {
            return VideoOutcome.error(transfer.error());
        }
        String posterKey = null;
        try {
            if (poster != null) {
                posterKey = VideoStore.posterKey(userId, transfer.prepared().videoId(), transfer.prepared().version(),
                        posterMime);
                store.put(posterKey, poster, posterMime);
            }
            VideoService.PublishedVideo published = videoService.commit(transfer.prepared(), transfer.size(), posterKey,
                    poster == null ? null : posterMime);
            Video video = published.video();
            String inSeries = published.series() == null ? ""
                    : " Série « " + published.series().getTitle() + " », épisode " + video.getEpisode() + ".";
            return new VideoOutcome("Vidéo publiée : « " + video.getTitle() + " » — version "
                    + published.version().getVersion() + ". video_id : " + video.getId() + "." + inSeries
                    + " L'utilisateur la regarde dans le fil et dans la galerie Vidéos du sujet ; pour la corriger, "
                    + "republie avec remplace=" + video.getId() + ".", false, VideoBlock.of(published));
        } catch (RuntimeException e) {
            store.delete(transfer.prepared().mediaKey());
            store.delete(posterKey);
            log.warn("Publication d'une vidéo annulée à l'inscription : {}", e.getClass().getSimpleName());
            return VideoOutcome.error(e instanceof VideoRejectedException ? e.getMessage()
                    : "La vidéo n'a pas pu être rangée ; réessaie.");
        }
    }

    // ------------------------------------------------------------------ audio_publier

    /**
     * Publie les pistes décrites par l'appel. Une piste en échec n'efface pas celles déjà rangées : l'agent
     * reçoit le détail, et le bloc montre ce qui est publié.
     */
    public AudioOutcome publishAudio(UUID userId, Workspace workspace, String callId, JsonNode input) {
        if (workspace == null || !workspace.isRunnerTarget()) {
            return AudioOutcome.error("La publication de sons se fait depuis un poste connecté.");
        }
        JsonNode list = input == null ? null : input.get("pistes");
        if (list == null || !list.isArray() || list.isEmpty()) {
            return AudioOutcome.error("Donne pistes : 1 à " + MAX_TRACKS + " sons {path, libelle}.");
        }
        if (list.size() > MAX_TRACKS) {
            return AudioOutcome.error("Trop de pistes : " + MAX_TRACKS + " au plus par appel.");
        }
        List<VideoMediaTypes.MediaType> types = new ArrayList<>();
        for (JsonNode track : list) {
            String path = text(track, "path");
            VideoMediaTypes.MediaType type = VideoMediaTypes.of(path, VideoKind.AUDIO).orElse(null);
            if (type == null) {
                return AudioOutcome.error("Son refusé : « " + path + " ». Formats admis : .mp3, .wav, .m4a.");
            }
            types.add(type);
        }
        String title = text(input, "titre");
        RunnerTarget target = RunnerTargets.of(workspace);
        VideoPlace place = new VideoPlace(userId, workspace.getId(), workspace.getHostId());
        List<AudioBlock.Track> published = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int index = 0;
        for (JsonNode track : list) {
            String path = text(track, "path");
            String label = text(track, "libelle");
            String trackTitle = !label.isEmpty() ? label : !title.isEmpty() ? title : fileName(path);
            VideoService.PublishRequest request = new VideoService.PublishRequest(place, types.get(index), null,
                    trackTitle, null, List.of(), null, null, null, label.isEmpty() ? null : label);
            index++;
            Transfer transfer = transfer(userId, target, callId + "#" + index, VideoToolCatalog.AUDIO, path, request, 0);
            if (transfer.error() != null) {
                failures.add("piste " + index + " (" + path + ") : " + transfer.error());
                continue;
            }
            try {
                VideoService.PublishedVideo done = videoService.commit(transfer.prepared(), transfer.size(), null, null);
                published.add(new AudioBlock.Track(done.video().getId(),
                        done.version().getLabel() != null ? done.version().getLabel() : done.video().getTitle(),
                        done.version().getVersion(), done.version().getMime()));
            } catch (RuntimeException e) {
                store.delete(transfer.prepared().mediaKey());
                failures.add("piste " + index + " (" + path + ") : " + (e instanceof VideoRejectedException
                        ? e.getMessage() : "rangement impossible"));
            }
        }
        if (published.isEmpty()) {
            return AudioOutcome.error("Aucun son publié. " + String.join(" ; ", failures));
        }
        String content = published.size() + (published.size() > 1 ? " sons publiés" : " son publié")
                + " dans le fil : " + String.join(", ", published.stream().map(AudioBlock.Track::label).toList()) + ".";
        if (!failures.isEmpty()) {
            content += " Non publiés (dis-le à l'utilisateur) : " + String.join(" ; ", failures);
        }
        return new AudioOutcome(content, false, new AudioBlock(title.isEmpty() ? null : title, List.copyOf(published)));
    }

    // ------------------------------------------------------------------ transfert par tranches

    /**
     * Lit la première tranche (taille + signature), prépare la publication, puis fait remonter le reste vers un
     * envoi par morceaux. Tracé une fois dans le journal du runner. En cas d'échec, l'envoi est annulé.
     */
    private Transfer transfer(UUID userId, RunnerTarget target, String callId, String tool, String path,
            VideoService.PublishRequest request, long posterBytes) {
        VideoMediaTypes.MediaType type = request.type();
        RunnerCallResult first = runnerToolGateway.readFileBytes(target, callId + ".0", path, 0L, CHUNK_BYTES);
        if (!first.ok()) {
            runnerAuditService.recordCall(userId, target, callId, tool, path, first);
            return Transfer.error(runnerError(path, first));
        }
        byte[] head = decode(first);
        if (head == null || first.bytes() == null || first.bytes() < 0) {
            runnerAuditService.recordCall(userId, target, callId, tool, path, first);
            return Transfer.error("Fichier illisible sur la machine (" + path + ") : réponse du runner invalide.");
        }
        long size = first.bytes();
        if (!VideoMediaTypes.matches(type, head)) {
            runnerAuditService.recordCall(userId, target, callId, tool, path, first);
            return Transfer.error("« " + path + " » n'est pas un fichier " + type.extension()
                    + " valide (signature inattendue).");
        }
        VideoService.Prepared prepared;
        try {
            prepared = videoService.prepare(request, size, posterBytes);
        } catch (VideoRejectedException e) {
            runnerAuditService.recordCall(userId, target, callId, tool, path, first);
            return Transfer.error(e.getMessage());
        }
        WorkspaceStorage.Upload upload = store.openUpload(prepared.mediaKey(), type.mime());
        RunnerCallResult last = first;
        try {
            long received = head.length;
            upload.write(head);
            int chunk = 1;
            boolean more = first.truncated() && head.length > 0 && received < size;
            while (more) {
                RunnerCallResult result = runnerToolGateway.readFileBytes(target, callId + "." + chunk, path, received,
                        CHUNK_BYTES);
                last = result;
                if (!result.ok()) {
                    upload.abort();
                    runnerAuditService.recordCall(userId, target, callId, tool, path, result);
                    return Transfer.error(runnerError(path, result));
                }
                byte[] part = decode(result);
                if (part == null || result.bytes() == null || result.bytes() != size) {
                    upload.abort();
                    runnerAuditService.recordCall(userId, target, callId, tool, path, result);
                    return Transfer.error(changed(path));
                }
                upload.write(part);
                received += part.length;
                if (received > size) {
                    upload.abort();
                    runnerAuditService.recordCall(userId, target, callId, tool, path, result);
                    return Transfer.error(changed(path));
                }
                more = result.truncated() && part.length > 0 && received < size;
                chunk++;
            }
            if (received != size) {
                upload.abort();
                runnerAuditService.recordCall(userId, target, callId, tool, path, last);
                return Transfer.error(changed(path));
            }
            upload.complete();
            runnerAuditService.recordCall(userId, target, callId, tool, path, last);
            return new Transfer(prepared, size, null);
        } catch (RuntimeException e) {
            upload.abort();
            runnerAuditService.recordCall(userId, target, callId, tool, path, last);
            log.warn("Envoi d'un média interrompu : {}", e.getClass().getSimpleName());
            return Transfer.error("Le fichier n'a pas pu être rangé (" + path + ") ; réessaie.");
        }
    }

    /** Un petit fichier (couverture), lu entier par tranches, borné. */
    private Small readSmall(UUID userId, RunnerTarget target, String callId, String path, long cap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RunnerCallResult last = null;
        int chunk = 0;
        Long size = null;
        while (true) {
            RunnerCallResult result = runnerToolGateway.readFileBytes(target, callId + "." + chunk, path, out.size(),
                    CHUNK_BYTES);
            last = result;
            if (!result.ok()) {
                runnerAuditService.recordCall(userId, target, callId, VideoToolCatalog.VIDEO, path, result);
                return new Small(null, runnerError(path, result));
            }
            byte[] part = decode(result);
            if (part == null || result.bytes() == null || result.bytes() < 0) {
                runnerAuditService.recordCall(userId, target, callId, VideoToolCatalog.VIDEO, path, result);
                return new Small(null, "Fichier illisible sur la machine (" + path + ") : réponse du runner invalide.");
            }
            if (size == null) {
                size = result.bytes();
                if (size > cap) {
                    runnerAuditService.recordCall(userId, target, callId, VideoToolCatalog.VIDEO, path, result);
                    return new Small(null, "Couverture trop volumineuse : " + (cap / (1024 * 1024)) + " Mo au plus.");
                }
            } else if (result.bytes().longValue() != size) {
                runnerAuditService.recordCall(userId, target, callId, VideoToolCatalog.VIDEO, path, result);
                return new Small(null, changed(path));
            }
            out.write(part, 0, part.length);
            if (!result.truncated() || part.length == 0 || out.size() >= size) {
                break;
            }
            chunk++;
        }
        runnerAuditService.recordCall(userId, target, callId, VideoToolCatalog.VIDEO, path, last);
        if (out.size() != size) {
            return new Small(null, changed(path));
        }
        return new Small(out.toByteArray(), null);
    }

    // ------------------------------------------------------------------ messages et lecture des paramètres

    private static String runnerError(String path, RunnerCallResult result) {
        if (RunnerErrorCodes.UNSUPPORTED_TOOL.equals(result.errorCode()) || "too_large".equals(result.errorCode())) {
            return "Le runner de ce poste est trop ancien pour publier ce fichier (" + path + ") : mets-le à jour "
                    + "depuis la Forge, puis réessaie.";
        }
        String reason = result.errorMessage() == null || result.errorMessage().isBlank()
                ? "lecture impossible" : result.errorMessage();
        return "Fichier illisible sur la machine (" + path + ") : " + reason;
    }

    private static String changed(String path) {
        return "Le fichier « " + path + " » a changé pendant la lecture : réessaie quand il ne bouge plus.";
    }

    private static byte[] decode(RunnerCallResult result) {
        try {
            return Base64.getDecoder().decode(result.content() == null ? "" : result.content());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String fileName(String path) {
        String name = path.replace('\\', '/');
        return name.substring(name.lastIndexOf('/') + 1);
    }

    private static UUID optionalUuid(JsonNode input, String field) {
        String raw = text(input, field);
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new VideoRejectedException(field + " doit être un identifiant rendu par une publication précédente.");
        }
    }

    private static Integer optionalInt(JsonNode input, String field) {
        if (input == null || !input.hasNonNull(field) || !input.get(field).canConvertToInt()) {
            return null;
        }
        return input.get(field).asInt();
    }

    private static String text(JsonNode input, String field) {
        if (input == null || !input.hasNonNull(field)) {
            return "";
        }
        return input.get(field).asText("").strip();
    }

    /** Titre lisible d'un appel, pour l'étape et le journal : jamais le contenu. */
    public static String auditTarget(JsonNode input) {
        String title = text(input, "titre");
        if (!title.isEmpty()) {
            return title;
        }
        JsonNode tracks = input == null ? null : input.get("pistes");
        return tracks != null && tracks.isArray() ? tracks.size() + " son(s)" : null;
    }

    private record Transfer(VideoService.Prepared prepared, long size, String error) {

        static Transfer error(String message) {
            return new Transfer(null, 0, message);
        }
    }

    private record Small(byte[] content, String error) {
    }

    /**
     * L'issue de {@code video_publier}.
     *
     * @param block le bloc « vidéo », ou {@code null} en cas d'échec
     */
    public record VideoOutcome(String content, boolean error, VideoBlock block) {

        static VideoOutcome error(String message) {
            return new VideoOutcome(message, true, null);
        }
    }

    /**
     * L'issue de {@code audio_publier}.
     *
     * @param block le bloc « audio », ou {@code null} en cas d'échec
     */
    public record AudioOutcome(String content, boolean error, AudioBlock block) {

        static AudioOutcome error(String message) {
            return new AudioOutcome(message, true, null);
        }
    }
}
