package fr.claudegateway.atelier.skills;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Les déclencheurs déclarés dans l'en-tête d'un skill</b> (F-275 / SF-275-01, D1-D2) — lus sans configuration.
 *
 * <p>Deux formes reconnues, dans l'en-tête {@code ---} :</p>
 * <ul>
 *   <li>une clé {@code declencheurs:} / {@code déclencheurs:} / {@code triggers:} — valeurs en ligne
 *       ({@code a, b}, {@code [a, b]}, {@code « a », « b »}) ou liste YAML ({@code - a}) ;</li>
 *   <li>à défaut, dans {@code description:}, les segments entre guillemets qui <b>suivent</b> un mot déclencheur —
 *       forme réelle du skill {@code suivi-hebdo} : « Déclenché par « point hebdo », « suivi du jour » ou « prépare le
 *       comité ». »</li>
 * </ul>
 * <p>Clés facultatives : {@code sujet:} (le sujet du poste où lancer) et {@code clic: envoyer|deposer}.</p>
 *
 * <p>Pur : aucune lecture, aucun état. Ne lève jamais (un en-tête illisible ne déclare rien).</p>
 */
public final class SkillTriggers {

    /** Plafond de déclencheurs retenus par skill. */
    public static final int MAX_TRIGGERS = 5;
    /** Longueur maximale d'un déclencheur retenu. */
    public static final int MAX_TRIGGER_CHARS = 80;

    private static final Pattern KEY_LINE = Pattern.compile("^([A-Za-zÀ-ÿ_-]+)\\s*:\\s*(.*)$");
    /** Un mot qui annonce les déclencheurs dans une description. */
    private static final Pattern ANNOUNCE = Pattern.compile(
            "(d[ée]clench|trigger|quand on dit|quand je dis|[àa] dire|se lance par|lanc[ée]e? par)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Un segment entre guillemets français ou droits. */
    private static final Pattern QUOTED = Pattern.compile("«\\s*([^«»]+?)\\s*»|\"([^\"]+)\"|“([^”]+)”");

    private SkillTriggers() {
    }

    /** Ce que déclare le skill de contenu {@code body} ; {@link SkillLaunch#NONE} s'il ne déclare rien. */
    public static SkillLaunch parse(String body) {
        if (body == null || body.isBlank()) {
            return SkillLaunch.NONE;
        }
        Map<String, String> inline = new LinkedHashMap<>();
        Map<String, List<String>> lists = new LinkedHashMap<>();
        if (!readFrontMatter(body, inline, lists)) {
            return SkillLaunch.NONE;
        }
        List<String> triggers = new ArrayList<>();
        for (String key : List.of("declencheurs", "déclencheurs", "triggers", "declencheur", "déclencheur")) {
            if (lists.containsKey(key)) {
                lists.get(key).forEach(v -> add(triggers, v));
            }
            if (inline.containsKey(key)) {
                splitInline(inline.get(key)).forEach(v -> add(triggers, v));
            }
        }
        if (triggers.isEmpty() && inline.containsKey("description")) {
            fromDescription(inline.get("description")).forEach(v -> add(triggers, v));
        }
        if (triggers.isEmpty()) {
            return SkillLaunch.NONE;
        }
        String subject = clean(inline.get("sujet"));
        String clic = clean(inline.get("clic"));
        boolean send = clic != null && clic.toLowerCase(Locale.ROOT).startsWith("envoy");
        return new SkillLaunch(triggers, subject, send);
    }

    /** Clé normalisée d'une phrase : minuscules, espaces réduits (comparaisons et doublons). */
    public static String key(String phrase) {
        return phrase == null ? "" : phrase.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ lecture

    /** Lit l'en-tête {@code ---} ; faux s'il n'y en a pas. */
    private static boolean readFrontMatter(String body, Map<String, String> inline, Map<String, List<String>> lists) {
        List<String> lines = body.lines().toList();
        int i = 0;
        while (i < lines.size() && lines.get(i).isBlank()) {
            i++;
        }
        if (i >= lines.size() || !lines.get(i).strip().equals("---")) {
            return false;
        }
        String currentList = null;
        for (i = i + 1; i < lines.size(); i++) {
            String raw = lines.get(i);
            String line = raw.strip();
            if (line.equals("---")) {
                return true;
            }
            if (line.startsWith("- ") && currentList != null) {
                lists.computeIfAbsent(currentList, k -> new ArrayList<>()).add(line.substring(2));
                continue;
            }
            Matcher m = KEY_LINE.matcher(line);
            if (m.matches()) {
                String key = m.group(1).toLowerCase(Locale.ROOT);
                String value = m.group(2).strip();
                if (value.isEmpty()) {
                    currentList = key;
                } else {
                    currentList = null;
                    inline.putIfAbsent(key, value);
                }
            } else {
                currentList = null;
            }
        }
        return true;
    }

    /** {@code a, b} · {@code [a, b]} · {@code « a », « b »}. */
    private static List<String> splitInline(String value) {
        List<String> quoted = quotedIn(value);
        if (!quoted.isEmpty()) {
            return quoted;
        }
        String v = value.strip();
        if (v.startsWith("[") && v.endsWith("]")) {
            v = v.substring(1, v.length() - 1);
        }
        List<String> out = new ArrayList<>();
        for (String part : v.split("[,;|]")) {
            out.add(part);
        }
        return out;
    }

    /** Les segments entre guillemets qui suivent le premier mot déclencheur de la description. */
    private static List<String> fromDescription(String description) {
        Matcher announce = ANNOUNCE.matcher(description);
        if (!announce.find()) {
            return List.of();
        }
        return quotedIn(description.substring(announce.start()));
    }

    private static List<String> quotedIn(String text) {
        List<String> out = new ArrayList<>();
        Matcher q = QUOTED.matcher(text);
        while (q.find()) {
            for (int g = 1; g <= 3; g++) {
                if (q.group(g) != null) {
                    out.add(q.group(g));
                    break;
                }
            }
        }
        return out;
    }

    private static void add(List<String> triggers, String raw) {
        String value = clean(raw);
        if (value == null || triggers.size() >= MAX_TRIGGERS) {
            return;
        }
        if (value.length() > MAX_TRIGGER_CHARS) {
            value = value.substring(0, MAX_TRIGGER_CHARS).strip();
        }
        String k = key(value);
        if (triggers.stream().noneMatch(t -> key(t).equals(k))) {
            triggers.add(value);
        }
    }

    /** Retire guillemets et blancs de bord ; {@code null} si rien ne reste. */
    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.strip();
        while (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'")
                || v.startsWith("«") && v.endsWith("»") || v.startsWith("“") && v.endsWith("”"))) {
            v = v.substring(1, v.length() - 1).strip();
        }
        v = v.replaceAll("\\s+", " ");
        return v.isEmpty() ? null : v;
    }
}
