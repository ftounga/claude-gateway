package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * <b>Le travail long de la transcription</b> (F-91 / SF-91-03), hors du fil de l'appel.
 *
 * <h2>Traitement lourd, donc asynchrone</h2>
 *
 * <p>C'est la règle de {@code CLAUDE.md}, et le modèle est celui de {@link MomentsWorker} :
 * l'arrêt de la capture <b>rend la main tout de suite</b>, et la transcription d'une heure d'audio
 * tourne ailleurs. Un outil qui attendrait la fin verrait son délai d'appel tomber bien avant — et
 * l'utilisateur, qui sort de réunion, attendrait devant un écran figé.</p>
 *
 * <h2>Elle démarre à l'arrêt, sans qu'on la demande</h2>
 *
 * <p>Une capture sans transcription ne sert à rien : il n'y aurait ni compte rendu, ni moments. Faire
 * attendre un tour de plus ferait perdre des minutes à quelqu'un qui vient de raccrocher. Elle est
 * asynchrone et <b>dite</b>, donc elle ne bloque rien.</p>
 *
 * <h2>Un seul travail à la fois</h2>
 *
 * <p>Exécuteur mono-fil, comme pour les moments, et pour la même raison : deux transcriptions
 * simultanées rendraient le poste d'un consultant inutilisable pendant qu'il travaille.</p>
 */
public final class TranscriptionWorker {

    private final CaptureStore store;
    private final AudioTrack audio;
    private final LocalTranscription transcription;
    private final ExecutorService pool;
    private final Map<String, TranscriptionJob> live = new ConcurrentHashMap<>();

    public TranscriptionWorker(CaptureStore store, AudioTrack audio,
            LocalTranscription transcription) {
        this(store, audio, transcription, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "teams-transcription");
            thread.setDaemon(true);
            return thread;
        }));
    }

    TranscriptionWorker(CaptureStore store, AudioTrack audio, LocalTranscription transcription,
            ExecutorService pool) {
        this.store = store;
        this.audio = audio;
        this.transcription = transcription;
        this.pool = pool;
    }

    /**
     * Démarre la transcription d'une capture, ou rend celle qui existe déjà.
     *
     * @param record   la capture terminée
     * @param progress où dire les étapes, au fil de l'eau
     * @return l'état du travail <b>à cet instant</b> — jamais son résultat final
     */
    public TranscriptionJob startOrResume(CaptureRecord record, Consumer<String> progress) {
        TranscriptionJob existing = find(record.id()).orElse(null);
        if (existing != null) {
            say(progress, existing.isOver()
                    ? "Cet enregistrement a déjà été transcrit : je rends son résultat plutôt que "
                            + "de tout recommencer."
                    : "La transcription de cet enregistrement est déjà en cours.");
            return existing;
        }
        TranscriptionJob job = new TranscriptionJob(record.id());
        // Une capture SANS SON n'a rien à transcrire, et cela se dit : un silence se lirait
        // « personne n'a rien dit », ce qui est une affirmation que nous n'avons pas les moyens de
        // faire.
        if (!record.audio()) {
            job.failed("Cette capture a été faite sans le son : il n'y a rien à transcrire.",
                    "Redemandez une capture avec le son si vous voulez un compte rendu de ce qui "
                            + "s'est dit.")
                    .addGap(new TeamsGap(TeamsGapKind.NOTHING_OBSERVED, "transcription locale",
                            "la capture a été faite sans le son : aucune parole n'a pu être "
                                    + "transcrite", 1));
            live.put(job.id(), job);
            say(progress, job.describe());
            return job;
        }
        live.put(job.id(), job);
        pool.submit(() -> run(job, record, progress));
        return job;
    }

    /**
     * Transcrit un <b>fichier</b> qui n'est pas une capture locale — l'enregistrement Teams téléchargé
     * par Chrome (F-108 / SF-108-05), quand Teams n'en a servi aucune transcription.
     *
     * <p>Même moteur, même règle : asynchrone, sur la machine, rien n'en sort que le texte. Deux
     * différences : le magasin des captures n'est <b>pas</b> touché (ce n'est pas une capture), et les
     * fichiers produits vont dans {@code into}, un dossier de travail <b>distinct</b> de celui du
     * téléchargement.</p>
     *
     * @param id        identifiant du travail ({@code rec-<réunion>})
     * @param startedAt origine du temps des répliques
     * @param header    en-tête du fichier de transcription (provenance)
     */
    public TranscriptionJob startOrResumeFile(String id, Path video, Path into, Instant startedAt,
            String header, Consumer<String> progress) {
        TranscriptionJob existing = find(id).orElse(null);
        if (existing != null) {
            return existing;
        }
        TranscriptionJob job = new TranscriptionJob(id);
        live.put(job.id(), job);
        pool.submit(() -> transcribe(job, video, into, startedAt, header, progress));
        return job;
    }

    /** Le travail de cette capture, s'il existe. */
    public Optional<TranscriptionJob> find(String captureId) {
        return Optional.ofNullable(live.get(captureId == null ? "" : captureId.strip()));
    }

    // ------------------------------------------------------------------ le travail

    private void run(TranscriptionJob job, CaptureRecord record, Consumer<String> progress) {
        Instant startedAt = record.startedAt() == null ? Instant.now() : record.startedAt();
        if (transcribe(job, Path.of(record.video()), Path.of(record.video()).getParent(), startedAt,
                record.mention(), progress)) {
            // La capture garde le chemin de sa transcription : c'est par elle qu'on rejoint le
            // chemin existant (moments, carte, compte rendu).
            record.transcript(job.file());
            store.save(record);
        }
    }

    /** Le travail lui-même : audio, modèle, transcription. Vrai s'il a abouti. */
    private boolean transcribe(TranscriptionJob job, Path video, Path into, Instant startedAt,
            String mention, Consumer<String> progress) {
        try {
            step(job, TranscriptionJob.Phase.AUDIO, progress);
            java.nio.file.Files.createDirectories(into);
            Path track = audio.extract(video, into);

            step(job, TranscriptionJob.Phase.MODELE, progress);
            step(job, TranscriptionJob.Phase.TRANSCRIPTION, progress);
            LocalTranscription.Result result =
                    transcription.transcribe(track, into, startedAt, mention);

            job.cues(result.cues()).addGaps(result.gaps())
                    .file(into.resolve(LocalTranscription.READABLE).toString())
                    .phase(TranscriptionJob.Phase.TERMINE);
            say(progress, job.describe());
            return true;
        } catch (java.io.IOException e) {
            fail(job, "Le dossier de travail de la transcription n'a pas pu être créé.",
                    String.valueOf(into), progress);
        } catch (ToolchainUnavailableException e) {
            fail(job, e.getMessage(), e.remedy(), progress);
        } catch (AudioTrack.AudioUnavailableException e) {
            fail(job, e.getMessage(), e.detail(), progress);
        } catch (LocalTranscription.TranscriptionFailedException e) {
            fail(job, e.getMessage(), e.detail(), progress);
        } catch (RuntimeException e) {
            fail(job, "La transcription de cet enregistrement s'est interrompue.",
                    e.getMessage() == null ? "" : e.getMessage(), progress);
        }
        return false;
    }

    private void step(TranscriptionJob job, TranscriptionJob.Phase phase, Consumer<String> progress) {
        job.phase(phase);
        say(progress, phase.label() + "…");
    }

    private void fail(TranscriptionJob job, String why, String how, Consumer<String> progress) {
        job.failed(why, how);
        say(progress, job.describe());
    }

    private static void say(Consumer<String> progress, String message) {
        if (progress != null && message != null && !message.isBlank()) {
            progress.accept(message);
        }
    }

    /** Le montage réel, à partir de l'outillage déjà résolu. */
    public static TranscriptionWorker over(LocalToolchain toolchain, ProcessRunner processes,
            CaptureStore store) {
        return new TranscriptionWorker(store, new AudioTrack(toolchain, processes),
                new LocalTranscription(toolchain, processes));
    }

    /** Les répliques d'une capture transcrite, ou la liste vide. */
    public List<TeamsTranscriptCue> cuesOf(String captureId) {
        return find(captureId).map(TranscriptionJob::cues).orElse(List.of());
    }

    /** Ce qui n'a pas pu être fait pendant la transcription de cette capture. */
    public List<TeamsGap> gapsOf(String captureId) {
        return find(captureId).map(TranscriptionJob::gaps).orElse(List.of());
    }
}
