package fr.claudegateway.atelier.scene;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * F-234 / SF-234-04 (D17) — <b>la garde du registre des scènes</b> : tout outil offert à l'agent (chaque
 * {@code new AgentTool(…)} du code de production) figure dans {@code jarvis-scene-registry.json} — par son nom exact
 * ou par un préfixe de famille. Un outil ajouté sans entrée fait échouer ce test : déclarer sa scène, ou accepter
 * explicitement {@code "scene": "GENERIQUE"} avec un libellé lisible (ligne de {@code review-checklist.md}).
 *
 * <p>Lecture du <b>source</b> plutôt que des catalogues : les outils sont assemblés tour par tour selon le sujet, le
 * poste et les droits — seul le source les voit tous.</p>
 */
class JarvisSceneRegistryGuardTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path REGISTRY = Path.of("../frontend/src/app/jarvis/scenes/jarvis-scene-registry.json");
    private static final Pattern TOOL = Pattern.compile("new AgentTool\\(\\s*(?:\"([a-z_0-9]+)\"|([A-Za-z_.0-9]+))");
    private static final Pattern CONSTANT =
            Pattern.compile("static\\s+final\\s+String\\s+([A-Z_0-9]+)\\s*=\\s*(?:\"([^\"]*)\"|([A-Za-z]+)\\.([A-Z_0-9]+)\\.tool\\(\\))");

    @Test
    @DisplayName("chaque outil offert à l'agent a sa scène au registre (nom exact ou famille)")
    void everyAgentToolIsInTheRegistry() throws IOException {
        JsonNode registry = new ObjectMapper().readTree(REGISTRY.toFile());
        TreeSet<String> missing = new TreeSet<>();
        for (String tool : agentTools()) {
            if (!registry.path("tools").has(tool) && !hasPrefix(registry.path("prefixes"), tool)) {
                missing.add(tool);
            }
        }
        assertThat(missing)
                .as("Outils sans scène Jarvis : déclarer chacun dans " + REGISTRY
                        + " (scène dédiée, ou \"scene\": \"GENERIQUE\" avec un libellé lisible) — F-234, D17")
                .isEmpty();
    }

    @Test
    @DisplayName("la lecture du source trouve bien les outils (la garde ne passe pas à vide)")
    void scanFindsTools() throws IOException {
        List<String> tools = agentTools();
        assertThat(tools).contains("bash", "read_file", "update_plan_step", "record_blocker", "ouvrir_sujet",
                "build_document", "build_spreadsheet", "teams_read_conversation", "veille_poser");
        assertThat(tools).noneMatch(t -> t.startsWith("??"));
    }

    @Test
    @DisplayName("chaque entrée du registre a une scène et un libellé")
    void entriesAreComplete() throws IOException {
        JsonNode registry = new ObjectMapper().readTree(REGISTRY.toFile());
        for (String section : List.of("tools", "prefixes", "steps")) {
            registry.path(section).fields().forEachRemaining(e -> {
                assertThat(e.getValue().path("scene").asText()).as(e.getKey()).isNotBlank();
                assertThat(e.getValue().path("libelle").asText()).as(e.getKey()).isNotBlank();
            });
        }
    }

    private static boolean hasPrefix(JsonNode prefixes, String tool) {
        var names = prefixes.fieldNames();
        while (names.hasNext()) {
            if (tool.startsWith(names.next())) {
                return true;
            }
        }
        return false;
    }

    /** Les noms de tous les outils déclarés dans le code de production ({@code ??X} si une constante est illisible). */
    static List<String> agentTools() throws IOException {
        Map<Path, String> sources = new HashMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                sources.put(f, Files.readString(f));
            }
        }
        Map<String, Map<Path, String>> constants = new HashMap<>();
        for (Map.Entry<Path, String> source : sources.entrySet()) {
            Matcher m = CONSTANT.matcher(source.getValue());
            while (m.find()) {
                String value = m.group(2) != null ? m.group(2) : enumTool(sources, m.group(3), m.group(4));
                constants.computeIfAbsent(m.group(1), k -> new HashMap<>()).put(source.getKey(), value);
            }
        }
        List<String> tools = new ArrayList<>();
        for (Map.Entry<Path, String> source : sources.entrySet()) {
            Matcher m = TOOL.matcher(source.getValue());
            while (m.find()) {
                if (m.group(1) != null) {
                    tools.add(m.group(1));
                    continue;
                }
                String[] parts = m.group(2).split("\\.");
                String name = parts[parts.length - 1];
                Map<Path, String> candidates = constants.getOrDefault(name, Map.of());
                String value = candidates.get(source.getKey());
                if (value == null) {
                    List<String> sameDir = candidates.entrySet().stream()
                            .filter(e -> e.getKey().getParent().equals(source.getKey().getParent()))
                            .map(Map.Entry::getValue).toList();
                    value = sameDir.size() == 1 ? sameDir.get(0)
                            : candidates.size() == 1 ? candidates.values().iterator().next() : null;
                }
                tools.add(value == null ? "??" + name + "@" + source.getKey().getFileName() : value);
            }
        }
        return tools;
    }

    /** {@code OfficeFormat.DOCX.tool()} : le premier argument de la constante d'énumération. */
    private static String enumTool(Map<Path, String> sources, String enumName, String constant) {
        Pattern p = Pattern.compile("\\b" + constant + "\\(\\s*\"([a-z_0-9]+)\"");
        return sources.entrySet().stream()
                .filter(e -> e.getKey().getFileName().toString().equals(enumName + ".java"))
                .map(e -> p.matcher(e.getValue()))
                .filter(Matcher::find)
                .map(m -> m.group(1))
                .findFirst().orElse(null);
    }
}
