package fr.claudegateway.runner.teams;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>Le rapport du relevé réel</b> (F-100 / SF-100-00) : la table des chemins, les écarts avec
 * l'adaptateur, et ce qui n'a été vu qu'en dehors de l'onglet Teams.
 *
 * <p>Il ne contient que ce que {@link NetworkSurvey} a le droit de retenir : motifs d'hôte, chemins
 * gabarisés, origines, types, statuts, classification. Il est écrit <b>sur la machine</b>, et c'est le
 * PO qui décide de le transmettre.</p>
 */
final class SurveyReport {

    static final List<String> STEPS = List.of("avant les étapes", "1 · un fil", "2 · une réunion passée",
            "3 · son récapitulatif", "4 · sa transcription");

    private final NetworkSurvey.Snapshot snapshot;
    private final Instant startedAt;
    private final Instant endedAt;
    private final boolean interrupted;
    private final String browser;
    private final String adapterVersion;

    SurveyReport(NetworkSurvey.Snapshot snapshot, Instant startedAt, Instant endedAt, boolean interrupted,
            String browser, String adapterVersion) {
        this.snapshot = snapshot;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.interrupted = interrupted;
        this.browser = browser == null ? "" : browser;
        this.adapterVersion = adapterVersion == null ? "" : adapterVersion;
    }

    /** Les chemins que l'adaptateur ne reconnaît pas : les écarts à corriger après le relevé. */
    List<NetworkSurvey.Entry> gaps() {
        return snapshot.entries().stream()
                .filter(entry -> TeamsPayloadKind.UNKNOWN.name().equals(entry.classification()))
                .toList();
    }

    /** Les chemins SharePoint et OneDrive observés (F-108 / SF-108-03), sans requête ni corps. */
    List<NetworkSurvey.Entry> filePaths() {
        return snapshot.entries().stream()
                .filter(entry -> entry.host().endsWith("sharepoint.com")
                        || "onedrive.live.com".equals(entry.host()))
                .toList();
    }

    /** Les chemins vus seulement hors de l'onglet Teams : les trois angles morts du cadrage. */
    List<NetworkSurvey.Entry> outsideTeamsTab() {
        return snapshot.entries().stream().filter(NetworkSurvey.Entry::onlyOutsideTeamsTab).toList();
    }

    String markdown() {
        StringBuilder md = new StringBuilder();
        md.append("# Relevé réel — Teams web (F-100 / SF-100-00)\n\n");
        md.append("- Début : ").append(startedAt).append('\n');
        md.append("- Fin : ").append(endedAt).append(interrupted ? " — **interrompu** (liaison perdue)" : "")
                .append('\n');
        md.append("- Navigateur : ").append(browser.isEmpty() ? "non déclaré" : browser).append('\n');
        md.append("- Adaptateur : ").append(adapterVersion).append('\n');
        md.append("- Chemins distincts : ").append(snapshot.entries().size()).append('\n');
        md.append("- Réponses hors domaines Microsoft (non détaillées) : ").append(snapshot.outsideMicrosoft())
                .append('\n');
        md.append("- Ressources statiques écartées : ").append(snapshot.staticResources()).append('\n');
        md.append("- Cibles attachées hors domaines Microsoft (jamais écoutées) : ")
                .append(snapshot.refusedTargets()).append('\n');
        if (snapshot.dropped() > 0) {
            md.append("- Réponses de chemins non détaillés (plafond de ").append(NetworkSurvey.MAX_ENTRIES)
                    .append(" chemins) : ").append(snapshot.dropped()).append('\n');
        }
        md.append("\n> Ce rapport ne contient ni corps de réponse, ni chaîne de requête, ni en-tête, ni nom de "
                + "tenant : les identifiants et les noms propres au client sont remplacés par `{id}`.\n\n");

        if (snapshot.entries().isEmpty()) {
            md.append("**Rien observé.** Teams était-il actif pendant le relevé ? Relancez et suivez les "
                    + "étapes : ouvrir un fil, une réunion passée, son récapitulatif, sa transcription.\n");
            return md.toString();
        }

        md.append("## Écarts avec l'adaptateur\n\n");
        List<NetworkSurvey.Entry> gaps = gaps();
        if (gaps.isEmpty()) {
            md.append("Aucun : chaque chemin relevé est classé par l'adaptateur.\n\n");
        } else {
            md.append("Chemins relevés que l'adaptateur classe `UNKNOWN` (leurs corps ne sont pas lus "
                    + "aujourd'hui).\n\n");
            table(md, gaps);
        }

        md.append("## Vu seulement hors de l'onglet Teams\n\n");
        List<NetworkSurvey.Entry> outside = outsideTeamsTab();
        if (outside.isEmpty()) {
            md.append("Aucun : tout ce qui a été vu l'a été depuis l'onglet Teams.\n\n");
        } else {
            md.append("Ces chemins n'apparaissent que dans un autre onglet, un cadre intégré ou un worker : "
                    + "l'observation limitée à l'onglet ne les voit pas.\n\n");
            table(md, outside);
        }

        md.append("## Fichiers SharePoint et OneDrive (F-108)\n\n");
        List<NetworkSurvey.Entry> files = filePaths();
        if (files.isEmpty()) {
            md.append("Aucun chemin SharePoint ou OneDrive observé pendant ce relevé.\n\n");
        } else {
            md.append("Les adaptateurs fichiers sont écrits sur la documentation publique de l'API REST "
                    + "SharePoint (forme éprouvée sur documentation, à confirmer sur poste réel). Les "
                    + "chemins classés `SHAREPOINT_*` ou `ONEDRIVE_*` sont ceux qu'ils appellent ; les "
                    + "autres montrent ce que SharePoint web emprunte à la place.\n\n");
            table(md, files);
        }

        md.append("## Table des chemins, par hôte\n\n");
        Map<String, List<NetworkSurvey.Entry>> byHost = new LinkedHashMap<>();
        snapshot.entries().forEach(entry -> byHost.computeIfAbsent(entry.host(), key -> new ArrayList<>())
                .add(entry));
        byHost.forEach((host, entries) -> {
            md.append("### ").append(host).append("\n\n");
            table(md, entries);
        });
        return md.toString();
    }

    private static void table(StringBuilder md, List<NetworkSurvey.Entry> entries) {
        md.append("| Hôte | Chemin | Origines | Types | MIME | Statuts | Classification | Étape | Nombre |\n");
        md.append("|---|---|---|---|---|---|---|---|---|\n");
        for (NetworkSurvey.Entry entry : entries) {
            md.append("| `").append(cell(entry.host())).append("` | `").append(cell(entry.path())).append("` | ")
                    .append(String.join(", ", entry.origins)).append(" | ")
                    .append(cell(String.join(", ", entry.resourceTypes))).append(" | ")
                    .append(cell(String.join(", ", entry.mimeTypes))).append(" | ")
                    .append(entry.statuses.stream().map(String::valueOf).collect(Collectors.joining(", ")))
                    .append(" | ").append(entry.classification()).append(" | ")
                    .append(STEPS.get(Math.min(Math.max(entry.firstStep(), 0), STEPS.size() - 1)))
                    .append(" | ").append(entry.count()).append(" |\n");
        }
        md.append('\n');
    }

    private static String cell(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("`", "'").replace("\n", " ");
    }

    String json() {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        ObjectNode root = mapper.createObjectNode();
        root.put("feature", "F-100 / SF-100-00");
        root.put("startedAt", String.valueOf(startedAt));
        root.put("endedAt", String.valueOf(endedAt));
        root.put("interrupted", interrupted);
        root.put("browser", browser);
        root.put("adapter", adapterVersion);
        root.put("outsideMicrosoft", snapshot.outsideMicrosoft());
        root.put("staticResources", snapshot.staticResources());
        root.put("refusedTargets", snapshot.refusedTargets());
        root.put("dropped", snapshot.dropped());
        ArrayNode paths = root.putArray("paths");
        for (NetworkSurvey.Entry entry : snapshot.entries()) {
            ObjectNode node = paths.addObject();
            node.put("host", entry.host());
            node.put("path", entry.path());
            node.put("classification", entry.classification());
            node.put("firstStep", entry.firstStep());
            node.put("count", entry.count());
            node.put("onlyOutsideTeamsTab", entry.onlyOutsideTeamsTab());
            ArrayNode origins = node.putArray("origins");
            entry.origins.forEach(origins::add);
            ArrayNode types = node.putArray("resourceTypes");
            entry.resourceTypes.forEach(types::add);
            ArrayNode mimes = node.putArray("mimeTypes");
            entry.mimeTypes.forEach(mimes::add);
            ArrayNode statuses = node.putArray("statuses");
            entry.statuses.forEach(statuses::add);
            ArrayNode steps = node.putArray("steps");
            entry.steps.forEach(steps::add);
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }
}
