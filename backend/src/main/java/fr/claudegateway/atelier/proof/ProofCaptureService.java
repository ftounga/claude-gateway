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
     * Range les captures d'une session.
     *
     * @param report relevé JSON du runner ({@code proof_run})
     */
    public Result capture(UUID userId, UUID workspaceId, String callId, String title, String step,
            String report) {
        JsonNode raw;
        try {
            raw = mapper.readTree(report == null ? "" : report);
        } catch (JsonProcessingException e) {
            raw = null;
        }
        if (raw == null || !raw.path("commands").isArray() || raw.path("commands").isEmpty()) {
            return new Result(null, "Le poste n'a rendu aucun relevé exploitable : aucune capture.");
        }
        ObjectNode session = masked(raw);
        String cleanTitle = clip(title == null || title.isBlank() ? "Preuves" : title.strip(), MAX_TITLE);
        String cleanStep = step == null || step.isBlank() ? null : clip(step.strip(), MAX_STEP);

        List<ProofBlock.Command> commands = commandsOf(session);
        String commandsJson;
        try {
            commandsJson = mapper.writeValueAsString(commands);
        } catch (JsonProcessingException e) {
            commandsJson = "[]";
        }
        List<TerminalShotRenderer.Shot> shots;
        String renderNote = null;
        try {
            shots = renderer.shoot(session);
        } catch (DiagramRejectedException | DiagramRendererUnavailableException e) {
            shots = List.of();
            renderNote = "Les images n'ont pas pu être produites (" + e.getMessage()
                    + ") ; les sorties ci-dessous sont celles du poste.";
        }
        String host = text(session, "host");
        String os = text(session, "os");
        String shell = text(session, "execShell").isBlank() ? text(session, "shell") : text(session, "execShell");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<ProofBlock.Capture> captures = new ArrayList<>();
        int position = 0;
        for (TerminalShotRenderer.Shot shot : shots) {
            UUID id = UUID.randomUUID();
            String key = prefixOf(userId, workspaceId) + hex(id) + ".png";
            storage.putFile(key, shot.png(), "image/png");
            repository.save(ProofCapture.builder()
                    .id(id).userId(userId).workspaceId(workspaceId).callId(clip(callId, 128))
                    .title(cleanTitle).step(cleanStep).position(position++)
                    .host(clip(host, 256)).os(clip(os, 16)).shell(clip(shell, 32))
                    .width(shot.width()).height(shot.height())
                    .commandsJson(clip(commandsJson, 200_000))
                    .storageKey(key).capturedAt(now)
                    .build());
            captures.add(new ProofBlock.Capture(hex(id), shot.width(), shot.height()));
        }
        ProofBlock block = captures.isEmpty() ? null
                : new ProofBlock(cleanTitle, cleanStep, host, os, shell, List.copyOf(captures), commands);
        return new Result(block, modelText(cleanTitle, captures, session, renderNote));
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
