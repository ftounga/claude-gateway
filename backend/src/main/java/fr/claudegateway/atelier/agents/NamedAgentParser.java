package fr.claudegateway.atelier.agents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lit et <b>valide</b> un fichier {@code .claude/agents/<nom>.md} (F-182 / SF-182-01, décisions D1 et D3).
 *
 * <p>Format : une entête entre deux lignes {@code ---} puis la consigne de l'agent. Clés françaises
 * ({@code description}, {@code outils}, {@code modele}, {@code justification}) et, pour la parité avec
 * Claude Code, leurs équivalents anglais ({@code tools}, {@code model}) ; les noms d'outils de Claude Code
 * ({@code Read}, {@code Edit}, {@code Bash}…) sont traduits vers les nôtres.</p>
 *
 * <p><b>Le moindre privilège par défaut</b> : sans {@code outils}, l'agent est en lecture. <b>Justesse avant
 * coût</b> (D3) : un modèle n'est retenu que s'il est écrit <i>et</i> justifié ; sinon l'agent tourne sur
 * le modèle de la Forge. Rien ici ne lève : un fichier mal écrit donne un agent avec ses erreurs en clair.</p>
 */
public final class NamedAgentParser {

    /** Borne d'une consigne d'agent : au-delà, elle est tronquée (avertissement). */
    public static final int MAX_INSTRUCTIONS_CHARS = 20_000;
    /** Borne d'une description affichée ou annoncée. */
    public static final int DESCRIPTION_CHARS = 160;
    static final int MAX_JUSTIFICATION_CHARS = 300;

    /** Les outils de lecture, dans l'ordre où on les rend. */
    public static final List<String> READ_TOOLS = List.of("read_file", "list_files", "search_files", "grep", "glob");
    /** Les outils qui écrivent ou exécutent. */
    public static final List<String> WRITE_TOOLS = List.of("write_file", "edit_file", "multi_edit", "bash");

    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{1,99}");
    private static final Set<String> DEFAULT_MODEL_WORDS = Set.of("inherit", "defaut", "défaut", "default",
            "forge", "opus");
    private static final Set<String> READ_WORDS = Set.of("lecture", "lecture-seule", "lecture seule",
            "read-only", "readonly");
    private static final Set<String> WRITE_WORDS = Set.of("ecriture", "écriture", "tous", "all", "*");

    /** Noms d'outils acceptés (minuscules) → nos noms. */
    private static final Map<String, String> TOOL_ALIASES = buildAliases();

    private NamedAgentParser() {
    }

    private static Map<String, String> buildAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (String tool : READ_TOOLS) {
            aliases.put(tool, tool);
        }
        for (String tool : WRITE_TOOLS) {
            aliases.put(tool, tool);
        }
        aliases.put("read", "read_file");
        aliases.put("ls", "list_files");
        aliases.put("edit", "edit_file");
        aliases.put("multiedit", "multi_edit");
        aliases.put("write", "write_file");
        return Map.copyOf(aliases);
    }

    /**
     * Lit un agent.
     *
     * @param name    son nom (celui du fichier, déjà validé)
     * @param path    son chemin
     * @param origin  {@code SUJET} ou {@code POSTE}
     * @param content le contenu du fichier (peut être {@code null})
     */
    public static NamedAgent parse(String name, String path, String origin, String content) {
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, String> header = new LinkedHashMap<>();
        String body = splitFrontMatter(content == null ? "" : content.replace("\r\n", "\n"), header);

        String instructions = body.strip();
        if (instructions.length() > MAX_INSTRUCTIONS_CHARS) {
            instructions = instructions.substring(0, MAX_INSTRUCTIONS_CHARS);
            warnings.add("Consigne tronquée à " + MAX_INSTRUCTIONS_CHARS + " caractères.");
        }
        if (instructions.isEmpty()) {
            errors.add("Consigne vide : écris sous l'entête ce que l'agent doit faire.");
        }

        String description = first(header, "description");
        if (description == null || description.isBlank()) {
            description = firstTextLine(instructions);
        }
        description = bound(description == null ? "" : description.strip(), DESCRIPTION_CHARS);

        List<String> tools = tools(first(header, "outils", "tools"), warnings);
        if (tools.isEmpty()) {
            errors.add("Aucun outil reconnu dans « outils » : écris lecture, ecriture, ou une liste "
                    + "(read_file, grep, glob, edit_file, write_file, bash…).");
        }
        String access = tools.stream().anyMatch(WRITE_TOOLS::contains) ? NamedAgent.ECRITURE : NamedAgent.LECTURE;

        String justification = first(header, "justification");
        justification = justification == null || justification.isBlank() ? null
                : bound(justification.strip(), MAX_JUSTIFICATION_CHARS);
        String model = model(first(header, "modele", "modèle", "model"), justification, warnings);

        return new NamedAgent(name, path, origin, description, access, tools, model,
                model == null ? null : justification, instructions, warnings, errors);
    }

    /** Les outils retenus, sous nos noms, dans un ordre stable ; sans valeur → lecture (moindre privilège). */
    static List<String> tools(String raw, List<String> warnings) {
        if (raw == null || raw.isBlank()) {
            return READ_TOOLS;
        }
        String value = unquote(raw.strip());
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        String word = value.strip().toLowerCase(Locale.ROOT);
        if (READ_WORDS.contains(word)) {
            return READ_TOOLS;
        }
        if (WRITE_WORDS.contains(word)) {
            List<String> all = new ArrayList<>(READ_TOOLS);
            all.addAll(WRITE_TOOLS);
            return List.copyOf(all);
        }
        Set<String> picked = new LinkedHashSet<>();
        for (String part : value.split("[,\\n]")) {
            String tool = unquote(part.strip().replaceFirst("^-\\s*", "").strip());
            if (tool.isEmpty()) {
                continue;
            }
            String ours = TOOL_ALIASES.get(tool.toLowerCase(Locale.ROOT));
            if (ours == null) {
                warnings.add("Outil inconnu ignoré : « " + bound(tool, 40) + " ».");
            } else {
                picked.add(ours);
            }
        }
        List<String> ordered = new ArrayList<>();
        for (String tool : READ_TOOLS) {
            if (picked.contains(tool)) {
                ordered.add(tool);
            }
        }
        for (String tool : WRITE_TOOLS) {
            if (picked.contains(tool)) {
                ordered.add(tool);
            }
        }
        return List.copyOf(ordered);
    }

    /** D3 : le modèle n'est retenu que s'il est écrit, bien formé ET justifié. */
    static String model(String raw, String justification, List<String> warnings) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String model = unquote(raw.strip());
        if (DEFAULT_MODEL_WORDS.contains(model.toLowerCase(Locale.ROOT))) {
            return null;
        }
        if (!MODEL.matcher(model).matches()) {
            warnings.add("Modèle « " + bound(model, 40) + " » mal formé : ignoré, l'agent tourne sur le modèle "
                    + "de la Forge.");
            return null;
        }
        if (justification == null) {
            warnings.add("Modèle « " + model + " » ignoré faute de « justification » (justesse avant coût) : "
                    + "l'agent tourne sur le modèle de la Forge.");
            return null;
        }
        return model;
    }

    /** Sépare l'entête ({@code ---}…{@code ---}) du corps ; remplit {@code header} (clés en minuscules). */
    static String splitFrontMatter(String content, Map<String, String> header) {
        List<String> lines = content.lines().toList();
        int start = 0;
        while (start < lines.size() && lines.get(start).isBlank()) {
            start++;
        }
        if (start >= lines.size() || !lines.get(start).strip().equals("---")) {
            return content;
        }
        int end = -1;
        for (int i = start + 1; i < lines.size(); i++) {
            if (lines.get(i).strip().equals("---")) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            return content;
        }
        String currentKey = null;
        for (int i = start + 1; i < end; i++) {
            String line = lines.get(i);
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            if (stripped.startsWith("- ") && currentKey != null) {
                // Liste YAML sous la clé précédente.
                String previous = header.getOrDefault(currentKey, "");
                header.put(currentKey, previous.isEmpty() ? stripped.substring(2).strip()
                        : previous + "," + stripped.substring(2).strip());
                continue;
            }
            int colon = stripped.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            currentKey = stripped.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = withoutComment(stripped.substring(colon + 1).strip());
            header.put(currentKey, value);
        }
        StringBuilder body = new StringBuilder();
        for (int i = end + 1; i < lines.size(); i++) {
            body.append(lines.get(i)).append('\n');
        }
        return body.toString();
    }

    private static String withoutComment(String value) {
        if (value.startsWith("\"") || value.startsWith("'")) {
            return value;
        }
        int hash = value.indexOf(" #");
        return hash >= 0 ? value.substring(0, hash).strip() : value;
    }

    private static String first(Map<String, String> header, String... keys) {
        for (String key : keys) {
            String value = header.get(key);
            if (value != null && !value.isBlank()) {
                return unquote(value.strip());
            }
        }
        return null;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1).strip();
        }
        return value;
    }

    private static String firstTextLine(String body) {
        for (String line : body.lines().toList()) {
            String stripped = line.strip();
            if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                return stripped;
            }
        }
        return "";
    }

    private static String bound(String value, int max) {
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }
}
