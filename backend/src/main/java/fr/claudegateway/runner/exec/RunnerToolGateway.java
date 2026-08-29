package fr.claudegateway.runner.exec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
