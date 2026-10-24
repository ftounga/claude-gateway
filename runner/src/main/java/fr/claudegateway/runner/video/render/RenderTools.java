package fr.claudegateway.runner.video.render;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.runner.PathResolver;
import fr.claudegateway.runner.ToolContext;
import fr.claudegateway.runner.ToolException;
import fr.claudegateway.runner.ToolOutcome;

/**
 * <b>Rendre une vidéo sur le poste</b> (F-215 / SF-215-02) : {@code video_render} (préparer la chaîne puis
 * lancer un rendu HyperFrames détaché, rend la main tout de suite) et {@code video_render_status} (suivre).
 *
 * <p>Capacité {@value #CAPABILITY}, annoncée par le {@code ToolRouter} seulement quand l'exécution de commandes
 * est autorisée sur ce poste : ce rendu lance des programmes, il n'ouvre pas une porte que le poste a fermée.</p>
 */
public final class RenderTools {

    /** Capacité annoncée dans la trame {@code ready}. */
    public static final String CAPABILITY = "video_render";
    public static final String RENDER = "video_render";
    public static final String STATUS = "video_render_status";

    /** Dossier des travaux de rendu, sous le projet. */
    static final String RENDER_DIR = ".atelier/rendu";
    static final Set<String> QUALITIES = Set.of("draft", "looks", "delivery");
    static final Set<Integer> FPS = Set.of(24, 25, 30, 60);
    static final int MAX_WORKERS = 8;

    private final RenderToolchain toolchain;
    private final RenderProcesses processes;
    private final ExecutorService background;
    private final Map<String, RenderJob> jobs = new ConcurrentHashMap<>();
    private final Map<String, String> runningByOutput = new ConcurrentHashMap<>();

    public RenderTools(RenderToolchain toolchain, RenderProcesses processes) {
        this(toolchain, processes, Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "video-render");
            thread.setDaemon(true);
            return thread;
        }));
    }

    RenderTools(RenderToolchain toolchain, RenderProcesses processes, ExecutorService background) {
        this.toolchain = toolchain;
        this.processes = processes;
        this.background = background;
    }

    public static boolean handles(String tool) {
        return RENDER.equals(tool) || STATUS.equals(tool);
    }

    /** Ces outils liés au dossier d'un projet. */
    public Bound bind(PathResolver paths) {
        return new Bound(this, paths);
    }

    public ToolOutcome execute(String tool, JsonNode input, ToolContext context, PathResolver paths) {
        try {
            return RENDER.equals(tool) ? start(input, paths) : status(input, paths);
        } catch (ToolException e) {
            return ToolOutcome.error(e);
        }
    }

    // ------------------------------------------------------------------ video_render

    private ToolOutcome start(JsonNode input, PathResolver paths) {
        boolean checkOnly = bool(input, "verifier_seulement");
        String quality = text(input, "qualite").toLowerCase(Locale.ROOT);
        if (quality.isEmpty()) {
            quality = "looks";
        }
        if (!QUALITIES.contains(quality)) {
            throw new ToolException("invalid_input", "qualite : draft, looks ou delivery.");
        }
        Integer fps = integer(input, "fps");
        if (fps != null && !FPS.contains(fps)) {
            throw new ToolException("invalid_input", "fps : 24, 25, 30 ou 60.");
        }
        Integer workers = integer(input, "workers");
        if (workers != null && (workers < 1 || workers > MAX_WORKERS)) {
            throw new ToolException("invalid_input", "workers : de 1 à " + MAX_WORKERS + ".");
        }

        Path project = null;
        String composition = "";
        Path output = null;
        String outputDisplay = "";
        if (!checkOnly) {
            String rawDir = text(input, "dossier");
            if (rawDir.isEmpty()) {
                throw new ToolException("invalid_input",
                        "Donne le dossier du projet HyperFrames (paramètre « dossier »).");
            }
            PathResolver.Resolved resolved = paths.resolve(rawDir);
            project = resolved.path();
            if (!Files.isDirectory(project)) {
                throw new ToolException("not_found", "Je ne trouve pas le dossier « " + resolved.display() + " ».");
            }
            composition = text(input, "composition");
            Path compositionPath = project.resolve(composition.isEmpty() ? "index.html" : composition).normalize();
            if (!compositionPath.startsWith(project) || !Files.isRegularFile(compositionPath)) {
                throw new ToolException("not_found", "Je ne trouve pas la composition « "
                        + (composition.isEmpty() ? "index.html" : composition) + " » dans « " + resolved.display()
                        + " ».");
            }
            String rawOutput = text(input, "sortie");
            if (rawOutput.isEmpty()) {
                rawOutput = paths.relativize(project.resolve("renders").resolve(project.getFileName() + ".mp4"));
            }
            if (!rawOutput.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
                rawOutput = rawOutput + ".mp4";
            }
            PathResolver.Resolved resolvedOutput = paths.resolve(rawOutput);
            output = resolvedOutput.path();
            outputDisplay = resolvedOutput.display();
            String running = runningByOutput.get(output.toString());
            if (running != null) {
                RenderJob existing = jobs.get(running);
                if (existing != null && !existing.phase().isOver()) {
                    return ToolOutcome.ok("Un rendu de cette sortie est déjà en cours : je te rends son suivi.\n"
                            + render(existing));
                }
            }
        }

        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path dir = paths.root().resolve(RENDER_DIR).resolve(id);
        RenderJob job = new RenderJob(id, dir, project == null ? "" : paths.relativize(project), outputDisplay,
                output, checkOnly);
        job.save();
        jobs.put(id, job);
        if (output != null) {
            runningByOutput.put(output.toString(), id);
        }
        List<String> options = options(quality, composition, fps, workers);
        Path projectDir = project;
        Path outputPath = output;
        background.execute(() -> run(job, projectDir, outputPath, options));
        return ToolOutcome.ok(render(job) + "\nC'est long : suis avec video_render_status (job_id ci-dessus). "
                + "Le rendu continue même si le tour se termine.");
    }

    private static List<String> options(String quality, String composition, Integer fps, Integer workers) {
        List<String> options = new ArrayList<>(List.of("--quality", quality));
        if (!composition.isEmpty()) {
            options.addAll(List.of("-c", composition));
        }
        if (fps != null) {
            options.addAll(List.of("--fps", String.valueOf(fps)));
        }
        if (workers != null) {
            options.addAll(List.of("--workers", String.valueOf(workers)));
        }
        return options;
    }

    /** La préparation puis le lancement détaché — hors du fil de l'appel. */
    void run(RenderJob job, Path project, Path output, List<String> options) {
        Consumer<String> say = message -> note(job, message);
        try {
            RenderToolchain.Prepared prepared = toolchain.prepare(say);
            job.prepared(prepared.report());
            if (job.checkOnly()) {
                job.done();
                job.save();
                return;
            }
            Files.createDirectories(output.toAbsolutePath().getParent());
            // Une sortie d'un rendu précédent fausserait le constat « vidéo produite ».
            Files.deleteIfExists(output);
            List<String> command = new ArrayList<>(List.of(prepared.node().toString(),
                    prepared.hyperframes().toString(), "render", project.toString(), "-o", output.toString()));
            command.addAll(options);
            Map<String, String> env = new HashMap<>(prepared.env());
            env.put("HYPERFRAMES_RENDER_DETACHED", "1");
            long pid = processes.launchDetached(command, project, env, job.log());
            job.rendering(pid);
            job.save();
        } catch (RenderFailure failure) {
            job.fail(failure.getMessage(), failure.remedy());
            job.save();
        } catch (IOException | RuntimeException e) {
            job.fail("Lancement du rendu impossible (" + (e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage()) + ").", "Si l'exécution est bloquée par la politique du poste, je ne la "
                    + "contourne pas : demandez à votre support d'autoriser Node et HyperFrames, puis relancez "
                    + "video_render.");
            job.save();
        }
    }

    /** Une ligne du runner dans le journal du rendu : on la relit au suivi. */
    private static void note(RenderJob job, String message) {
        try {
            Files.createDirectories(job.log().getParent());
            Files.writeString(job.log(), "[runner] " + message + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Le suivi dira l'étape, à défaut du détail.
        }
    }

    // ------------------------------------------------------------------ video_render_status

    private ToolOutcome status(JsonNode input, PathResolver paths) {
        String id = text(input, "job_id").toLowerCase(Locale.ROOT);
        if (!id.matches("[0-9a-f]{12}")) {
            throw new ToolException("invalid_input", "Donne le job_id rendu par video_render.");
        }
        Path dir = paths.root().resolve(RENDER_DIR).resolve(id);
        RenderJob job = jobs.get(id);
        if (job == null || !job.log().getParent().equals(dir)) {
            job = RenderJob.load(dir).orElseThrow(() -> new ToolException("not_found",
                    "Aucun rendu « " + id + " » dans ce projet. Relance video_render."));
            jobs.put(id, job);
        }
        if (job.phase() == RenderJob.Phase.RENDU) {
            job.refresh(job.pid() > 0 && processes.alive(job.pid()));
            if (job.phase().isOver()) {
                job.save();
            }
        }
        if (job.phase().isOver() && job.outputPath() != null) {
            runningByOutput.remove(job.outputPath().toString(), id);
        }
        return ToolOutcome.ok(render(job));
    }

    // ------------------------------------------------------------------ rendu du texte

    static String render(RenderJob job) {
        StringBuilder out = new StringBuilder();
        out.append("job_id : ").append(job.id()).append('\n');
        out.append("étape : ").append(job.phase().label()).append('\n');
        if (!job.checkOnly()) {
            out.append("source : ").append(job.source()).append(" → sortie : ").append(job.output()).append('\n');
        }
        if (job.phase() == RenderJob.Phase.RENDU) {
            String progress = job.progress();
            if (!progress.isEmpty()) {
                out.append("avancement : ").append(progress).append('\n');
            }
        }
        out.append("écoulé : ").append(duration(job.elapsed())).append('\n');
        if (!job.report().isEmpty()) {
            out.append("chaîne : ").append(job.report()).append('\n');
        }
        switch (job.phase()) {
            case TERMINE -> {
                if (job.checkOnly()) {
                    out.append("La chaîne de rendu est prête sur ce poste.\n");
                } else {
                    out.append(String.format(Locale.ROOT, "vidéo : %s (%.1f Mo) — relis-la (images clés) puis "
                            + "publie-la avec video_publier.%n", job.output(), job.outputSize() / 1_048_576d));
                }
            }
            case ECHOUE -> {
                out.append("échec : ").append(job.failure()).append('\n');
                if (!job.remedy().isEmpty()) {
                    out.append("remède : ").append(job.remedy()).append('\n');
                }
                out.append("Répète l'échec et le remède tels quels à l'utilisateur.\n");
            }
            default -> {
                String lines = job.lastLogLines(3);
                if (!lines.isEmpty()) {
                    out.append("journal : ").append(lines).append('\n');
                }
                out.append("Toujours en cours : redemande video_render_status dans un moment.\n");
            }
        }
        return out.toString().strip();
    }

    private static String duration(Duration d) {
        long s = Math.max(0, d.getSeconds());
        return s < 60 ? s + " s" : (s / 60) + " min " + (s % 60) + " s";
    }

    private static String text(JsonNode input, String field) {
        JsonNode node = input == null ? null : input.get(field);
        return node == null || node.isNull() ? "" : node.asText("").strip();
    }

    private static boolean bool(JsonNode input, String field) {
        JsonNode node = input == null ? null : input.get(field);
        return node != null && (node.asBoolean(false) || "true".equalsIgnoreCase(node.asText()));
    }

    private static Integer integer(JsonNode input, String field) {
        JsonNode node = input == null ? null : input.get(field);
        if (node == null || node.isNull() || node.asText("").isBlank()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        try {
            return Integer.parseInt(node.asText().strip());
        } catch (NumberFormatException e) {
            throw new ToolException("invalid_input", "« " + field + " » doit être un entier.");
        }
    }

    /** Les outils liés au projet d'un aiguillage. */
    public record Bound(RenderTools tools, PathResolver paths) {

        public ToolOutcome execute(String tool, JsonNode input, ToolContext context) {
            return tools.execute(tool, input, context, paths);
        }
    }
}
