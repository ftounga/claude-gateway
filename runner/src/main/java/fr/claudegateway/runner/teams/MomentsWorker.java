package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * <b>Le travail long, hors du fil de l'appel</b> (F-90 / SF-90-03).
 *
 * <h2>Traitement lourd, donc asynchrone</h2>
 *
 * <p>C'est la règle de {@code CLAUDE.md}, et le modèle est celui d'{@code OcrPollingWorker} :
 * l'outil qui démarre le travail <b>rend la main tout de suite</b> ; le décodage d'une heure de
 * vidéo tourne ailleurs. Un outil qui attendrait la fin verrait son délai d'appel tomber bien avant.</p>
 *
 * <h2>Et le travail se voit travailler</h2>
 *
 * <p>§5.5 du cadrage : <i>« la conversation ne se fige pas — l'agent dit ce qu'il fait, étape par
 * étape, comme pour une commande »</i>. Chaque étape est diffusée au fil de l'eau, exactement comme
 * {@code bash} diffuse ses lignes. <b>Et depuis F-84, ce fil survit à un changement d'écran</b> :
 * l'utilisateur peut partir et revenir.</p>
 *
 * <h2>La reprise</h2>
 *
 * <p>Redemander le <b>même</b> enregistrement ne recommence pas : un travail terminé rend ses
 * moments, un travail en cours rend son avancement, un travail échoué rend son échec et son remède.
 * Seul un redémarrage <b>explicite</b> relance.</p>
 *
 * <h2>Un seul travail à la fois</h2>
 *
 * <p>L'exécuteur est mono-fil, et c'est délibéré : deux décodages vidéo simultanés sur le poste d'un
 * consultant rendraient sa machine inutilisable pendant qu'il travaille. Le second attend.</p>
 */
public final class MomentsWorker {

    private final MomentsJobStore store;
    private final SceneFrames frames;
    private final MomentUploader uploader;
    private final ExecutorService pool;
    private final Map<String, MomentsJob> live = new ConcurrentHashMap<>();

    public MomentsWorker(MomentsJobStore store, SceneFrames frames, MomentUploader uploader) {
        this(store, frames, uploader, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "teams-moments");
            thread.setDaemon(true);
            return thread;
        }));
    }

    MomentsWorker(MomentsJobStore store, SceneFrames frames, MomentUploader uploader,
            ExecutorService pool) {
        this.store = store;
        this.frames = frames;
        this.uploader = uploader;
        this.pool = pool;
    }

    /**
     * Démarre le travail, ou rend celui qui existe déjà.
     *
     * @param request  ce qu'il y a à faire
     * @param progress où dire les étapes, au fil de l'eau
     * @return l'état du travail <b>à cet instant</b> — jamais son résultat final : il n'a pas encore
     *         eu lieu
     */
    public MomentsJob startOrResume(Request request, Consumer<String> progress) {
        String id = MomentsJobStore.idFor(request.video());
        if (request.restart()) {
            live.remove(id);
            store.forget(id);
        }
        MomentsJob existing = find(id).orElse(null);
        if (existing != null) {
            // La reprise, et elle SE DIT : sans cela, l'utilisateur croirait avoir relancé.
            say(progress, existing.isOver()
                    ? "Ce travail a déjà été fait pour cet enregistrement : je rends son résultat "
                            + "plutôt que de tout recommencer."
                    : "Ce travail est déjà en cours pour cet enregistrement : je ne le relance pas.");
            return existing;
        }
        MomentsJob job = new MomentsJob(id, request.video().toAbsolutePath().toString());
        job.subject(request.subject());
        live.put(id, job);
        store.save(job);
        pool.submit(() -> run(job, request, progress));
        return job;
    }

    /** Le travail de cet identifiant, en mémoire ou relu du disque. */
    public Optional<MomentsJob> find(String id) {
        MomentsJob known = live.get(id);
        return known != null ? Optional.of(known) : store.find(id);
    }

    // ------------------------------------------------------------------ le travail

    private void run(MomentsJob job, Request request, Consumer<String> progress) {
        try {
            step(job, MomentsJob.Phase.OUTILLAGE, progress);
            Path framesDir = store.framesDirOf(job.id());

            step(job, MomentsJob.Phase.EXTRACTION, progress);
            SceneFrames.Extraction extraction =
                    frames.extract(request.video(), framesDir, request.threshold());

            step(job, MomentsJob.Phase.TRI, progress);
            FramesHarvest harvest = FrameSelection.select(extraction.frames(), extraction.gaps());
            job.counts(harvest.examined(), harvest.frames().size());
            say(progress, harvest.describe());
            store.save(job);

            step(job, MomentsJob.Phase.ALIGNEMENT, progress);
            MomentsAlignment aligned = MomentAlignment.align(harvest.frames(), request.cues(),
                    request.timeline(), harvest.gaps());
            job.addGaps(aligned.gaps()).timeline(aligned.timeline().describe());
            say(progress, aligned.describe());
            store.save(job);

            step(job, MomentsJob.Phase.REMONTEE, progress);
            upload(job, request, aligned.moments(), progress);

            job.phase(MomentsJob.Phase.TERMINE);
            store.save(job);
            say(progress, job.describe());
        } catch (ToolchainUnavailableException e) {
            fail(job, e.getMessage(), e.remedy(), progress);
        } catch (SceneFrames.SceneExtractionException e) {
            fail(job, e.getMessage(), e.detail(), progress);
        } catch (MomentAlignment.OriginUnknownException e) {
            fail(job, e.getMessage(), e.remedy(), progress);
        } catch (IOException | RuntimeException e) {
            fail(job, "Le traitement de cet enregistrement s'est interrompu.",
                    e.getMessage() == null ? "" : e.getMessage(), progress);
        }
    }

    /**
     * Fait remonter les images des moments. <b>Une refusée est comptée et le travail continue</b> ;
     * <b>toutes refusées font échouer</b> — un compte rendu de moments sans aucune image se lirait
     * comme une transcription découpée, et personne ne saurait qu'il manque tout.
     */
    private void upload(MomentsJob job, Request request, List<TeamsMoment> moments,
            Consumer<String> progress) {
        for (TeamsMoment moment : moments) {
            String imageId = "";
            try {
                imageId = uploader.upload(request.workspaceId(), moment.image());
                job.uploadedOne();
            } catch (IOException e) {
                job.uploadRefusedOne();
            }
            job.addMoment(new MomentsJob.Moment(moment.at().toString(), moment.offsetSeconds(),
                    moment.quote(), moment.speaker(), imageId, moment.otherCues()));
        }
        if (!moments.isEmpty() && job.uploaded() == 0) {
            throw new IllegalStateException(
                    "aucune des " + moments.size() + " images retenues n'a pu remonter : je "
                            + "n'écris pas un compte rendu de moments sans aucune image, il se "
                            + "lirait comme une transcription découpée");
        }
        if (job.uploadRefused() > 0) {
            job.addGap(new TeamsGap(TeamsGapKind.UPLOAD_REFUSED, "remontée des images",
                    "images retenues que la gateway n'a pas acceptées — leurs moments sont rendus "
                            + "sans capture", job.uploadRefused()));
        }
        say(progress, job.uploaded() + " image" + (job.uploaded() > 1 ? "s" : "")
                + " remontée" + (job.uploaded() > 1 ? "s" : "")
                + ". La vidéo, l'audio et les images écartées restent sur cette machine.");
    }

    private void step(MomentsJob job, MomentsJob.Phase phase, Consumer<String> progress) {
        job.phase(phase);
        store.save(job);
        say(progress, phase.label() + "…");
    }

    private void fail(MomentsJob job, String why, String how, Consumer<String> progress) {
        job.failed(why, how);
        store.save(job);
        say(progress, job.describe());
    }

    private static void say(Consumer<String> progress, String message) {
        if (progress != null && message != null && !message.isBlank()) {
            progress.accept(message);
        }
    }

    /**
     * Ce qu'il y a à faire.
     *
     * @param video       l'enregistrement, <b>sur la machine</b>
     * @param workspaceId terminal Teams où les images retenues iront
     * @param cues        les répliques de la transcription, prises au démarrage
     * @param timeline    l'origine du temps de la vidéo
     * @param threshold   seuil de changement de plan, {@code <= 0} pour le seuil par défaut
     * @param subject     le sujet de la réunion, pour l'écrire dans le compte rendu
     * @param restart     vrai pour relancer explicitement un travail déjà fait ou échoué
     */
    public record Request(Path video, String workspaceId, List<TeamsTranscriptCue> cues,
            MomentTimeline timeline, double threshold, String subject, boolean restart) {

        public Request {
            cues = cues == null ? List.of() : List.copyOf(cues);
            workspaceId = workspaceId == null ? "" : workspaceId.strip();
            subject = subject == null ? "" : subject.strip();
        }
    }
}
