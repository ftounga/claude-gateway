package fr.claudegateway.runner.video;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import fr.claudegateway.runner.teams.AudioTrack;
import fr.claudegateway.runner.teams.LocalToolchain;
import fr.claudegateway.runner.teams.LocalTranscription;
import fr.claudegateway.runner.teams.ProcessRunner;
import fr.claudegateway.runner.teams.TeamsTranscriptCue;
import fr.claudegateway.runner.teams.ToolchainUnavailableException;

/**
 * <b>Transcrire une vidéo sur le poste</b> (F-190 / SF-190-03) quand personne n'a fourni de
 * transcription : l'audio est extrait par {@code ffmpeg}, puis whisper.cpp le transcrit — les mêmes
 * briques que l'enregistrement local de F-91, moteur et modèle rapatriés au premier usage.
 */
public interface VideoTranscriber {

    /**
     * @param video la vidéo, sur le poste
     * @param dir   dossier du travail : {@code transcript.txt} y est écrit
     * @throws Unavailable quand le son ne peut pas être transcrit — jamais une exception brute
     */
    Transcribed transcribe(Path video, Path dir);

    /** Les répliques datées, et le fichier lisible de la transcription complète. */
    record Transcribed(List<TimedTranscript.Line> lines, Path readable) {
    }

    /** Le son n'a pas pu être transcrit : ce qui s'est passé, et ce qu'on peut y faire. */
    final class Unavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String remedy;

        public Unavailable(String message, String remedy) {
            super(message);
            this.remedy = remedy == null ? "" : remedy.strip();
        }

        public String remedy() {
            return remedy;
        }
    }

    /** Aucun moyen de transcrire sur cette machine (tests, montages partiels). */
    static VideoTranscriber none() {
        return (video, dir) -> {
            throw new Unavailable("la transcription sur le poste n'est pas montée sur ce runner", "");
        };
    }

    /** La transcription locale de F-91 : {@code ffmpeg} pour l'audio, whisper.cpp pour le texte. */
    static VideoTranscriber local(LocalToolchain toolchain, ProcessRunner processes) {
        AudioTrack audioTrack = new AudioTrack(toolchain, processes);
        LocalTranscription transcription = new LocalTranscription(toolchain, processes);
        return (video, dir) -> {
            Path audio = null;
            try {
                Files.createDirectories(dir);
                audio = audioTrack.extract(video, dir);
                LocalTranscription.Result result = transcription.transcribe(audio, dir,
                        Instant.EPOCH, "Transcription produite sur ce poste (whisper.cpp) — sans "
                                + "locuteurs, à relire.");
                List<TimedTranscript.Line> lines = new ArrayList<>();
                for (TeamsTranscriptCue cue : result.cues()) {
                    if (cue.isReadable()) {
                        lines.add(new TimedTranscript.Line(cue.at().toEpochMilli() / 1000d,
                                cue.speakerDisplayName(), cue.text()));
                    }
                }
                return new Transcribed(List.copyOf(lines), dir.resolve("transcript.txt"));
            } catch (ToolchainUnavailableException e) {
                throw new Unavailable(e.getMessage(), e.remedy());
            } catch (AudioTrack.AudioUnavailableException e) {
                throw new Unavailable(e.getMessage(), e.detail());
            } catch (LocalTranscription.TranscriptionFailedException e) {
                throw new Unavailable(e.getMessage(), e.detail());
            } catch (IOException e) {
                throw new Unavailable("le dossier du travail n'a pas pu être préparé",
                        e.getMessage());
            } finally {
                if (audio != null) {
                    try {
                        Files.deleteIfExists(audio);
                    } catch (IOException ignored) {
                        // L'audio intermédiaire qui résiste reste sur le poste, jamais ailleurs.
                    }
                }
            }
        };
    }
}
