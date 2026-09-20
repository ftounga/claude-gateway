package fr.claudegateway.runner.teams;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * <b>La forme du DOM, jamais son contenu</b> (F-89 / SF-89-16) — le relevé de <b>squelette</b> de la
 * vue Conversations du nouveau Teams (v2), pour recaler les sélecteurs de {@link TeamsScreen}.
 *
 * <p>Les conversations et les messages du client v2 viennent de l'<b>écran</b> (cache de Chrome), pas
 * du réseau : le relevé « forme » des corps JSON (SF-89-12/14, {@link PayloadShape}) ne les révèle pas.
 * C'est la <b>table de sélecteurs</b> ({@code data-tid="chat-list"}, {@code chat-list-item},
 * {@code message-pane-list-viewport}…), calée sur une version antérieure, qui ne matche plus la v2 et
 * lit 0 conversation. Pour la recaler, il faut voir la <b>forme réelle</b> du DOM — et elle seule.</p>
 *
 * <h2>Ce qui sort — et rien d'autre</h2>
 * <ul>
 *   <li>par nœud : le <b>nom de balise</b>, les valeurs <b>structurantes</b> {@code data-tid} /
 *       {@code role} / {@code class} (assainies), les <b>noms</b> des attributs {@code aria-*} et des
 *       autres attributs (<b>jamais</b> leurs valeurs), la <b>profondeur</b>, le <b>nombre d'enfants</b>,
 *       et le <b>compteur</b> {@code n} de frères identiques repliés ;</li>
 *   <li>tout <b>nœud texte est élidé</b> : remplacé par sa <b>longueur</b> (un entier), jamais son
 *       contenu ;</li>
 *   <li>tout ce qui pourrait porter un nom, un message, une adresse, un identifiant de fil — texte,
 *       {@code aria-label}, {@code title}, {@code href}, {@code id}, {@code data-item-id}… — sort
 *       <b>en nom d'attribut seulement</b>, jamais en valeur.</li>
 * </ul>
 *
 * <h2>Les gardes, dans le script et après lui</h2>
 * <ul>
 *   <li>Le <b>script</b> ({@link #surveyScript}) n'émet déjà que la forme : longueurs, noms de balises
 *       et d'attributs, valeurs {@code data-tid}/{@code role}/{@code class}. Il ne lit jamais
 *       {@code textContent}/{@code innerText} comme valeur (seulement {@code .length}), ni
 *       {@code document.cookie}, ni le stockage — et un test le garde.</li>
 *   <li>Java <b>re-filtre</b> ({@link #refilter}) : clés hors schéma écartées, texte forcé en entier,
 *       {@code data-tid} et jetons de classe qui <b>ressemblent à un identifiant</b> ramenés à
 *       {@link SurveyPaths#ID} ({@link SurveyPaths#looksLikeId}), noms d'attributs validés, tout borné.
 *       C'est le <b>cœur du test de vie privée</b>.</li>
 *   <li>Enfin {@code RunnerDiagRedaction} re-expurge à l'entrée du Journal (F-132) : triple garde.</li>
 * </ul>
 */
final class TeamsDomShape {

    /** Profondeur au-delà de laquelle on ne déplie plus (un DOM est profond ; on borne pour le relevé). */
    static final int MAX_DEPTH = 12;
    /** Enfants dépliés au plus par nœud (après repli des frères identiques). */
    static final int MAX_CHILDREN = 40;
    /** Nœuds au plus par vue : borne le volume remonté au Journal. */
    static final int MAX_NODES = 160;

    static final int MAX_CLASS_TOKENS = 6;
    static final int MAX_ATTR_NAMES = 16;
    static final int MAX_TOKEN_CHARS = 40;
    static final int MAX_TID_CHARS = 80;
    static final int MAX_TAG_CHARS = 24;
    static final int MAX_ROLE_CHARS = 40;

    /**
     * La racine du relevé : large et robuste, <b>indépendante des sélecteurs suspects</b> de
     * {@link TeamsScreen} — c'est justement leur forme réelle qu'on cherche à révéler. Le premier
     * candidat trouvé l'emporte ; à défaut, {@code document.body}.
     */
    static final List<String> ROOT_SELECTORS = List.of(
            "[data-tid=\"app-layout-area--main\"]", "[role=\"main\"]", "main", "#app");

    /**
     * De quoi <b>ouvrir</b> le premier fil pour en relever la forme — un jeu de sélecteurs large et
     * indépendant de la table cassée. Le premier qui répond au clic l'emporte.
     */
    static final List<String> THREAD_OPENERS = List.of(
            "[role=\"treeitem\"]", "[role=\"listitem\"] a[href]", "a[href*=\"conversations\"]",
            "[data-tid$=\"list-item\"] a[href]", "[data-tid*=\"chat-list-item\"]");

    /** Un nom d'attribut admissible : une clé, jamais une valeur (pas de {@code =}, pas d'espace). */
    private static final Pattern ATTR_NAME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9:_-]*$");
    /** Un jeton de balise/rôle : lettres, chiffres, tiret, souligné, espace (pour rôle). */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}]");

    private TeamsDomShape() {
    }

    /**
     * Un nœud de la squelette : sa forme, jamais son contenu.
     *
     * @param depth      profondeur dans l'arbre relevé
     * @param repeat     nombre de frères identiques repliés en ce nœud représentatif ({@code >= 1})
     * @param tag        nom de balise (minuscule)
     * @param tid        {@code data-tid} assaini ({@code ""} si absent)
     * @param role       {@code role} borné ({@code ""} si absent)
     * @param aria       noms des attributs {@code aria-*} présents (jamais leurs valeurs)
     * @param attrs      noms des autres attributs présents (jamais leurs valeurs)
     * @param classes    jetons de {@code class} assainis
     * @param textLen    longueur totale des nœuds texte directs (un entier, jamais le texte)
     * @param childCount nombre d'enfants du nœud dans la page
     */
    record Node(int depth, int repeat, String tag, String tid, String role, List<String> aria,
            List<String> attrs, List<String> classes, int textLen, int childCount) {

        Node {
            aria = List.copyOf(aria);
            attrs = List.copyOf(attrs);
            classes = List.copyOf(classes);
        }
    }

    /** Une squelette relevée : trouvée ?, tronquée par une borne ?, et ses nœuds en pré-ordre DFS. */
    record Survey(boolean found, boolean truncated, List<Node> nodes) {

        Survey {
            nodes = List.copyOf(nodes);
        }

        int maxDepth() {
            return nodes.stream().mapToInt(Node::depth).max().orElse(0);
        }
    }

    // ------------------------------------------------------------------ le script

    /**
     * Le script de relevé, à passer à {@code Runtime.evaluate} (via {@code PageActions.readScript}).
     * Il descend depuis la première racine trouvée, replie les frères identiques (même
     * {@code tag|data-tid|role}) en un nœud + un compteur, et rend un tableau plat de nœuds — texte
     * élidé en longueur, valeurs d'attributs jamais lues (hors {@code data-tid}/{@code role}/{@code class}).
     */
    static String surveyScript(ObjectMapper mapper, List<String> roots) {
        ArrayNode array = mapper.createArrayNode();
        roots.forEach(array::add);
        return "(() => { /*cg-domshape*/ const ROOTS = " + array + ";"
                + " const MAX_DEPTH = " + MAX_DEPTH + ", MAX_CHILDREN = " + MAX_CHILDREN
                + ", MAX_NODES = " + MAX_NODES + ";"
                + " let root = null;"
                + " for (const s of ROOTS) { const e = document.querySelector(s); if (e) { root = e; break; } }"
                + " if (!root) { root = document.body; }"
                + " if (!root) { return { found: false, truncated: false, nodes: [] }; }"
                + " const nodes = []; let truncated = false;"
                + " const sig = (el) => el.tagName + '|' + (el.getAttribute('data-tid') || '') + '|'"
                + "   + (el.getAttribute('role') || '');"
                + " const textLen = (el) => { let n = 0; for (const c of el.childNodes) {"
                + "   if (c.nodeType === 3) { n += (c.nodeValue || '').length; } } return n; };"
                + " const attrNames = (el) => { const aria = [], other = [];"
                + "   const list = el.attributes || [];"
                + "   for (let i = 0; i < list.length; i++) { const nm = list[i].name;"
                + "     if (nm === 'class' || nm === 'style' || nm === 'data-tid' || nm === 'role') { continue; }"
                + "     if (nm.indexOf('aria-') === 0) { aria.push(nm); } else { other.push(nm); } }"
                + "   return { aria: aria, other: other }; };"
                + " const classesOf = (el) => { try { return Array.prototype.slice.call(el.classList); }"
                + "   catch (e) { return []; } };"
                + " const walk = (el, depth, repeat) => {"
                + "   if (nodes.length >= MAX_NODES) { truncated = true; return; }"
                + "   const names = attrNames(el);"
                + "   const kids = el.children ? el.children.length : 0;"
                + "   nodes.push({ d: depth, n: repeat, tag: (el.tagName || '').toLowerCase(),"
                + "     tid: el.getAttribute('data-tid') || '', role: el.getAttribute('role') || '',"
                + "     aria: names.aria, attrs: names.other, cls: classesOf(el), text: textLen(el), kids: kids });"
                + "   if (depth >= MAX_DEPTH) { if (kids > 0) { truncated = true; } return; }"
                + "   const children = el.children ? Array.prototype.slice.call(el.children) : [];"
                + "   let emitted = 0, i = 0;"
                + "   while (i < children.length) {"
                + "     if (emitted >= MAX_CHILDREN) { truncated = true; break; }"
                + "     const s = sig(children[i]); let j = i + 1;"
                + "     while (j < children.length && sig(children[j]) === s) { j++; }"
                + "     walk(children[i], depth + 1, j - i); emitted++; i = j;"
                + "   }"
                + " };"
                + " walk(root, 0, 1);"
                + " return { found: true, truncated: truncated, nodes: nodes };"
                + "})()";
    }

    // ------------------------------------------------------------------ le re-filtre Java (cœur vie privée)

    /**
     * Ce que le script a rendu, <b>re-filtré</b> : seules les clés du schéma, le texte forcé en entier,
     * {@code data-tid}/classe assainis, noms d'attributs validés, tout borné. Un script modifié ne peut
     * pas faire passer une valeur : rien d'autre que la forme ne franchit cette couche.
     */
    static Survey refilter(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            return new Survey(false, false, List.of());
        }
        boolean found = raw.path("found").asBoolean(false);
        boolean truncated = raw.path("truncated").asBoolean(false);
        List<Node> out = new ArrayList<>();
        JsonNode array = raw.path("nodes");
        if (array.isArray()) {
            for (JsonNode entry : array) {
                if (out.size() >= MAX_NODES) {
                    truncated = true;
                    break;
                }
                if (entry.isObject()) {
                    out.add(node(entry));
                }
            }
        }
        return new Survey(found, truncated, out);
    }

    private static Node node(JsonNode n) {
        int depth = clamp(n.path("d").asInt(0), 0, MAX_DEPTH);
        int repeat = Math.max(1, n.path("n").asInt(1));
        int textLen = Math.max(0, n.path("text").asInt(0));
        int kids = Math.max(0, n.path("kids").asInt(0));
        return new Node(depth, repeat, tag(n.path("tag").asText("")),
                scrubToken(n.path("tid").asText(""), MAX_TID_CHARS), role(n.path("role").asText("")),
                names(n.path("aria")), names(n.path("attrs")), classes(n.path("cls")), textLen, kids);
    }

    /** Un nom de balise : minuscule, {@code [a-z0-9-]}, borné. */
    private static String tag(String raw) {
        String value = strip(raw).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "");
        return value.length() > MAX_TAG_CHARS ? value.substring(0, MAX_TAG_CHARS) : value;
    }

    /** Un rôle : jeton court structurant, borné ; aucun caractère de contrôle. */
    private static String role(String raw) {
        String value = strip(raw).replaceAll("[^a-zA-Z0-9 _-]", "");
        return value.length() > MAX_ROLE_CHARS ? value.substring(0, MAX_ROLE_CHARS) : value;
    }

    /**
     * Un jeton structurant ({@code data-tid} ou classe) : ramené à {@link SurveyPaths#ID} s'il
     * <b>ressemble à un identifiant</b> (id de fil, GUID, hex long, base64…), sinon borné. Jamais une
     * valeur laissée passer telle quelle si elle porte la marque d'un contenu client.
     */
    static String scrubToken(String raw, int max) {
        String value = strip(raw);
        if (value.isEmpty()) {
            return "";
        }
        if (SurveyPaths.looksLikeId(value)) {
            return SurveyPaths.ID;
        }
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }

    /** Des <b>noms</b> d'attributs seulement : chacun validé comme une clé (jamais une valeur), borné. */
    private static List<String> names(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (!array.isArray()) {
            return out;
        }
        for (JsonNode entry : array) {
            if (out.size() >= MAX_ATTR_NAMES) {
                break;
            }
            if (!entry.isTextual()) {
                continue;
            }
            String name = strip(entry.asText());
            if (name.length() > MAX_TOKEN_CHARS) {
                name = name.substring(0, MAX_TOKEN_CHARS);
            }
            if (!name.isEmpty() && ATTR_NAME.matcher(name).matches()) {
                out.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** Des jetons de classe assainis, bornés. */
    private static List<String> classes(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (!array.isArray()) {
            return out;
        }
        for (JsonNode entry : array) {
            if (out.size() >= MAX_CLASS_TOKENS) {
                break;
            }
            if (!entry.isTextual()) {
                continue;
            }
            String token = scrubToken(entry.asText(), MAX_TOKEN_CHARS);
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ le rendu (msg + champs)

    /**
     * La ligne lisible d'un nœud, pour le {@code msg} d'un événement du Journal : indentée par la
     * profondeur, elle ne porte que la <b>forme</b> — jamais une valeur, jamais un texte, jamais une URL.
     */
    static String line(Node node) {
        StringBuilder out = new StringBuilder();
        int indent = Math.min(node.depth(), MAX_DEPTH);
        for (int i = 0; i < indent; i++) {
            out.append("  ");
        }
        out.append(node.tag().isEmpty() ? "?" : node.tag());
        if (!node.tid().isEmpty()) {
            out.append(" [data-tid=").append(node.tid()).append(']');
        }
        if (!node.role().isEmpty()) {
            out.append(" role=").append(node.role());
        }
        if (node.repeat() > 1) {
            out.append(" x").append(node.repeat());
        }
        out.append(" k:").append(node.childCount());
        if (node.textLen() > 0) {
            out.append(" t:").append(node.textLen());
        }
        if (!node.classes().isEmpty()) {
            out.append(" .").append(String.join(".", node.classes()));
        }
        if (!node.aria().isEmpty()) {
            out.append(" aria[").append(String.join(",", node.aria())).append(']');
        }
        return out.toString();
    }

    /**
     * Les champs scalaires d'un nœud, pour le {@code fields} d'un événement du Journal (F-132). Tout est
     * scalaire (nombre, ou chaîne de <b>noms</b>/jetons structurants) : rien qui porte un contenu.
     */
    static Map<String, Object> fields(Node node, int seq) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("seq", seq);
        map.put("d", node.depth());
        map.put("tag", node.tag());
        if (!node.tid().isEmpty()) {
            map.put("tid", node.tid());
        }
        if (!node.role().isEmpty()) {
            map.put("role", node.role());
        }
        map.put("n", node.repeat());
        map.put("kids", node.childCount());
        map.put("text", node.textLen());
        if (!node.aria().isEmpty()) {
            map.put("aria", String.join(",", node.aria()));
        }
        if (!node.attrs().isEmpty()) {
            map.put("attrs", String.join(",", node.attrs()));
        }
        if (!node.classes().isEmpty()) {
            map.put("cls", String.join(",", node.classes()));
        }
        return map;
    }

    private static String strip(String raw) {
        if (raw == null) {
            return "";
        }
        return CONTROL.matcher(raw).replaceAll(" ").strip();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
