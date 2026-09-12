package fr.claudegateway.runner.exec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.relay.RunnerCallRouter;

/**
 * Façade métier des outils fichiers exécutés <b>sur la machine de l'utilisateur</b> (F-38 / SF-38-05).
 * C'est le seul point par lequel le domaine (la boucle tool-use de l'Atelier) parle au runner : il
 * ne connaît ni WebSocket, ni trame, ni {@code id} de corrélation — seulement quelques opérations et
 * une issue.
 *
 * <p>Depuis F-48 / SF-48-01, la cible d'un appel est un {@link RunnerTarget} : le <b>poste</b> qui
 * exécute, et le <b>projet</b> qui travaille sous sa racine. Le projet part dans la trame et donne
 * le dossier où le tour démarre — sans le borner (F-73 / SF-73-01).</p>
 *
 * <p>Depuis SF-38-12, elle passe par {@link RunnerCallRouter} et non plus par le dispatcher : c'est
 * le routeur qui sait si la socket du runner vit sur ce pod ou sur un autre. La façade, elle, ne
 * change pas de contrat — un appel rend la même issue, que le runner soit joignable ici ou par un
 * saut interne.
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
    /**
     * Délai des outils Teams (F-87 / SF-87-03) : plus long que celui des fichiers, parce que la
     * sonde <b>observe</b> le réseau du navigateur pendant quelques secondes avant de conclure.
     */
    public static final long TEAMS_TOOL_TIMEOUT_MS = 20_000L;
    /**
     * Délai des outils Teams qui <b>font défiler</b> (F-88 / SF-88-03) : jusqu'à quarante remontées
     * d'écran, chacune suivie d'une attente de stabilisation. Observer est court ; parcourir ne
     * l'est pas.
     */
    public static final long TEAMS_SCROLLING_TIMEOUT_MS = 60_000L;
    /** Plancher : un délai ridicule ferait échouer la commande avant même son démarrage. */
    public static final long MIN_BASH_TIMEOUT_MS = 1_000L;
    /** Longueur maximale d'une ligne de commande acceptée (le runner applique la même borne). */
    public static final int MAX_COMMAND_CHARS = 8_192;
    /** Borne du contenu d'un {@code write_file} (contrat §5) : au-delà on refuse, on ne fragmente pas. */
    public static final int MAX_WRITE_BYTES = 524_288;
    private static final int MAX_PATH_CHARS = 4_096;
    private static final int MAX_QUERY_CHARS = 1_024;

    private final RunnerCallRouter router;
    private final ObjectMapper objectMapper;

    public RunnerToolGateway(RunnerCallRouter router, ObjectMapper objectMapper) {
        this.router = router;
        this.objectMapper = objectMapper;
    }

    /** Liste les fichiers du projet sur la machine (exclusions du runner déjà appliquées, SF-38-10). */
    public RunnerCallResult listFiles(RunnerTarget target, String callId) {
        return router.call(target, callId, "list_files", objectMapper.createObjectNode(),
                FILE_TOOL_TIMEOUT_MS);
    }

    /** Lit un fichier du projet sur la machine. */
    public RunnerCallResult readFile(RunnerTarget target, String callId, String path) {
        String rel = normalizePath(path);
        if (rel == null) {
            return invalid("Chemin de fichier invalide.");
        }
        ObjectNode input = objectMapper.createObjectNode();
        input.put("path", rel);
        return router.call(target, callId, "read_file", input, FILE_TOOL_TIMEOUT_MS);
    }

    /** Écrit un fichier du projet sur la machine. */
    public RunnerCallResult writeFile(RunnerTarget target, String callId, String path, String content) {
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
        return router.call(target, callId, "write_file", input, FILE_TOOL_TIMEOUT_MS);
    }

    /**
     * Recherche une chaîne dans les fichiers du projet, en <b>un seul</b> appel : le runner parcourt
     * lui-même l'arborescence. Une recherche par N lectures ferait traverser le réseau à tout le
     * projet, pour un résultat que la machine calcule sur place.
     */
    public RunnerCallResult searchFiles(RunnerTarget target, String callId, String query) {
        String needle = query == null ? "" : query.strip();
        if (needle.isEmpty() || needle.length() > MAX_QUERY_CHARS) {
            return invalid("Terme de recherche invalide.");
        }
        ObjectNode input = objectMapper.createObjectNode();
        input.put("query", needle);
        return router.call(target, callId, "search_files", input, FILE_TOOL_TIMEOUT_MS);
    }

    /**
     * Demande à la machine l'<b>état de sa liaison Teams</b> (F-87 / SF-87-03).
     *
     * <p>Aucun paramètre : la question est « où en est la liaison sur ce poste ». Le runner rend un
     * objet JSON portant l'état, la phrase à lire et, s'il y a lieu, le remède — la ligne de
     * commande à coller pour lancer le navigateur. Le délai est plus long que celui des outils
     * fichiers : la sonde observe le réseau du navigateur pendant quelques secondes.</p>
     */
    public RunnerCallResult teamsStatus(RunnerTarget target, String callId) {
        return router.call(target, callId, "teams_status", objectMapper.createObjectNode(),
                TEAMS_TOOL_TIMEOUT_MS);
    }

    /**
     * Relaie un outil de <b>lecture</b> Teams (F-88 / SF-88-03).
     *
     * <p>Un seul relais pour les sept, et c'est voulu : ils partagent exactement le même contrat —
     * un objet de paramètres que <b>seul le runner</b> sait interpréter, et une enveloppe JSON en
     * retour. La gateway <b>relaie</b> ; elle ne réinterprète ni la période, ni le plafond, ni la
     * conversation demandée. Dupliquer ici la lecture des paramètres créerait une seconde vérité sur
     * ce que « from » veut dire, et les deux finiraient par diverger.</p>
     *
     * <p>Ce que ce relais fait quand même, parce que rien d'autre ne le ferait : <b>borner les
     * chaînes</b> avant émission — une requête de recherche de dix mille caractères n'a aucune
     * chance d'être une vraie question.</p>
     *
     * @param tool nom de l'outil, préfixé {@code teams_} (vérifié)
     */
    public RunnerCallResult teamsRead(RunnerTarget target, String callId, String tool,
            com.fasterxml.jackson.databind.JsonNode input) {
        if (tool == null || !tool.startsWith("teams_")) {
            return invalid("Outil Teams inconnu.");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        if (input != null && input.isObject()) {
            input.fields().forEachRemaining(field -> {
                if (field.getValue() == null || field.getValue().isNull()) {
                    return;
                }
                if (field.getValue().isTextual()) {
                    String value = field.getValue().asText();
                    if (value.length() > MAX_QUERY_CHARS) {
                        value = value.substring(0, MAX_QUERY_CHARS);
                    }
                    payload.put(field.getKey(), value);
                } else if (field.getValue().isNumber() || field.getValue().isBoolean()) {
                    payload.set(field.getKey(), field.getValue());
                }
                // Tout le reste est écarté : un outil de lecture ne prend que des scalaires, et
                // recopier un objet quelconque ferait traverser le réseau à ce qu'on n'a pas lu.
            });
        }
        return router.call(target, callId, tool, payload, teamsTimeoutFor(tool));
    }

    /**
     * Le délai d'un outil Teams. Lire un fil ou poser une question à l'index demande de
     * <b>faire défiler</b> la page et d'attendre à chaque geste ; observer, non. Allonger le délai
     * de tous ferait mettre une minute à {@code teams_status} pour dire « navigateur non détecté »,
     * ce qui serait une régression de SF-87-03.
     */
    static long teamsTimeoutFor(String tool) {
        return "teams_read_conversation".equals(tool) || "teams_search".equals(tool)
                ? TEAMS_SCROLLING_TIMEOUT_MS : TEAMS_TOOL_TIMEOUT_MS;
    }

    /**
     * Exécute une commande sur la machine de l'utilisateur (F-38 / SF-38-07).
     *
     * <p>Ce que cette méthode fait <b>avant</b> d'émettre quoi que ce soit : borner la commande,
     * ramener un éventuel {@code cwd} à un chemin relatif (le runner revérifie et fait foi, D6), et
     * clamper le délai. Ce qu'elle ne fait pas : décider si la commande a le droit d'être lancée —
     * c'est la machine qui tranche (opt-out {@code --no-bash}, SF-38-19), et ce sera la validation par
     * commande de SF-38-08 côté gateway.</p>
     *
     * @param timeoutMs délai souhaité, clampé dans {@code [1 000 ; 120 000]} ms
     * @param onOutput  relais de la sortie au fil de l'eau, ou {@code null}
     */
    public RunnerCallResult bash(RunnerTarget target, String callId, String command, String cwd,
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
                router.call(target, callId, "bash", input, effective, onOutput);
        return RunnerErrorCodes.UNSUPPORTED_TOOL.equals(result.errorCode())
                ? RunnerCallResult.backendError(RunnerErrorCodes.UNSUPPORTED_TOOL,
                        // Le drapeau nommé ici doit être celui qui AGIT : --allow-bash n'a plus
                        // d'effet depuis SF-38-19, et le conseiller envoyait l'utilisateur relancer
                        // une commande qui n'aurait rien changé (SF-38-26, D4).
                        "L'exécution de commandes n'est pas activée sur ce runner. "
                                + "Redémarre-le sans --no-bash pour l'autoriser.")
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
