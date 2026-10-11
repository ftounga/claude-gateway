package fr.claudegateway.runner.video;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.runner.PathResolver;
import fr.claudegateway.runner.ToolContext;
import fr.claudegateway.runner.ToolException;
import fr.claudegateway.runner.ToolOutcome;

/**
 * <b>Regarder une vidéo</b> (F-190 / SF-190-01) : les deux outils {@code video_images} (démarrer)
 * et {@code video_images_status} (suivre, puis lister les images).
 *
 * <p>Ils ne dépendent <b>pas</b> du volet Teams : n'importe quelle vidéo présente sur le poste —
 * une démo enregistrée, une capture d'écran animée — se regarde de la même façon. L'API du modèle ne
 * prend pas de vidéo, mais elle <b>voit</b> des images : on extrait celles qui portent le sens, et
 * l'agent les ouvre par {@code read_file} (F-121 / SF-121-15). La vidéo ne quitte jamais la
 * machine.</p>
 */
public final class VideoTools {

    /** Capacité annoncée dans la trame {@code ready} : sans elle, la gateway ne déclare pas ces outils. */
    public static final String CAPABILITY = "video";
    public static final String IMAGES = "video_images";
    public static final String STATUS = "video_images_status";

    /** Dossier des images, sous le projet : {@code read_file} les atteint par un chemin relatif. */
    static final String VIDEO_DIR = ".atelier/video";

    /** Plafond d'une demande en intervalle — le même que celui des changements de plan (F-90). */
    static final int MAX_INTERVAL_FRAMES = 60;
    static final double DEFAULT_EVERY_SECONDS = 5d;
    static final double MAX_EVERY_SECONDS = 3600d;

    private final VideoFramesWorker worker;

    public VideoTools(VideoFramesWorker worker) {
        this.worker = worker;
    }

    /** Ces outils liés au dossier d'un projet : ce que le {@code ToolRouter} de ce projet appelle. */
    public Bound bind(PathResolver paths) {
        return new Bound(this, paths);
    }

    public static boolean handles(String tool) {
        return IMAGES.equals(tool) || STATUS.equals(tool);
    }

    /**
     * @param paths résolution des chemins <b>du projet du tour</b> : la vidéo s'y trouve, les images
     *              y sont écrites
     */
    public ToolOutcome execute(String tool, JsonNode input, ToolContext context, PathResolver paths) {
        try {
            return IMAGES.equals(tool) ? start(input, context, paths) : status(input, paths);
        } catch (ToolException e) {
            return ToolOutcome.error(e);
        }
    }

    // ------------------------------------------------------------------ video_images

    private ToolOutcome start(JsonNode input, ToolContext context, PathResolver paths) {
        String raw = text(input, "path");
        if (raw.isBlank()) {
            raw = text(input, "video");
        }
        if (raw.isBlank()) {
            throw new ToolException("invalid_input",
                    "Donne le chemin de la vidéo sur le poste (paramètre « path »).");
        }
        PathResolver.Resolved resolved = paths.resolve(raw);
        Path video = resolved.path();
        if (!Files.isRegularFile(video)) {
            throw new ToolException("not_found", "Je ne trouve pas de fichier vidéo à « "
                    + resolved.display() + " ». Vérifie le chemin (un dépôt arrive dans "
                    + ".atelier/entrees/).");
        }
        VideoFramesWorker.Mode mode = mode(text(input, "mode"));
        double from = 0d;
        double to = 0d;
        double every = 0d;
        if (mode == VideoFramesWorker.Mode.INTERVALLE) {
            from = seconds(input, "from");
            to = seconds(input, "to");
            every = input != null && input.hasNonNull("every_seconds")
                    ? seconds(input, "every_seconds") : DEFAULT_EVERY_SECONDS;
            checkInterval(from, to, every);
        }
        String videoKey = identity(video);
        Path baseDir = paths.root().resolve(VIDEO_DIR).resolve(slug(video) + "-"
                + videoKey.substring(0, 8));
        Path dir = mode == VideoFramesWorker.Mode.PLANS ? baseDir
                : baseDir.resolve("zoom-" + whole(from) + "-" + whole(to) + "-" + whole(every));
        String id = mode == VideoFramesWorker.Mode.PLANS ? videoKey
                : hash(videoKey + "|" + from + "|" + to + "|" + every);

        VideoJob job = worker.startOrResume(new VideoFramesWorker.Request(id, video, dir, mode,
                        from, to, every, bool(input, "restart"), paths::relativize),
                message -> context.stream("stdout", message + System.lineSeparator()));
        return ToolOutcome.ok(render(job, true));
    }

    // ------------------------------------------------------------------ video_images_status

    private ToolOutcome status(JsonNode input, PathResolver paths) {
        String id = text(input, "job_id");
        if (id.isBlank()) {
            throw new ToolException("invalid_input",
                    "Donne le job_id rendu par video_images.");
        }
        Optional<VideoJob> job = worker.find(id, null)
                .or(() -> locate(paths.root().resolve(VIDEO_DIR), id));
        if (job.isEmpty()) {
            throw new ToolException("not_found", "Aucun travail d'extraction ne porte le numéro « "
                    + id + " » sur ce poste. Relance video_images sur la vidéo.");
        }
        return ToolOutcome.ok(render(job.get(), false));
    }

    /** Un travail d'un runner précédent : son {@code job.json} est dans l'un des dossiers d'images. */
    private Optional<VideoJob> locate(Path videoRoot, String id) {
        if (!Files.isDirectory(videoRoot)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.find(videoRoot, 3,
                (path, attributes) -> path.getFileName().toString().equals(VideoJob.FILE_NAME))) {
            for (Path file : stream.toList()) {
                Optional<VideoJob> found = worker.find(id, file.getParent());
                if (found.isPresent()) {
                    return found;
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ le rendu

    /**
     * Ce que lit le modèle. Un travail terminé <b>liste chaque image</b> avec son instant : c'est ce
     * qui lui permet de choisir quoi ouvrir, et de dater ce qu'il a vu.
     */
    static String render(VideoJob job, boolean justStarted) {
        StringBuilder out = new StringBuilder();
        out.append("job_id : ").append(job.id()).append('\n');
        out.append("vidéo : ").append(job.video()).append(" (mode ").append(job.mode())
                .append(")\n");
        out.append("étape : ").append(job.phase().label()).append('\n');
        switch (job.phase()) {
            case TERMINE -> {
                List<VideoJob.Frame> frames = job.frames();
                List<VideoJob.Sheet> sheets = job.sheets();
                out.append(job.describe()).append('\n');
                if (frames.isEmpty()) {
                    out.append("Aucune image retenue. Pour une démo qui défile dans un même écran, "
                            + "relance video_images en mode « intervalle » (from, to, "
                            + "every_seconds).\n");
                } else if (!sheets.isEmpty()) {
                    // SF-190-02 : les planches d'abord — 16 vignettes pour le prix d'une image.
                    out.append("Ouvre d'abord les PLANCHES avec read_file (16 vignettes "
                            + "horodatées chacune, environ 1 800 tokens), puis seulement les images "
                            + "en pleine taille dont tu as besoin pour lire un détail. Ne décris "
                            + "jamais ce que tu n'as pas ouvert.\n");
                    int number = 1;
                    for (VideoJob.Sheet sheet : sheets) {
                        out.append("Planche ").append(number++).append(" : ").append(sheet.path())
                                .append('\n');
                        for (VideoJob.Cell cell : sheet.cells()) {
                            out.append("  case ").append(cell.index()).append(" · ")
                                    .append(cell.at()).append(" · ").append(cell.framePath())
                                    .append('\n');
                        }
                    }
                    out.append("Pour voir un passage de plus près : video_images en mode "
                            + "« intervalle » avec from, to et every_seconds.\n");
                } else {
                    out.append("Ouvre les images avec read_file pour les VOIR (chacune se paie : "
                            + "environ 1 200 tokens). Ne décris jamais une image que tu n'as pas "
                            + "ouverte.\n");
                    for (VideoJob.Frame frame : frames) {
                        out.append(frame.at()).append("  ").append(frame.path()).append('\n');
                    }
                    out.append("Pour voir un passage de plus près : video_images en mode "
                            + "« intervalle » avec from, to et every_seconds.\n");
                }
                for (String gap : job.gaps()) {
                    out.append("Manque : ").append(gap).append('\n');
                }
            }
            case ECHOUE -> {
                out.append("Échec : ").append(job.failure()).append('\n');
                if (!job.remedy().isBlank()) {
                    out.append("Remède : ").append(job.remedy()).append('\n');
                }
                out.append("Dis-le à l'utilisateur tel quel : sans ces images, tu n'as PAS vu "
                        + "la vidéo.\n");
            }
            default -> out.append(justStarted
                    ? "L'extraction tourne sur le poste (plusieurs minutes pour une longue vidéo). "
                            + "Suis-la avec video_images_status et ne conclus rien sur le contenu "
                            + "visuel avant qu'elle soit terminée.\n"
                    : "Toujours en cours : redemande video_images_status dans un moment.\n");
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ entrées

    private static VideoFramesWorker.Mode mode(String raw) {
        if (raw.isBlank() || "plans".equalsIgnoreCase(raw)) {
            return VideoFramesWorker.Mode.PLANS;
        }
        if ("intervalle".equalsIgnoreCase(raw) || "interval".equalsIgnoreCase(raw)) {
            return VideoFramesWorker.Mode.INTERVALLE;
        }
        throw new ToolException("invalid_input",
                "Mode inconnu « " + raw + " » : « plans » (défaut) ou « intervalle ».");
    }

    static void checkInterval(double from, double to, double every) {
        if (from < 0d || to <= from) {
            throw new ToolException("invalid_input",
                    "Intervalle invalide : « to » doit être après « from » (en secondes ou "
                            + "HH:MM:SS).");
        }
        if (every < 1d || every > MAX_EVERY_SECONDS) {
            throw new ToolException("invalid_input",
                    "« every_seconds » doit être entre 1 et 3600.");
        }
        long count = (long) Math.floor((to - from) / every) + 1;
        if (count > MAX_INTERVAL_FRAMES) {
            long suggested = (long) Math.ceil((to - from) / (MAX_INTERVAL_FRAMES - 1));
            throw new ToolException("invalid_input",
                    "Cet intervalle donnerait " + count + " images ; le plafond est de "
                            + MAX_INTERVAL_FRAMES + " par demande. Prends every_seconds à "
                            + Math.max(1, suggested) + " ou plus, ou un intervalle plus court.");
        }
    }

    /** Secondes, en nombre ou en {@code HH:MM:SS} / {@code MM:SS}. */
    static double seconds(JsonNode input, String field) {
        JsonNode node = input == null ? null : input.get(field);
        if (node == null || node.isNull()) {
            throw new ToolException("invalid_input",
                    "Le mode « intervalle » demande « " + field + " » (secondes ou HH:MM:SS).");
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        String raw = node.asText().strip();
        try {
            if (!raw.contains(":")) {
                return Double.parseDouble(raw.replace(',', '.'));
            }
            String[] parts = raw.split(":");
            double total = 0d;
            for (String part : parts) {
                total = total * 60d + Double.parseDouble(part.replace(',', '.'));
            }
            return total;
        } catch (NumberFormatException e) {
            throw new ToolException("invalid_input",
                    "« " + field + " » illisible : « " + raw + " » (secondes ou HH:MM:SS).");
        }
    }

    private static String text(JsonNode input, String field) {
        JsonNode node = input == null ? null : input.get(field);
        return node == null || node.isNull() ? "" : node.asText("").strip();
    }

    private static boolean bool(JsonNode input, String field) {
        JsonNode node = input == null ? null : input.get(field);
        return node != null && (node.asBoolean(false) || "true".equalsIgnoreCase(node.asText()));
    }

    // ------------------------------------------------------------------ identité

    /** Identité stable d'une vidéo : chemin, taille, date — la même vidéo retrouve son travail. */
    static String identity(Path video) {
        try {
            return hash(video.toAbsolutePath().normalize() + "|" + Files.size(video) + "|"
                    + Files.getLastModifiedTime(video).toMillis());
        } catch (IOException e) {
            return hash(video.toAbsolutePath().normalize().toString());
        }
    }

    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    /** Nom de dossier lisible tiré du nom de la vidéo : sans accents, espaces ni caractères spéciaux. */
    static String slug(Path video) {
        String name = video.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String plain = Normalizer.normalize(base, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (plain.length() > 40) {
            plain = plain.substring(0, 40).replaceAll("-$", "");
        }
        return plain.isEmpty() ? "video" : plain;
    }

    private static String whole(double seconds) {
        return Long.toString(Math.round(seconds));
    }

    /** Les outils vidéo d'UN projet. */
    public record Bound(VideoTools tools, PathResolver paths) {

        public ToolOutcome execute(String tool, JsonNode input, ToolContext context) {
            return tools.execute(tool, input, context, paths);
        }
    }
}
