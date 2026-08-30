package fr.claudegateway.runner.exec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;

/**
 * Façade métier des outils fichiers exécutés <b>sur la machine de l'utilisateur</b> (F-38 / SF-38-05).
 * C'est le seul point par lequel le domaine (la boucle tool-use de l'Atelier) parle au runner : il
 * ne connaît ni WebSocket, ni trame, ni {@code id} de corrélation — seulement quatre opérations et
 * une issue.
 *
 * <p>Rôle propre de cette classe : appliquer <b>avant émission</b> ce qui n'a aucune raison de
 * traverser le réseau — chemin normalisé en relatif (le runner revérifie et fait foi, D6), contenu
 * d'écriture borné, délais par défaut du contrat (§2.2). Tout le reste est du transport.</p>
 */
@Service
public class RunnerToolGateway {

    /** Délai imposé aux outils fichiers par le contrat de messages §2.2. */
    public static final long FILE_TOOL_TIMEOUT_MS = 30_000L;
    /** Délai imposé à {@code bash} par le contrat de messages §2.2. */
    public static final long BASH_TIMEOUT_MS = 120_000L;
    /** Plancher : un délai ridicule ferait échouer la commande avant même son démarrage. */
    public static final long MIN_BASH_TIMEOUT_MS = 1_000L;
    /** Longueur maximale d'une ligne de commande acceptée (le runner applique la même borne). */
    public static final int MAX_COMMAND_CHARS = 8_192;
    /** Borne du contenu d'un {@code write_file} (contrat §5) : au-delà on refuse, on ne fragmente pas. */
    public static final int MAX_WRITE_BYTES = 524_288;
    private static final int MAX_PATH_CHARS = 4_096;
    private static final int MAX_QUERY_CHARS = 1_024;

    private final RunnerCallDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    public RunnerToolGateway(RunnerCallDispatcher dispatcher, ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    /** Liste les fichiers du projet sur la machine (exclusions du runner déjà appliquées, SF-38-10). */
    public RunnerCallResult listFiles(UUID workspaceId, String callId) {
        return dispatcher.call(workspaceId, callId, "list_files", objectMapper.createObjectNode(),
                FILE_TOOL_TIMEOUT_MS);
    }

    /** Lit un fichier du projet sur la machine. */
    public RunnerCallResult readFile(UUID workspaceId, String callId, String path) {
        String rel = normalizePath(path);
        if (rel == null) {
            return invalid("Chemin de fichier invalide.");
        }
        ObjectNode input = objectMapper.createObjectNode();
        input.put("path", rel);
        return dispatcher.call(workspaceId, callId, "read_file", input, FILE_TOOL_TIMEOUT_MS);
    }

    /** Écrit un fichier du projet sur la machine. */
    public RunnerCallResult writeFile(UUID workspaceId, String callId, String path, String content) {
        String rel = normalizePath(path);
        if (rel == null) {
            return invalid("Chemin de fichier invalide.");
        }
        String text = content == null ? "" : content;
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_WRITE_BYTES) {
            return invalid("Contenu trop volumineux : 512 Kio au plus par écriture.");
        }
        ObjectNode input = objectMapper.createObjectNode();
        input.put("path", rel);
        input.put("content", text);
        return dispatcher.call(workspaceId, callId, "write_file", input, FILE_TOOL_TIMEOUT_MS);
    }

    /**
     * Recherche une chaîne dans les fichiers du projet, en <b>un seul</b> appel : le runner parcourt
     * lui-même l'arborescence. Une recherche par N lectures ferait traverser le réseau à tout le
     * projet, pour un résultat que la machine calcule sur place.
     */
    public RunnerCallResult searchFiles(UUID workspaceId, String callId, String query) {
        String needle = query == null ? "" : query.strip();
        if (needle.isEmpty() || needle.length() > MAX_QUERY_CHARS) {
            return invalid("Terme de recherche invalide.");
        }
        ObjectNode input = objectMapper.createObjectNode();
        input.put("query", needle);
        return dispatcher.call(workspaceId, callId, "search_files", input, FILE_TOOL_TIMEOUT_MS);
    }

    /**
     * Exécute une commande sur la machine de l'utilisateur (F-38 / SF-38-07).
     *
     * <p>Ce que cette méthode fait <b>avant</b> d'émettre quoi que ce soit : borner la commande,
     * ramener un éventuel {@code cwd} à un chemin relatif (le runner revérifie et fait foi, D6), et
     * clamper le délai. Ce qu'elle ne fait pas : décider si la commande a le droit d'être lancée —
     * c'est la machine qui tranche (opt-in {@code --allow-bash}), et ce sera la validation par
     * commande de SF-38-08 côté gateway.</p>
     *
     * @param timeoutMs délai souhaité, clampé dans {@code [1 000 ; 120 000]} ms
     * @param onOutput  relais de la sortie au fil de l'eau, ou {@code null}
     */
    public RunnerCallResult bash(UUID workspaceId, String callId, String command, String cwd,
            long timeoutMs, Consumer<String> onOutput) {
        String cmd = command == null ? "" : command.strip();
        if (cmd.isEmpty() || cmd.length() > MAX_COMMAND_CHARS || cmd.indexOf('\0') >= 0) {
            return invalid("Commande invalide ou trop longue.");
        }
        ObjectNode input = objectMapper.createObjectNode();
        input.put("command", cmd);
        if (cwd != null && !cwd.isBlank()) {
            String rel = normalizePath(cwd);
            if (rel == null) {
                return invalid("Répertoire de travail invalide.");
            }
            input.put("cwd", rel);
        }
        long effective = Math.max(MIN_BASH_TIMEOUT_MS, Math.min(BASH_TIMEOUT_MS, timeoutMs));
        RunnerCallResult result =
                dispatcher.call(workspaceId, callId, "bash", input, effective, onOutput);
        return RunnerErrorCodes.UNSUPPORTED_TOOL.equals(result.errorCode())
                ? RunnerCallResult.backendError(RunnerErrorCodes.UNSUPPORTED_TOOL,
                        "L'exécution de commandes n'est pas activée sur ce runner. "
                                + "Redémarre-le avec --allow-bash pour l'autoriser.")
                : result;
    }

    private static RunnerCallResult invalid(String message) {
        return RunnerCallResult.backendError(RunnerErrorCodes.INVALID_INPUT, message);
    }

    /**
     * Chemin relatif sûr, ou {@code null} s'il est inexploitable. Mêmes règles que le stockage objet :
     * séparateur {@code /}, ni chemin absolu, ni {@code ..}, ni segment vide. Le runner refera cette
     * vérification (résolution canonique, liens symboliques compris) et c'est la sienne qui fait foi.
     */
    static String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path.replace('\\', '/').trim();
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.length() > MAX_PATH_CHARS
                || normalized.indexOf('\0') >= 0) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                return null;
            }
            parts.add(segment);
        }
        return parts.isEmpty() ? null : String.join("/", parts);
    }
}
