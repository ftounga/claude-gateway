package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>L'extraction aux changements de plan</b> (F-90 / SF-90-01).
 *
 * <h2>Pourquoi aux changements de plan, et pas à intervalle fixe</h2>
 *
 * <p>Une heure de réunion à une image par seconde donne <b>3 600 images</b>. Aux changements de plan,
 * puis dédoublonnée, elle en donne <b>20 à 60</b>. <b>Chaque image analysée se paie</b> : ce tri
 * n'est pas une optimisation de confort, c'est ce qui rend la fonctionnalité finançable.</p>
 *
 * <h2>Le seuil, en une phrase</h2>
 *
 * <p><b>Un curseur qui bouge n'est pas un nouveau plan.</b> Le filtre {@code scene} d'{@code ffmpeg}
 * compare deux trames successives et rend une valeur entre 0 et 1 ; sous {@value #SCENE_THRESHOLD},
 * c'est du mouvement dans un même plan (curseur, défilement, visage qui parle), au-dessus, c'est un
 * plan nouveau (diapositive suivante, partage d'écran qui démarre).</p>
 *
 * <h2>L'horodatage vient du journal, jamais du numéro de fichier</h2>
 *
 * <p>Le filtre {@code showinfo} écrit, pour chaque image retenue, son {@code pts_time} — sa
 * position <b>exacte</b> dans la vidéo. Le numéro de fichier, lui, ne dit que le rang. Or
 * <b>c'est l'alignement qui fait la valeur de F-90, pas l'extraction</b> : une image dont on ne
 * connaîtrait que le rang ne pourrait être rapprochée d'aucune phrase.</p>
 *
 * <h2>Une extraction partielle est jetée</h2>
 *
 * <p>Quand {@code ffmpeg} s'arrête au milieu, les images déjà écrites sont effacées. Garder la
 * première moitié d'une réunion <b>sans le dire</b> est exactement le « plausible et faux » que ce
 * volet interdit — et le refus rend <b>les dernières lignes d'{@code ffmpeg}</b>, pas un « échec »
 * nu.</p>
 */
public final class SceneFrames {

    /** Le seuil de changement de plan. Constante nommée : elle se règle sans se chercher. */
    public static final double SCENE_THRESHOLD = 0.4d;
    /** Largeur maximale des images produites : au-delà, on paie des pixels que personne ne lit. */
    static final int MAX_WIDTH = 1280;
    /** Une extraction sur une réunion d'une heure : quelques minutes de décodage au pire. */
    static final long EXTRACTION_TIMEOUT_MS = 1_800_000L;
    /** Préfixe des fichiers produits. */
    static final String FRAME_PREFIX = "frame-";

    /** {@code [Parsed_showinfo_1 @ 0x…] n:0 pts:12345 pts_time:5.12 …} */
    private static final Pattern PTS_TIME = Pattern.compile("pts_time:\\s*([0-9]+(?:\\.[0-9]+)?)");

    private final LocalToolchain toolchain;
    private final ProcessRunner processes;

    public SceneFrames(LocalToolchain toolchain, ProcessRunner processes) {
        this.toolchain = toolchain;
        this.processes = processes;
    }

    /**
     * Extrait les images des changements de plan d'un enregistrement.
     *
     * @param video     fichier vidéo <b>sur la machine</b> — il y reste, et ne remonte jamais
     * @param outputDir dossier de travail, vidé avant usage
     * @param threshold seuil de changement de plan, {@code <= 0} pour le seuil par défaut
     * @return les images brutes, dans l'ordre du temps, et les manques déjà constatés
     * @throws ToolchainUnavailableException quand {@code ffmpeg} n'est ni là ni rapatriable
     * @throws SceneExtractionException      quand la vidéo est absente ou qu'{@code ffmpeg} échoue
     */
    public Extraction extract(Path video, Path outputDir, double threshold) {
        if (video == null || !Files.isRegularFile(video)) {
            throw new SceneExtractionException("not_found",
                    "Je ne trouve pas de fichier vidéo à « "
                            + (video == null ? "(chemin vide)" : video) + " ».",
                    "Vérifiez le chemin, ou donnez-le en absolu. L'enregistrement reste sur cette "
                            + "machine : je ne le télécharge pas depuis Teams.");
        }
        Path ffmpeg = toolchain.require(LocalTool.ffmpeg());
        double effective = threshold > 0d && threshold < 1d ? threshold : SCENE_THRESHOLD;
        try {
            Files.createDirectories(outputDir);
            clear(outputDir);
            ProcessRunner.ProcessResult result = processes.run(
                    command(ffmpeg, video, outputDir, effective), outputDir, EXTRACTION_TIMEOUT_MS);
            if (!result.succeeded()) {
                // A3 : rien de partiel ne survit.
                clear(outputDir);
                throw new SceneExtractionException(
                        result.timedOut() ? "timeout" : "extraction_failed",
                        result.timedOut()
                                ? "L'extraction des images a dépassé le délai et a été arrêtée ; "
                                        + "rien n'a été gardé."
                                : "ffmpeg n'a pas pu traiter cet enregistrement jusqu'au bout ; "
                                        + "les images déjà écrites ont été jetées — une extraction "
                                        + "partielle donnerait un compte rendu plausible et faux.",
                        result.tail());
            }
            return pair(outputDir, timestamps(result));
        } catch (IOException e) {
            throw new SceneExtractionException("extraction_failed",
                    "Le dossier de travail de l'extraction n'a pas pu être préparé.",
                    e.getMessage() == null ? "" : e.getMessage());
        }
    }

    /** Le seuil par défaut. */
    public Extraction extract(Path video, Path outputDir) {
        return extract(video, outputDir, SCENE_THRESHOLD);
    }

    /**
     * La ligne de commande, argument par argument — <b>jamais</b> concaténée dans un shell : un
     * chemin de fichier avec un espace ou un point-virgule ne peut pas devenir une commande
     * (leçon de SF-38-23).
     */
    static List<String> command(Path ffmpeg, Path video, Path outputDir, double threshold) {
        return List.of(
                ffmpeg.toAbsolutePath().toString(),
                "-hide_banner",
                // Le runner n'a pas de terminal à prêter : sans cela, ffmpeg peut attendre une
                // réponse à « écraser le fichier ? » et ne jamais rendre la main.
                "-nostdin",
                "-y",
                "-i", video.toAbsolutePath().toString(),
                "-vf", "select='gt(scene," + format(threshold) + ")',showinfo,"
                        + "scale=w='min(" + MAX_WIDTH + ",iw)':h=-2",
                // Une image par changement de plan, et pas une cadence régulière recalculée.
                "-vsync", "vfr",
                "-q:v", "4",
                outputDir.resolve(FRAME_PREFIX + "%05d.jpg").toAbsolutePath().toString());
    }

    /** Les {@code pts_time} du journal {@code showinfo}, dans l'ordre où ils ont été écrits. */
    static List<Double> timestamps(ProcessRunner.ProcessResult result) {
        List<Double> times = new ArrayList<>();
        List<String> lines = new ArrayList<>(result.stderr());
        lines.addAll(result.stdout());
        for (String line : lines) {
            if (line == null || !line.contains("showinfo")) {
                continue;
            }
            Matcher matcher = PTS_TIME.matcher(line);
            if (matcher.find()) {
                try {
                    times.add(Double.parseDouble(matcher.group(1)));
                } catch (NumberFormatException ignored) {
                    // Une ligne de journal illisible ne vaut pas la peine d'arrêter l'extraction :
                    // l'appariement ci-dessous comptera l'écart et le nommera.
                }
            }
        }
        return times;
    }

    /**
     * Rapproche les fichiers produits et les horodatages du journal.
     *
     * <p>Un écart entre les deux comptes est un <b>manque nommé</b>, pas un ajustement silencieux :
     * il voudrait dire qu'on ne sait plus à quel instant appartient telle image, et une image mal
     * datée dans un compte rendu est pire qu'une image absente.</p>
     */
    private static Extraction pair(Path outputDir, List<Double> times) throws IOException {
        List<Path> files;
        try (var stream = Files.list(outputDir)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(FRAME_PREFIX))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        List<TeamsGap> gaps = new ArrayList<>();
        int paired = Math.min(files.size(), times.size());
        if (files.size() != times.size()) {
            gaps.add(new TeamsGap(TeamsGapKind.FRAME_UNDATED, "extraction des images",
                    "ffmpeg a écrit " + files.size() + " images mais daté " + times.size()
                            + " changements de plan : les images non datées sont écartées — une "
                            + "image mal datée serait pire qu'une image absente",
                    Math.max(1, Math.abs(files.size() - times.size()))));
        }
        List<SceneFrame> frames = new ArrayList<>(paired);
        for (int index = 0; index < paired; index++) {
            frames.add(new SceneFrame(times.get(index), files.get(index), 0L));
        }
        return new Extraction(frames, gaps);
    }

    private static void clear(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path path : stream.toList()) {
                if (Files.isRegularFile(path)
                        && path.getFileName().toString().startsWith(FRAME_PREFIX)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static String format(double threshold) {
        return String.format(java.util.Locale.ROOT, "%.3f", threshold);
    }

    /** Ce qu'{@code ffmpeg} a sorti, avant tout tri : les images brutes et les manques constatés. */
    public record Extraction(List<SceneFrame> frames, List<TeamsGap> gaps) {

        public Extraction {
            frames = frames == null ? List.of() : List.copyOf(frames);
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
        }
    }

    /**
     * L'extraction n'a pas abouti. Porte un <b>code</b>, une phrase, et — quand {@code ffmpeg} a
     * parlé — <b>ses dernières lignes</b>, parce que « échec » n'aide personne.
     */
    public static class SceneExtractionException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String code;
        private final String detail;

        public SceneExtractionException(String code, String message, String detail) {
            super(message);
            this.code = code == null ? "extraction_failed" : code;
            this.detail = detail == null ? "" : detail.strip();
        }

        public String code() {
            return code;
        }

        /** Les dernières lignes d'{@code ffmpeg}, ou le remède. */
        public String detail() {
            return detail;
        }

        public String sentence() {
            return detail.isEmpty() ? getMessage()
                    : getMessage() + System.lineSeparator() + detail;
        }
    }
}
