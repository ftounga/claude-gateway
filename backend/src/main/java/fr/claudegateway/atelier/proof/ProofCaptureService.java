package fr.claudegateway.atelier.proof;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.storage.WorkspaceStorage;
import fr.claudegateway.diagrams.DiagramRejectedException;
import fr.claudegateway.diagrams.DiagramRendererUnavailableException;
import fr.claudegateway.mcp.McpSecretFilter;

/**
 * <b>Les captures de preuve</b> (F-212 / SF-212-03) : du relevé brut rendu par le runner
 * ({@code proof_run}) aux images rangées, au bloc du fil et au texte court rendu au modèle.
 *
 * <ol>
 *   <li><b>Filtre de secrets</b> sur les commandes et les sorties, avant tout rendu ou stockage ;</li>
 *   <li><b>rendu</b> par {@code diagram-renderer} ({@link TerminalShotRenderer}) — le contenu de l'image
 *       vient toujours de la sortie du poste, jamais d'un texte du modèle ;</li>
 *   <li><b>stockage</b> sous {@code preuves/{userId}/{workspaceId}/{id}.png} (clé reconstruite) et une
 *       ligne {@code proof_captures} par image ;</li>
 *   <li><b>texte au modèle</b> borné (≈ 2 000 caractères par commande, 6 000 au total) : les images ne
 *       lui reviennent jamais (exigence de coût du PO).</li>
 * </ol>
 */
@Service
public class ProofCaptureService {

    static final String PREFIX = "preuves/";
    /** Sortie rendue au modèle, par commande (tête + fin). */
    static final int MODEL_CHARS_PER_COMMAND = 2_000;
    /** Sortie rendue au modèle, au total. */
    static final int MODEL_CHARS_TOTAL = 6_000;
    private static final int MAX_TITLE = 300;
    private static final int MAX_STEP = 120;

    private final ProofCaptureRepository repository;
    private final WorkspaceStorage storage;
    private final TerminalShotRenderer renderer;
    private final ObjectMapper mapper;
    private final McpSecretFilter secrets = new McpSecretFilter();

    public ProofCaptureService(ProofCaptureRepository repository, WorkspaceStorage storage,
            TerminalShotRenderer renderer, ObjectMapper mapper) {
        this.repository = repository;
        this.storage = storage;
        this.renderer = renderer;
        this.mapper = mapper;
    }

    /** Issue d'une capture : le bloc du fil ({@code null} si aucune image) et le texte au modèle. */
    public record Result(ProofBlock block, String modelText) {
    }

    /**
     * Un fichier à photographier (F-268 / SF-268-02) : son contenu RÉEL, lu sur le poste au moment de la
     * capture, et ce que l'agent veut y montrer.
     *
     * @param path       chemin relatif au projet
     * @param content    contenu lu ({@code null} si la lecture a échoué)
     * @param truncated  vrai si le poste a tronqué la lecture
     * @param from       première ligne demandée (facultative)
     * @param to         dernière ligne demandée (facultative)
     * @param highlights lignes à surligner
     * @param note       annotation courte (facultative)
     * @param error      raison de l'échec de lecture ({@code null} si lu)
     */
    public record FileSource(String path, String content, boolean truncated, Integer from, Integer to,
            List<Integer> highlights, String note, String error) {
    }

    /**
     * Range les captures d'une session.
     *
     * @param report relevé JSON du runner ({@code proof_run})
     */
    public Result capture(UUID userId, UUID workspaceId, String callId, String title, String step,
            String report) {
        return capture(userId, workspaceId, callId, title, step, report == null ? "" : report, List.of());
    }

    /**
     * Range les captures d'un appel : la session de commandes (si {@code report} n'est pas {@code null})
     * puis les extraits de fichiers (F-268), dans un même bloc, avec des identifiants de même nature.
     *
     * @param report relevé JSON du runner ({@code proof_run}), {@code null} s'il n'y a pas de commande
     * @param files  fichiers lus sur le poste (vide s'il n'y en a pas)
     */
    public Result capture(UUID userId, UUID workspaceId, String callId, String title, String step,
            String report, List<FileSource> files) {
        String cleanTitle = clip(title == null || title.isBlank() ? "Preuves" : title.strip(), MAX_TITLE);
        String cleanStep = step == null || step.isBlank() ? null : clip(step.strip(), MAX_STEP);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<ProofBlock.Capture> captures = new ArrayList<>();
        StringBuilder text = new StringBuilder();

        // ---- la session de commandes (F-212)
        ObjectNode session = null;
        List<ProofBlock.Command> commands = List.of();
        String host = "";
        String os = "";
        String shell = "";
        List<ProofBlock.Capture> terminalCaptures = new ArrayList<>();
        String renderNote = null;
        if (report != null) {
            JsonNode raw;
            try {
                raw = mapper.readTree(report);
            } catch (JsonProcessingException e) {
                raw = null;
            }
            if (raw == null || !raw.path("commands").isArray() || raw.path("commands").isEmpty()) {
                text.append("Le poste n'a rendu aucun relevé exploitable : aucune capture de commande.\n");
            } else {
                session = masked(raw);
                commands = commandsOf(session);
                String commandsJson;
                try {
                    commandsJson = mapper.writeValueAsString(commands);
                } catch (JsonProcessingException e) {
                    commandsJson = "[]";
                }
                List<TerminalShotRenderer.Shot> shots;
                try {
                    shots = renderer.shoot(session);
                } catch (DiagramRejectedException | DiagramRendererUnavailableException e) {
                    shots = List.of();
                    renderNote = "Les images n'ont pas pu être produites (" + e.getMessage()
                            + ") ; les sorties ci-dessous sont celles du poste.";
                }
                host = text(session, "host");
                os = text(session, "os");
                shell = text(session, "execShell").isBlank() ? text(session, "shell") : text(session, "execShell");
                for (TerminalShotRenderer.Shot shot : shots) {
                    terminalCaptures.add(store(userId, workspaceId, callId, cleanTitle, cleanStep, captures.size(),
                            host, os, shell, shot, commandsJson, now));
                    captures.add(terminalCaptures.get(terminalCaptures.size() - 1));
                }
            }
        }

        // ---- les fichiers (F-268)
        List<ProofBlock.FileShot> fileShots = new ArrayList<>();
        String fileText = files == null || files.isEmpty() ? ""
                : captureFiles(userId, workspaceId, callId, cleanTitle, cleanStep, files, captures, fileShots, now);

        ProofBlock block = captures.isEmpty() ? null
                : new ProofBlock(cleanTitle, cleanStep, host, os, shell, List.copyOf(captures), commands,
                        fileShots.isEmpty() ? null : List.copyOf(fileShots));
        if (session != null) {
            text.append(modelText(cleanTitle, terminalCaptures, session, renderNote)).append('\n');
        }
        text.append(fileText);
        if (!captures.isEmpty() && (session == null ? 0 : 1) + (fileShots.isEmpty() ? 0 : 1) > 1) {
            text.append("Toutes les captures de cet appel : preuves=")
                    .append(String.join(",", captures.stream().map(ProofBlock.Capture::id).toList())).append('\n');
        }
        return new Result(block, text.toString().strip());
    }

    /** Range une image et sa ligne {@code proof_captures} ; rend sa référence pour le bloc. */
    private ProofBlock.Capture store(UUID userId, UUID workspaceId, String callId, String title, String step,
            int position, String host, String os, String shell, TerminalShotRenderer.Shot shot, String detailsJson,
            OffsetDateTime now) {
        UUID id = UUID.randomUUID();
        String key = prefixOf(userId, workspaceId) + hex(id) + ".png";
        storage.putFile(key, shot.png(), "image/png");
        repository.save(ProofCapture.builder()
                .id(id).userId(userId).workspaceId(workspaceId).callId(clip(callId, 128))
                .title(title).step(step).position(position)
                .host(clip(host, 256)).os(clip(os, 16)).shell(clip(shell, 32))
                .width(shot.width()).height(shot.height())
                .commandsJson(clip(detailsJson, 200_000))
                .storageKey(key).capturedAt(now)
                .build());
        return new ProofBlock.Capture(hex(id), shot.width(), shot.height());
    }

    // ------------------------------------------------------------------ fichiers (F-268)

    /** Lignes montrées par image (borne du rendu). */
    static final int MAX_FILE_LINES = 80;
    /** Fenêtre par défaut sans plage ni surlignage. */
    static final int DEFAULT_FILE_LINES = 40;
    /** Contexte autour des lignes surlignées. */
    static final int HIGHLIGHT_CONTEXT = 6;
    /** Fichiers par appel. */
    public static final int MAX_FILES = 4;
    private static final int MAX_NOTE = 200;
    private static final int MODEL_LINE_CHARS = 200;

    /** Fenêtre montrée d'un fichier : [first, last] (1-based, inclusifs). */
    record Window(int first, int last) {
    }

    /**
     * La fenêtre montrée : la plage demandée (bornée à {@value #MAX_FILE_LINES} lignes), sinon les lignes
     * surlignées et {@value #HIGHLIGHT_CONTEXT} lignes de contexte, sinon le début du fichier.
     */
    static Window windowOf(int total, Integer from, Integer to, List<Integer> highlights) {
        int count = Math.max(1, total);
        List<Integer> hl = highlights == null ? List.of()
                : highlights.stream().filter(n -> n != null && n >= 1 && n <= count).sorted().toList();
        int first;
        int last;
        if (from != null || to != null) {
            first = Math.max(1, Math.min(count, from == null ? 1 : from));
            last = Math.max(first, Math.min(count, to == null ? first + MAX_FILE_LINES - 1 : to));
        } else if (!hl.isEmpty()) {
            first = Math.max(1, hl.get(0) - HIGHLIGHT_CONTEXT);
            last = Math.min(count, hl.get(hl.size() - 1) + HIGHLIGHT_CONTEXT);
        } else {
            first = 1;
            last = Math.min(count, DEFAULT_FILE_LINES);
        }
        if (last - first + 1 > MAX_FILE_LINES) {
            if (from == null && !hl.isEmpty()) {
                first = Math.max(1, hl.get(0) - 3);
            }
            last = first + MAX_FILE_LINES - 1;
        }
        return new Window(first, Math.min(count, last));
    }

    /** Secrets d'un fichier masqués LIGNE À LIGNE (les numéros de ligne restent justes). */
    List<String> maskedLines(String content) {
        String normalized = content.replace("\r\n", "\n");
        if (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String[] raw = normalized.split("\n", -1);
        List<String> lines = new ArrayList<>(raw.length);
        boolean inKey = false;
        for (String line : raw) {
            if (line.contains("-----BEGIN") && line.contains("PRIVATE KEY-----")) {
                inKey = !line.contains("-----END");
                lines.add(McpSecretFilter.MASK);
                continue;
            }
            if (inKey) {
                inKey = !(line.contains("-----END") && line.contains("PRIVATE KEY-----"));
                lines.add(McpSecretFilter.MASK);
                continue;
            }
            lines.add(maskAssignment(secrets.maskString(line)));
        }
        return lines;
    }

    /** Clé sensible (mot de passe, secret, jeton, clé d'accès…) à laquelle on affecte une valeur. */
    private static final java.util.regex.Pattern SECRET_KEY = java.util.regex.Pattern.compile(
            "(?i)([\\w.-]*(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key"
                    + "|credentials?)[\\w.-]*[\"']?\\s*[=:]\\s*)(\"[^\"]{4,}\"|'[^']{4,}'|[^\\s\"'#{$][^\\s#]{3,})");

    /**
     * Valeur littérale d'une clé sensible masquée ({@code password = "x"}, {@code TOKEN=abc}) ; une
     * référence ({@code var.db_password}, {@code ${...}}) reste visible : c'est elle qui prouve.
     */
    static String maskAssignment(String line) {
        java.util.regex.Matcher m = SECRET_KEY.matcher(line);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = m.group(2);
            boolean quoted = value.startsWith("\"") || value.startsWith("'");
            String inner = quoted ? value.substring(1, value.length() - 1) : value;
            boolean reference = inner.startsWith("${") || inner.matches("(?:var|local|module|data|each|self)\\..*")
                    || McpSecretFilter.MASK.equals(inner);
            String replacement = reference ? m.group(0)
                    : m.group(1) + (quoted ? value.charAt(0) + McpSecretFilter.MASK + value.charAt(0) : McpSecretFilter.MASK);
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String captureFiles(UUID userId, UUID workspaceId, String callId, String title, String step,
            List<FileSource> files, List<ProofBlock.Capture> captures, List<ProofBlock.FileShot> fileShots,
            OffsetDateTime now) {
        StringBuilder text = new StringBuilder();
        ArrayNode requests = mapper.createArrayNode();
        List<FileSource> rendered = new ArrayList<>();
        List<Window> windows = new ArrayList<>();
        List<List<String>> shown = new ArrayList<>();
        List<Integer> totals = new ArrayList<>();
        List<List<Integer>> highlightsShown = new ArrayList<>();
        for (FileSource file : files.subList(0, Math.min(files.size(), MAX_FILES))) {
            if (file.error() != null || file.content() == null) {
                text.append("Fichier « ").append(file.path()).append(" » non lu : ")
                        .append(file.error() == null ? "lecture impossible" : file.error()).append('\n');
                continue;
            }
            List<String> lines = maskedLines(file.content());
            Window window = windowOf(lines.size(), file.from(), file.to(), file.highlights());
            List<String> slice = lines.subList(window.first() - 1, window.last());
            List<Integer> hl = file.highlights() == null ? List.of() : file.highlights().stream()
                    .filter(n -> n != null && n >= window.first() && n <= window.last()).distinct().sorted().toList();
            ObjectNode request = mapper.createObjectNode();
            request.put("path", file.path());
            request.put("firstLine", window.first());
            ArrayNode linesNode = request.putArray("lines");
            slice.forEach(linesNode::add);
            ArrayNode hlNode = request.putArray("highlights");
            hl.forEach(hlNode::add);
            if (file.note() != null && !file.note().isBlank()) {
                request.put("note", clip(secrets.maskString(file.note().strip()), MAX_NOTE));
            }
            request.put("totalLines", lines.size());
            requests.add(request);
            rendered.add(file);
            windows.add(window);
            shown.add(slice);
            totals.add(lines.size());
            highlightsShown.add(hl);
        }
        if (rendered.isEmpty()) {
            return text.toString();
        }
        List<TerminalShotRenderer.Shot> shots;
        try {
            shots = renderer.shootFiles(mapper.createObjectNode().set("files", requests));
        } catch (DiagramRejectedException | DiagramRendererUnavailableException e) {
            return text.append("Les images des fichiers n'ont pas pu être produites (").append(e.getMessage())
                    .append(").\n").toString();
        }
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < Math.min(shots.size(), rendered.size()); i++) {
            FileSource file = rendered.get(i);
            Window window = windows.get(i);
            ObjectNode details = mapper.createObjectNode();
            details.put("file", file.path());
            details.put("firstLine", window.first());
            details.put("lastLine", window.last());
            ProofBlock.Capture capture = store(userId, workspaceId, callId, title, step, captures.size(), "", "",
                    "", shots.get(i), details.toString(), now);
            captures.add(capture);
            ids.add(capture.id());
            fileShots.add(new ProofBlock.FileShot(capture.id(), clip(file.path(), 512), window.first(), window.last(),
                    totals.get(i), highlightsShown.get(i)));
            text.append("Capture du fichier « ").append(file.path()).append(" », lignes ").append(window.first())
                    .append("–").append(window.last()).append(" sur ").append(totals.get(i))
                    .append(file.truncated() ? " (lecture tronquée par le poste)" : "")
                    .append(" — identifiant ").append(capture.id()).append(".\n");
            List<Integer> hl = highlightsShown.get(i);
            if (hl.isEmpty()) {
                text.append("  Aucune ligne surlignée.\n");
            }
            // Ce qui est surligné, pour que l'agent vérifie qu'il montre la bonne ligne (5 au plus).
            for (Integer n : hl.subList(0, Math.min(5, hl.size()))) {
                text.append("  surlignée ").append(n).append(" : ")
                        .append(clip(shown.get(i).get(n - window.first()).strip(), MODEL_LINE_CHARS)).append('\n');
            }
        }
        if (!ids.isEmpty()) {
            text.append("Pour joindre ces fichiers à un message : preuves=").append(String.join(",", ids)).append('\n');
        }
        return text.toString();
    }

    /** L'image d'une capture de ce compte et de ce terminal, ou vide. */
    public Optional<byte[]> image(UUID userId, UUID workspaceId, String captureId) {
        UUID id = parseId(captureId);
        if (id == null) {
            return Optional.empty();
        }
        return repository.findByIdAndUserIdAndWorkspaceId(id, userId, workspaceId)
                // La clé est reconstruite : jamais lue d'ailleurs que du compte authentifié.
                .flatMap(capture -> storage.getFile(prefixOf(userId, workspaceId) + hex(id) + ".png"));
    }

    /** Racine des images d'un terminal — effacée avec lui ({@code WorkspaceService.delete}). */
    public static String prefixOf(UUID userId, UUID workspaceId) {
        return PREFIX + userId + "/" + workspaceId + "/";
    }

    // ------------------------------------------------------------------ texte au modèle

    /**
     * Le texte rendu au modèle : identifiants, puis pour chaque commande son code et sa sortie bornée
     * (tête + fin). Jamais d'image.
     */
    String modelText(String title, List<ProofBlock.Capture> captures, ObjectNode session, String renderNote) {
        StringBuilder text = new StringBuilder();
        if (captures.isEmpty()) {
            text.append("Aucune capture n'a pu être rangée pour « ").append(title).append(" ».\n");
        } else {
            List<String> ids = captures.stream().map(ProofBlock.Capture::id).toList();
            text.append("Captures « ").append(title).append(" » affichées dans le fil (")
                    .append(captures.size()).append(captures.size() > 1 ? " images" : " image")
                    .append(") — identifiants : ").append(String.join(", ", ids))
                    .append(". Pour les joindre à un message : preuves=").append(String.join(",", ids))
                    .append(".\n");
        }
        if (renderNote != null) {
            text.append(renderNote).append('\n');
        }
        JsonNode commands = session.path("commands");
        int count = commands.size();
        int perCommand = Math.min(MODEL_CHARS_PER_COMMAND, Math.max(200, MODEL_CHARS_TOTAL / Math.max(1, count)));
        int index = 0;
        for (JsonNode command : commands) {
            index++;
            String status = command.path("status").asText("done");
            text.append('[').append(index).append("] $ ").append(clip(command.path("command").asText(""), 300));
            switch (status) {
                case "not_run" -> {
                    text.append(" — non exécutée (la session s'est arrêtée avant)\n");
                    continue;
                }
                case "interrupted" -> text.append(" — interrompue (le shell s'est arrêté), code ")
                        .append(command.path("exitCode").asText("?"));
                default -> text.append(" — code ").append(command.path("exitCode").asText("?"));
            }
            if (command.hasNonNull("durationMs")) {
                text.append(", ").append(String.format(Locale.FRANCE, "%.1f s",
                        command.path("durationMs").asLong() / 1000d));
            }
            text.append('\n');
            String output = command.path("stdout").asText("");
            String err = command.path("stderr").asText("");
            if (!err.isEmpty()) {
                output = output.isEmpty() ? err : output + (output.endsWith("\n") ? "" : "\n") + err;
            }
            if (!output.isBlank()) {
                text.append(headAndTail(output.strip(), perCommand)).append('\n');
            }
        }
        return text.toString().strip();
    }

    static String headAndTail(String output, int max) {
        if (output.length() <= max) {
            return output;
        }
        int head = max * 2 / 3;
        int tail = max - head;
        return output.substring(0, head) + "\n… [" + (output.length() - max) + " caractères omis] …\n"
                + output.substring(output.length() - tail);
    }

    // ------------------------------------------------------------------ outils

    /** Copie du relevé, secrets masqués dans les commandes et les sorties (avant tout rendu). */
    private ObjectNode masked(JsonNode raw) {
        ObjectNode copy = raw.deepCopy();
        ArrayNode commands = (ArrayNode) copy.path("commands");
        ArrayNode kept = mapper.createArrayNode();
        int n = 0;
        for (JsonNode node : commands) {
            if (!node.isObject() || n++ >= ProofToolCatalog.MAX_COMMANDS) {
                continue;
            }
            ObjectNode command = (ObjectNode) node;
            for (String field : List.of("command", "stdout", "stderr")) {
                if (command.hasNonNull(field)) {
                    command.put(field, secrets.maskString(command.path(field).asText("")));
                }
            }
            kept.add(command);
        }
        copy.set("commands", kept);
        return copy;
    }

    private static List<ProofBlock.Command> commandsOf(JsonNode session) {
        List<ProofBlock.Command> commands = new ArrayList<>();
        for (JsonNode command : session.path("commands")) {
            commands.add(new ProofBlock.Command(
                    clip(command.path("command").asText(""), 2_000),
                    command.path("status").asText("done"),
                    command.hasNonNull("exitCode") ? command.path("exitCode").asInt() : null,
                    command.hasNonNull("durationMs") ? command.path("durationMs").asLong() : null,
                    command.hasNonNull("startedAt") ? command.path("startedAt").asText() : null,
                    command.path("truncated").asBoolean(false)));
        }
        return List.copyOf(commands);
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("");
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static String hex(UUID id) {
        return id.toString().replace("-", "");
    }

    /** Identifiant d'une capture : 32 caractères hexadécimaux (UUID sans tirets) — jamais un chemin. */
    static UUID parseId(String captureId) {
        if (captureId == null || !captureId.matches("[0-9a-fA-F]{32}")) {
            return null;
        }
        String h = captureId.toLowerCase(Locale.ROOT);
        return UUID.fromString(h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) + "-"
                + h.substring(16, 20) + "-" + h.substring(20));
    }
}
