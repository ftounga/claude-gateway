package fr.claudegateway.runner.video;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

import fr.claudegateway.runner.teams.FrameSelection;
import fr.claudegateway.runner.teams.FramesHarvest;
import fr.claudegateway.runner.teams.SceneFrame;
import fr.claudegateway.runner.teams.SceneFrames;
import fr.claudegateway.runner.teams.TeamsGap;
import fr.claudegateway.runner.teams.ToolchainUnavailableException;

/**
 * <b>Le travail long de l'extraction</b> (F-190 / SF-190-01) : un seul à la fois sur la machine,
 * en tâche de fond, et qui se dit au fil de l'eau.
 *
 * <p>Il réutilise ce que F-90 a construit pour Teams — {@link SceneFrames} (les changements de plan,
 * horodatés par {@code ffmpeg}) et {@link FrameSelection} (doublons écartés, 60 images au plus
 * réparties sur toute la durée) — <b>sans</b> ce qui n'appartient qu'aux réunions : ni
 * transcription obligatoire, ni remontée des images. Les images restent sur le poste ; l'agent les
 * ouvre par {@code read_file}.</p>
 */
public final class VideoFramesWorker {

    /** Au-delà, le texte d'une case est coupé : la planche se survole, elle ne se lit pas en entier. */
    static final int MAX_SAID_CHARS = 300;

    private final SceneFrames frames;
    private final VideoTranscriber transcriber;
    private final ExecutorService pool;
    private final Map<String, VideoJob> live = new ConcurrentHashMap<>();

    public VideoFramesWorker(SceneFrames frames) {
        this(frames, VideoTranscriber.none());
    }

    public VideoFramesWorker(SceneFrames frames, VideoTranscriber transcriber) {
        this(frames, transcriber, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "video-frames");
            thread.setDaemon(true);
            return thread;
        }));
    }

    VideoFramesWorker(SceneFrames frames, ExecutorService pool) {
        this(frames, VideoTranscriber.none(), pool);
    }

    VideoFramesWorker(SceneFrames frames, VideoTranscriber transcriber, ExecutorService pool) {
        this.frames = frames;
        this.transcriber = transcriber == null ? VideoTranscriber.none() : transcriber;
        this.pool = pool;
    }

    /**
     * Démarre le travail, ou rend celui qui existe déjà pour la même vidéo et les mêmes réglages.
     *
     * @return l'état <b>à cet instant</b> — jamais le résultat final, qui n'a pas encore eu lieu
     */
    public VideoJob startOrResume(Request request, Consumer<String> progress) {
        if (request.restart()) {
            live.remove(request.id());
        } else {
            VideoJob existing = find(request.id(), request.dir()).orElse(null);
            if (existing != null) {
                say(progress, existing.isOver()
                        ? "Ces images ont déjà été extraites : je rends le résultat plutôt que de "
                                + "tout recommencer (restart à true pour relancer)."
                        : "Cette extraction est déjà en cours : je ne la relance pas.");
                return existing;
            }
        }
        VideoJob job = new VideoJob(request.id(), request.video().toAbsolutePath().toString(),
                request.mode().label(), request.dir());
        live.put(request.id(), job);
        job.save();
        pool.submit(() -> run(job, request, progress));
        return job;
    }

    /** Le travail de cet identifiant : en mémoire, sinon relu du dossier donné. */
    public Optional<VideoJob> find(String id, Path dir) {
        VideoJob known = live.get(id);
        if (known != null) {
            return Optional.of(known);
        }
        return dir == null ? Optional.empty()
                : VideoJob.load(dir).filter(job -> id.equals(job.id()));
    }

    // ------------------------------------------------------------------ le travail

    private void run(VideoJob job, Request request, Consumer<String> progress) {
        try {
            step(job, VideoJob.Phase.OUTILLAGE, progress);
            step(job, VideoJob.Phase.EXTRACTION, progress);
            SceneFrames.Extraction extraction = request.mode() == Mode.PLANS
                    ? frames.extractWithOpening(request.video(), request.dir(),
                            SceneFrames.SCENE_THRESHOLD)
                    : frames.extractEvery(request.video(), request.dir(), request.fromSeconds(),
                            request.toSeconds(), request.everySeconds());

            step(job, VideoJob.Phase.TRI, progress);
            FramesHarvest harvest = FrameSelection.select(extraction.frames(), extraction.gaps());
            discardUnkept(extraction.frames(), harvest.frames());
            List<VideoJob.Frame> kept = new ArrayList<>();
            for (SceneFrame frame : harvest.frames()) {
                kept.add(new VideoJob.Frame(frame.offsetSeconds(),
                        request.display().apply(frame.file())));
            }
            List<String> gaps = new ArrayList<>();
            for (TeamsGap gap : harvest.gaps()) {
                gaps.add(gap.describe());
            }
            job.harvested(harvest.examined(), kept, gaps);
            say(progress, harvest.describe());

            // SF-190-02 : les planches contact — survoler avant d'ouvrir en grand.
            if (!harvest.frames().isEmpty()) {
                step(job, VideoJob.Phase.PLANCHES, progress);
                ContactSheets.Result sheets = ContactSheets.compose(harvest.frames(),
                        request.dir(), request.display());
                job.composed(sheets.sheets(), sheets.gaps());
            }

            // SF-190-03 : ce qui se disait pendant chaque image.
            if (!harvest.frames().isEmpty()
                    && (request.transcript() != null || request.transcribe())) {
                step(job, VideoJob.Phase.SON, progress);
                hear(job, request, progress);
            }

            job.phase(VideoJob.Phase.TERMINE);
            job.save();
            say(progress, job.describe());
        } catch (ToolchainUnavailableException e) {
            fail(job, e.getMessage(), e.remedy(), progress);
        } catch (SceneFrames.SceneExtractionException e) {
            fail(job, e.getMessage(), e.detail(), progress);
        } catch (RuntimeException e) {
            fail(job, "L'extraction de cette vidéo s'est interrompue.",
                    e.getMessage() == null ? "" : e.getMessage(), progress);
        }
    }

    /**
     * Lit ou produit la transcription, puis l'aligne. <b>Rien ici ne fait échouer le travail</b> :
     * les images valent sans le son, et ce qui manque est dit.
     */
    private void hear(VideoJob job, Request request, Consumer<String> progress) {
        List<String> gaps = new ArrayList<>();
        List<TimedTranscript.Line> lines = List.of();
        String readable = "";
        if (request.transcript() != null) {
            readable = request.display().apply(request.transcript());
            try {
                lines = TimedTranscript.read(request.transcript());
            } catch (IOException | RuntimeException e) {
                gaps.add("transcription illisible (" + e.getMessage() + ") : les images sont "
                        + "rendues sans le son");
            }
            if (lines.isEmpty() && gaps.isEmpty()) {
                gaps.add("transcription sans horodatage lisible : aucun texte n'est posé à côté "
                        + "des images — lis-la toi-même avec read_file (" + readable + ")");
            }
        } else {
            try {
                VideoTranscriber.Transcribed transcribed =
                        transcriber.transcribe(request.video(), request.dir());
                lines = transcribed.lines();
                readable = request.display().apply(transcribed.readable());
                if (lines.isEmpty()) {
                    gaps.add("la transcription sur le poste n'a rien rendu (vidéo muette ?)");
                }
            } catch (VideoTranscriber.Unavailable e) {
                gaps.add("transcription sur le poste impossible : " + e.getMessage()
                        + (e.remedy().isBlank() ? "" : " — " + e.remedy()));
            }
        }
        Map<String, String> said = align(job.frames(), lines, gaps);
        job.heard(said, readable, gaps);
        say(progress, lines.isEmpty() ? "Images rendues sans le son."
                : lines.size() + " réplique" + (lines.size() > 1 ? "s" : "")
                        + " posée" + (lines.size() > 1 ? "s" : "") + " à côté des images.");
    }

    /**
     * <b>Une image règne</b> de son instant jusqu'à l'image suivante (la dernière, jusqu'à la
     * fin) : chaque réplique commencée pendant ce règne lui revient. C'est la règle de F-90
     * (SF-90-02), appliquée aux instants de la vidéo.
     */
    static Map<String, String> align(List<VideoJob.Frame> frames, List<TimedTranscript.Line> lines,
            List<String> gaps) {
        Map<String, String> said = new java.util.HashMap<>();
        if (frames.isEmpty() || lines.isEmpty()) {
            return said;
        }
        List<VideoJob.Frame> ordered = new ArrayList<>(frames);
        ordered.sort(java.util.Comparator.comparingDouble(VideoJob.Frame::offsetSeconds));
        int before = 0;
        for (TimedTranscript.Line line : lines) {
            VideoJob.Frame reigning = null;
            for (VideoJob.Frame frame : ordered) {
                if (frame.offsetSeconds() <= line.offsetSeconds() + 1e-6) {
                    reigning = frame;
                } else {
                    break;
                }
            }
            if (reigning == null) {
                before++;
                continue;
            }
            String piece = (line.speaker().isBlank() ? "" : line.speaker() + " : ") + line.text();
            said.merge(reigning.path(), piece, (a, b) -> a + " / " + b);
        }
        said.replaceAll((path, text) -> text.length() <= MAX_SAID_CHARS ? text
                : text.substring(0, MAX_SAID_CHARS - 1).strip() + "…");
        if (before > 0) {
            gaps.add(before + " réplique" + (before > 1 ? "s" : "") + " avant la première image "
                    + "(sans image en vigueur)");
        }
        return said;
    }

    /**
     * Les images écartées (doublons, au-delà du plafond) sont <b>effacées</b> : laissées dans le
     * dossier, l'agent les listerait et ouvrirait des doublons — chaque image ouverte se paie.
     */
    private static void discardUnkept(List<SceneFrame> extracted, List<SceneFrame> kept) {
        Set<Path> keep = new HashSet<>();
        kept.forEach(frame -> keep.add(frame.file()));
        for (SceneFrame frame : extracted) {
            if (!keep.contains(frame.file())) {
                try {
                    Files.deleteIfExists(frame.file());
                } catch (IOException ignored) {
                    // Une image écartée qui résiste n'est pas listée : elle ne trompe personne.
                }
            }
        }
    }

    private static void step(VideoJob job, VideoJob.Phase phase, Consumer<String> progress) {
        job.phase(phase);
        job.save();
        say(progress, phase.label() + "…");
    }

    private static void fail(VideoJob job, String why, String how, Consumer<String> progress) {
        job.failed(why, how);
        job.save();
        say(progress, job.describe());
    }

    private static void say(Consumer<String> progress, String message) {
        if (progress != null && message != null && !message.isBlank()) {
            progress.accept(message);
        }
    }

    /** Comment choisir les images. */
    public enum Mode {
        /** Aux changements de plan (défaut) : 20 à 60 images pour une heure. */
        PLANS("plans"),
        /** Le zoom : une image toutes les N secondes sur un intervalle. */
        INTERVALLE("intervalle");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * Ce qu'il y a à faire.
     *
     * @param id      identifiant stable (vidéo + réglages) : la même demande retrouve le même travail
     * @param video   la vidéo, <b>sur la machine</b> ; elle n'en sort jamais
     * @param dir     dossier des images et de {@code job.json}
     * @param display    chemin d'une image tel que {@code read_file} le lira
     * @param transcript transcription fournie, sur le poste, ou {@code null} (SF-190-03)
     * @param transcribe transcrire sur le poste quand aucune transcription n'est fournie
     */
    public record Request(String id, Path video, Path dir, Mode mode, double fromSeconds,
            double toSeconds, double everySeconds, boolean restart,
            Function<Path, String> display, Path transcript, boolean transcribe) {

        /** Sans le son (SF-190-01/02). */
        public Request(String id, Path video, Path dir, Mode mode, double fromSeconds,
                double toSeconds, double everySeconds, boolean restart,
                Function<Path, String> display) {
            this(id, video, dir, mode, fromSeconds, toSeconds, everySeconds, restart, display, null,
                    false);
        }
    }
}
