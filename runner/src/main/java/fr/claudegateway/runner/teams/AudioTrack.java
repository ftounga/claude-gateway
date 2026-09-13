package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * <b>L'audio tiré d'une capture</b> (F-91 / SF-91-03), et il ne va nulle part.
 *
 * <h2>Pourquoi une piste à part, plutôt que donner la vidéo au moteur</h2>
 *
 * <p>Un moteur de reconnaissance de parole veut un format précis : <b>mono, 16 kHz, PCM 16 bits</b>.
 * Lui passer un MP4 le ferait décoder lui-même, avec ses propres suppositions — ou refuser. Un seul
 * passage {@code ffmpeg} produit exactement ce qu'il attend, et ce passage-là est le même partout.</p>
 *
 * <h2>Le fichier reste dans le dossier de la capture</h2>
 *
 * <p>Comme la vidéo dont il sort. <b>Rien ne remonte</b> : ni la vidéo, ni l'audio, ni ce fichier
 * intermédiaire — seulement le texte. Envoyer l'audio à un service annulerait le bénéfice de garder
 * la vidéo en local, et c'est écrit ici pour que la tentation soit nommée.</p>
 */
public final class AudioTrack {

    /** Ce qu'un moteur de reconnaissance de parole attend, partout. */
    static final String SAMPLE_RATE = "16000";
    /** Une heure de réunion se dé-multiplexe en quelques minutes au pire. */
    static final long EXTRACTION_TIMEOUT_MS = 1_800_000L;

    private final LocalToolchain toolchain;
    private final ProcessRunner processes;

    public AudioTrack(LocalToolchain toolchain, ProcessRunner processes) {
        this.toolchain = toolchain;
        this.processes = processes;
    }

    /**
     * Extrait l'audio d'un enregistrement.
     *
     * @param video l'enregistrement, <b>sur la machine</b>
     * @param into  le dossier de la capture — jamais un chemin venu d'un appel d'outil
     * @return le fichier audio produit
     * @throws ToolchainUnavailableException quand {@code ffmpeg} n'est ni là ni rapatriable
     * @throws AudioUnavailableException     quand la vidéo est absente, muette, ou illisible
     */
    public Path extract(Path video, Path into) {
        if (video == null || !Files.isRegularFile(video)) {
            throw new AudioUnavailableException(
                    "Je ne trouve pas l'enregistrement dont il faudrait tirer l'audio.",
                    "Vérifiez que la capture s'est bien terminée : une capture interrompue ne "
                            + "laisse pas toujours de fichier.");
        }
        Path ffmpeg = toolchain.require(LocalTool.ffmpeg());
        Path audio = into.resolve("audio.wav");
        try {
            Files.createDirectories(into);
            Files.deleteIfExists(audio);
            ProcessRunner.ProcessResult result =
                    processes.run(command(ffmpeg, video, audio), into, EXTRACTION_TIMEOUT_MS);
            if (!result.succeeded()) {
                // Rien de partiel ne survit : un audio tronqué donnerait une transcription qui
                // s'arrête au milieu SANS LE DIRE — le « plausible et faux » que ce volet interdit.
                Files.deleteIfExists(audio);
                throw new AudioUnavailableException(
                        result.timedOut()
                                ? "L'extraction de l'audio a dépassé le délai et a été arrêtée."
                                : "ffmpeg n'a pas pu tirer d'audio de cet enregistrement.",
                        result.tail());
            }
            if (!Files.isRegularFile(audio) || Files.size(audio) <= 0L) {
                throw new AudioUnavailableException(
                        "Cet enregistrement ne porte aucun son exploitable.",
                        "Une capture démarrée sans son n'a pas de transcription, et c'est normal : "
                                + "redemandez une capture avec le son si vous en voulez une.");
            }
            return audio;
        } catch (IOException e) {
            throw new AudioUnavailableException(
                    "Le dossier de travail de l'extraction audio n'a pas pu être préparé.",
                    e.getMessage() == null ? "" : e.getMessage());
        }
    }

    /**
     * La ligne de commande, <b>argument par argument</b> — jamais concaténée dans un shell (leçon de
     * SF-38-23).
     */
    static List<String> command(Path ffmpeg, Path video, Path audio) {
        return List.of(
                ffmpeg.toAbsolutePath().toString(),
                "-hide_banner",
                "-nostdin",
                "-y",
                "-i", video.toAbsolutePath().toString(),
                // Pas de vidéo dans la sortie : on ne réencode pas des images pour rien.
                "-vn",
                "-ac", "1",
                "-ar", SAMPLE_RATE,
                "-c:a", "pcm_s16le",
                audio.toAbsolutePath().toString());
    }

    /** L'audio n'a pas pu être obtenu. Porte une phrase et, quand {@code ffmpeg} a parlé, ses lignes. */
    public static class AudioUnavailableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String detail;

        public AudioUnavailableException(String message, String detail) {
            super(message);
            this.detail = detail == null ? "" : detail.strip();
        }

        /** Les dernières lignes d'{@code ffmpeg}, ou le remède. */
        public String detail() {
            return detail;
        }
    }
}
