package fr.claudegateway.runner.teams;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * <b>La couche écran de l'adaptateur</b> (F-89 / SF-89-06) — lire le texte affiché quand le réseau ne
 * donne rien.
 *
 * <p>Décision du PO du 2026-09-13 (cadrage F-87 §9 bis) : le relevé réel a montré que le nouveau Teams
 * sert l'historique d'un fil depuis son cache local, et qu'aucun échange reconnaissable n'accompagne
 * une transcription. En <b>repli seulement</b>, le runner lit donc l'écran. Le réseau reste la source
 * quand il répond.</p>
 *
 * <h2>Ce que cette classe sait de Teams — et elle seule</h2>
 *
 * <p>La <b>table des sélecteurs</b> par vue ({@link #MESSAGES}, {@link #CONVERSATIONS}, {@link #ACTIVITY},
 * {@link #TRANSCRIPT}) et les commandes de la vue (bouton de téléchargement, ouverture du panneau). Elle
 * est versionnée ({@link #VERSION}) et <b>à confirmer sur poste réel</b> : écrite sur la connaissance
 * publique du client v2, éprouvée contre des DOM modèles. Une vue dont le conteneur n'est pas trouvé rend
 * un manque « Teams a changé d'écran », jamais un résultat à moitié faux.</p>
 *
 * <h2>Les gardes, dans le script et après lui</h2>
 *
 * <ul>
 *   <li>Le script est <b>générique</b> : il applique la table envoyée par Java, et ne lit que les champs
 *       qu'elle nomme — le texte d'un sous-élément, ou un attribut de {@link #READABLE_ATTRIBUTES}.</li>
 *   <li>Il ne lit <b>jamais</b> un champ de saisie ni ce qui en contient un ({@link #BANNED}), ni
 *       {@code document.cookie}, ni le stockage, ni les caches : ces mots n'existent pas dans le script,
 *       et un test le garde.</li>
 *   <li>Java <b>refiltre</b> ce qui revient ({@link #refilter}) : clés hors table écartées, valeurs non
 *       textuelles écartées, longueur bornée, caractères de contrôle retirés.</li>
 * </ul>
 */
final class TeamsScreen {

    /** Version de la table des sélecteurs : citée dans chaque manque d'écran et dans chaque résultat. */
    static final String VERSION = "ecran-v2-2026-09-13 (à confirmer sur poste réel)";

    /** Longueur maximale d'une valeur lue à l'écran. */
    static final int MAX_VALUE_CHARS = 4_000;

    /** Éléments lus au plus par écran. */
    static final int MAX_ITEMS = 200;

    /** Les seuls attributs qu'un champ peut lire. Rien qui porte une valeur saisie. */
    static final Set<String> READABLE_ATTRIBUTES = Set.of("datetime", "data-mid", "data-item-id", "id",
            "aria-label", "title", "disabled", "aria-disabled");

    /** Ce que le script ne lit jamais, ni ce qui le contient. */
    static final String BANNED = "input,textarea,select,[contenteditable]:not([contenteditable=\"false\"]),"
            + "[role=\"textbox\"],[type=\"password\"]";

    /** Un champ : où le lire dans l'élément, et comment (texte, ou un attribut listé). */
    record Field(List<String> selectors, List<String> attributes) {

        Field {
            selectors = List.copyOf(selectors == null ? List.of() : selectors);
            attributes = List.copyOf(attributes == null ? List.of() : attributes);
            for (String attribute : attributes) {
                if (!READABLE_ATTRIBUTES.contains(attribute)) {
                    throw new IllegalArgumentException("Attribut non lisible à l'écran : " + attribute);
                }
            }
        }

        static Field text(String... selectors) {
            return new Field(List.of(selectors), List.of());
        }

        static Field attribute(List<String> selectors, String... attributes) {
            return new Field(selectors, List.of(attributes));
        }
    }

    /**
     * Une vue lisible : son conteneur défilable, ses éléments, et les champs d'un élément.
     *
     * @param name      nom de la vue, cité dans un manque
     * @param container sélecteurs candidats du conteneur (le premier trouvé l'emporte)
     * @param item      sélecteurs candidats d'un élément dans le conteneur
     * @param fields    champs lus dans chaque élément, dans l'ordre
     */
    record View(String name, List<String> container, List<String> item, Map<String, Field> fields) {

        View {
            container = List.copyOf(container);
            item = List.copyOf(item);
            fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }
    }

    // ------------------------------------------------------------------ la table (à confirmer sur poste)

    static final View MESSAGES = new View("fil affiché",
            List.of("[data-tid=\"message-pane-list-viewport\"]", "[data-tid=\"chat-pane-list\"]"),
            List.of("[data-tid=\"chat-pane-item\"]"),
            fields("id", Field.attribute(List.of("[data-mid]"), "data-mid"),
                    "author", Field.text("[data-tid=\"message-author-name\"]"),
                    "time", Field.attribute(List.of("time[datetime]"), "datetime"),
                    "text", Field.text("[data-tid=\"chat-pane-message\"] [id^=\"content-\"]",
                            "[data-tid=\"message-body-content\"]")));

    static final View CONVERSATIONS = new View("liste des conversations",
            List.of("[data-tid=\"chat-list\"]", "[role=\"tree\"][data-tid=\"simple-collab-dnd-rail\"]"),
            List.of("[data-tid=\"chat-list-item\"]"),
            fields("id", Field.attribute(List.of(), "data-item-id", "id"),
                    "title", Field.text("[data-tid=\"chat-list-item-title\"]"),
                    "time", Field.attribute(List.of("time[datetime]"), "datetime")));

    static final View ACTIVITY = new View("flux d'activité",
            List.of("[data-tid=\"activity-feed-list\"]"),
            List.of("[data-tid=\"activity-feed-item\"]"),
            fields("id", Field.attribute(List.of(), "data-item-id", "id"),
                    "label", Field.attribute(List.of(), "aria-label"),
                    "author", Field.text("[data-tid=\"activity-item-author\"]"),
                    "time", Field.attribute(List.of("time[datetime]"), "datetime"),
                    "where", Field.text("[data-tid=\"activity-item-location\"]"),
                    "text", Field.text("[data-tid=\"activity-item-preview\"]")));

    static final View TRANSCRIPT = new View("panneau de transcription",
            List.of("[data-tid=\"transcript-list\"]"),
            List.of("[data-tid=\"transcript-entry\"]"),
            fields("id", Field.attribute(List.of(), "data-item-id", "id"),
                    "speaker", Field.text("[data-tid=\"transcript-speaker\"]"),
                    "offset", Field.text("[data-tid=\"transcript-timestamp\"]"),
                    "text", Field.text("[data-tid=\"transcript-text\"]")));

    /** Le bouton de téléchargement de la transcription, cherché dans le document. */
    static final List<String> TRANSCRIPT_DOWNLOAD = List.of("[data-tid=\"transcript-download-button\"]",
            "button[aria-label*=\"élécharger la transcription\"]", "button[aria-label*=\"ownload transcript\"]");

    /** Ce qui ouvre le panneau de transcription, cliqué par la garde de F-108. */
    static final List<String> TRANSCRIPT_OPENERS = List.of("[data-tid=\"transcript-tab\"]",
            "button[aria-label=\"Transcription\"]", "button[aria-label=\"Transcript\"]");

    private static Map<String, Field> fields(Object... pairs) {
        Map<String, Field> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            map.put((String) pairs[index], (Field) pairs[index + 1]);
        }
        return map;
    }

    private TeamsScreen() {
    }

    // ------------------------------------------------------------------ les scripts

    /** La table d'une vue, telle qu'elle voyage vers le script (JSON). */
    static String spec(ObjectMapper mapper, View view) {
        ObjectNode spec = mapper.createObjectNode();
        ArrayNode container = spec.putArray("container");
        view.container().forEach(container::add);
        ArrayNode item = spec.putArray("item");
        view.item().forEach(item::add);
        ObjectNode fields = spec.putObject("fields");
        view.fields().forEach((name, field) -> {
            ObjectNode node = fields.putObject(name);
            ArrayNode selectors = node.putArray("sel");
            field.selectors().forEach(selectors::add);
            ArrayNode attributes = node.putArray("attr");
            field.attributes().forEach(attributes::add);
        });
        return spec.toString();
    }

    private static final String COMMON = " const BAN = " + TextNode.valueOf(BANNED) + ";"
            + " const first = (root, sels) => { for (const s of sels) { const el = root.querySelector(s);"
            + "   if (el) { return el; } } return null; };"
            + " const unsafe = (el) => el.matches(BAN) || !!el.closest(BAN) || !!el.querySelector(BAN);"
            + " const box = first(document, spec.container);";

    /** Lire les éléments affichés d'une vue. Rend {@code {found, items, atStart, atEnd}}. */
    static String readScript(ObjectMapper mapper, View view) {
        return "(() => { /*cg-screen:read*/ const spec = " + spec(mapper, view) + "; /*cg-end*/" + COMMON
                + " if (!box) { return { found: false }; }"
                + " let els = []; for (const s of spec.item) { els = Array.from(box.querySelectorAll(s));"
                + "   if (els.length) { break; } }"
                + " const items = [];"
                + " for (const el of els.slice(0, " + MAX_ITEMS + ")) {"
                + "   const out = {};"
                + "   for (const name of Object.keys(spec.fields)) {"
                + "     const f = spec.fields[name];"
                + "     const target = f.sel.length ? first(el, f.sel) : el;"
                + "     if (!target || unsafe(target)) { continue; }"
                + "     let v = '';"
                + "     if (f.attr.length) { for (const a of f.attr) { const x = target.getAttribute(a);"
                + "       if (x !== null) { v = x === '' ? 'true' : x; break; } } }"
                + "     else { v = target.innerText || target.textContent || ''; }"
                + "     if (v) { out[name] = String(v).slice(0, " + MAX_VALUE_CHARS + "); }"
                + "   }"
                + "   items.push(out);"
                + " }"
                + " return { found: true, items: items, atStart: box.scrollTop <= 0,"
                + "   atEnd: box.scrollTop + box.clientHeight >= box.scrollHeight - 2 };"
                + "})()";
    }

    /** Faire défiler le conteneur d'un écran, vers le haut (plus ancien) ou le bas. Rend {@code {found, moved, position}}. */
    static String scrollScript(ObjectMapper mapper, View view, boolean up) {
        return "(() => { /*cg-screen:scroll-" + (up ? "up" : "down") + "*/ const spec = " + spec(mapper, view)
                + "; /*cg-end*/" + COMMON
                + " if (!box) { return { found: false, moved: false }; }"
                + " const before = box.scrollTop;"
                + " box.scrollTop = " + (up ? "Math.max(0, before - box.clientHeight)" : "before + box.clientHeight")
                + ";"
                + " return { found: true, moved: box.scrollTop !== before, position: before };"
                + "})()";
    }

    /** Remettre le conteneur à la position notée avant la lecture. */
    static String restoreScript(ObjectMapper mapper, View view, double position) {
        return "(() => { /*cg-screen:restore*/ const spec = " + spec(mapper, view) + "; /*cg-end*/" + COMMON
                + " if (!box) { return false; } box.scrollTop = " + position + "; return true; })()";
    }

    /** La position de défilement actuelle du conteneur, ou {@code -1}. */
    static String positionScript(ObjectMapper mapper, View view) {
        return "(() => { /*cg-screen:position*/ const spec = " + spec(mapper, view) + "; /*cg-end*/" + COMMON
                + " return box ? box.scrollTop : -1; })()";
    }

    /**
     * L'état du bouton de téléchargement de la transcription : {@code {panel, present, disabled}}. Aucune
     * valeur n'est lue, seulement la présence et l'état désactivé.
     */
    static String downloadControlScript(ObjectMapper mapper) {
        ObjectNode spec = mapper.createObjectNode();
        ArrayNode container = spec.putArray("container");
        TRANSCRIPT.container().forEach(container::add);
        ArrayNode buttons = spec.putArray("buttons");
        TRANSCRIPT_DOWNLOAD.forEach(buttons::add);
        return "(() => { /*cg-screen:control*/ const spec = " + spec + "; /*cg-end*/" + COMMON
                + " const button = first(document, spec.buttons);"
                + " return { panel: !!box, present: !!button,"
                + "   disabled: !!button && (button.hasAttribute('disabled')"
                + "     || button.getAttribute('aria-disabled') === 'true') };"
                + "})()";
    }

    // ------------------------------------------------------------------ le refiltrage Java

    /**
     * Ce que le script a rendu, <b>refiltré</b> : seuls les champs de la vue, seulement du texte, borné.
     * Le script est déjà gardé ; ce second filtre existe pour qu'une page modifiée ne puisse rien faire
     * passer d'autre (même patron que la projection SharePoint de F-108).
     */
    static Reading refilter(View view, JsonNode value) {
        if (value == null || !value.isObject() || !value.path("found").asBoolean(false)) {
            return new Reading(false, List.of(), false, false);
        }
        List<Map<String, String>> items = new ArrayList<>();
        JsonNode array = value.path("items");
        if (array.isArray()) {
            for (JsonNode entry : array) {
                if (items.size() >= MAX_ITEMS || !entry.isObject()) {
                    continue;
                }
                Map<String, String> item = new LinkedHashMap<>();
                for (String name : view.fields().keySet()) {
                    JsonNode field = entry.get(name);
                    if (field == null || !field.isTextual()) {
                        continue;
                    }
                    String clean = clean(field.asText());
                    if (!clean.isEmpty()) {
                        item.put(name, clean);
                    }
                }
                items.add(item);
            }
        }
        return new Reading(true, items, value.path("atStart").asBoolean(false), value.path("atEnd").asBoolean(false));
    }

    /** Texte borné, sans caractères de contrôle (hors saut de ligne). */
    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        raw.codePoints().limit(MAX_VALUE_CHARS).forEach(point -> {
            if (point == '\n' || !Character.isISOControl(point)) {
                out.appendCodePoint(point);
            }
        });
        return out.toString().strip();
    }

    /**
     * Un décalage de transcription affiché (« 1:02:03 », « 02:03 ») en secondes, ou {@code -1}.
     */
    static long offsetSeconds(String raw) {
        if (raw == null || !raw.strip().matches("\\d{1,2}(:\\d{2}){1,2}")) {
            return -1;
        }
        long total = 0;
        for (String part : raw.strip().split(":")) {
            total = total * 60 + Long.parseLong(part);
        }
        return total;
    }

    /**
     * Un écran lu.
     *
     * @param found   le conteneur de la vue a été trouvé
     * @param items   les éléments, champs refiltrés
     * @param atStart le conteneur est en haut (début d'une liste qui remonte)
     * @param atEnd   le conteneur est en bas
     */
    record Reading(boolean found, List<Map<String, String>> items, boolean atStart, boolean atEnd) {

        Reading {
            items = List.copyOf(items);
        }
    }
}
