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

    /**
     * Profondeur au-delà de laquelle on ne déplie plus (un DOM est profond ; on borne pour le relevé).
     *
     * <p>SF-89-17 : monté de 12 à <b>22</b> — le premier relevé (SF-89-16) s'arrêtait à 12, avant les
     * nœuds message/auteur du sous-arbre {@code message-pane-list-runway}. Le volume reste borné : le
     * <b>repli des frères</b> garde le compte de nœuds bas même en profondeur (50 messages → un nœud
     * représentatif {@code n=50}), et {@link #MAX_NODES} plafonne toujours la vue.</p>
     */
    static final int MAX_DEPTH = 22;
    /** Enfants dépliés au plus par nœud (après repli des frères identiques). */
    static final int MAX_CHILDREN = 40;
    /** Nœuds au plus par vue : borne le volume remonté au Journal. */
    static final int MAX_NODES = 160;
    /** Cadres iframe relevés au plus par relevé (SF-89-17) : borne le volume et le temps. */
    static final int MAX_FRAMES = 3;
    /**
     * Zones de layout relevées au plus par relevé (SF-89-19) : borne le volume et le temps. Teams v2
     * découpe l'écran en quelques zones {@code [data-tid^="app-layout-area--"]} (main, sidebar, rail,
     * header…) ; 8 couvre largement et plafonne le Journal.
     */
    static final int MAX_AREAS = 8;

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

    /**
     * La racine du <b>rail de gauche</b> (liste des chats) — SF-89-17. SF-89-16 ne captait que le volet
     * {@code app-layout-area--main} ; le rail vit dans une autre zone de layout. On le cible par
     * {@code data-tid}/{@code role} structurants (<b>jamais</b> par classe : les classes v2 sont hashées
     * et instables). Le premier candidat trouvé l'emporte.
     */
    static final List<String> RAIL_SELECTORS = List.of(
            "[data-tid=\"app-layout-area--rail\"]", "[data-tid=\"chat-list\"]",
            "[data-tid=\"chat-list-tree\"]", "[role=\"navigation\"]", "[role=\"tree\"]",
            "[data-tid$=\"-rail\"]");

    /**
     * La racine du <b>sous-arbre des messages</b> (un fil ouvert) — SF-89-17. On y cible en profondeur les
     * enfants répétés (= les messages) pour capter la forme d'<b>un</b> message (conteneur, auteur,
     * horodatage, texte — <b>structure seulement</b>). Ciblé par {@code data-tid}, jamais par classe.
     */
    static final List<String> RUNWAY_SELECTORS = List.of(
            "[data-tid=\"message-pane-list-runway\"]", "[data-tid=\"message-pane-body\"]",
            "[data-tid=\"message-pane-list-viewport\"]");

    /**
     * La racine de la <b>liste des chats</b> (les conversations) — SF-89-18. Distincte du {@code rail}
     * (barre d'icônes d'application, zone de layout captée sous {@code chat_rail} par SF-89-17) : c'est
     * <b>cette</b> liste, à l'intérieur de l'iframe same-origin {@code hwc-iframe}, qui portait le
     * <i>0 conversation</i>. Ciblée par {@code role=tree}/{@code role=list} et les {@code data-tid} de
     * liste de chats (<b>jamais</b> par classe : les classes v2 sont hashées). Premier candidat trouvé.
     */
    static final List<String> LIST_SELECTORS = List.of(
            "[data-tid=\"chat-list\"]", "[data-tid=\"chatListItems\"]", "[data-tid$=\"chatListItem\"]",
            "[data-tid*=\"chat-list\"]", "[data-tid^=\"list-\"]", "[role=\"tree\"]", "[role=\"list\"]",
            "[role=\"grid\"]");

    /**
     * Le sélecteur de <b>découverte des zones de layout</b> (SF-89-19). Teams v2 découpe l'écran en
     * plusieurs {@code [data-tid^="app-layout-area--"]} (main, sidebar, rail, header…). SF-89-16→18 ne
     * relevaient que {@code --main} (le volet du chat ouvert) ; la <b>liste des conversations</b> vit dans
     * une <b>autre</b> zone (panneau de gauche, ex. {@code app-layout-area--sidebar}), jamais captée. On
     * les énumère toutes par ce préfixe d'attribut — stable, indépendant des classes hashées de la v2.
     */
    static final String AREA_DISCOVERY = "[data-tid^=\"app-layout-area--\"]";

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

    /**
     * Les {@code data-tid} (déjà assainis) des nœuds {@code <iframe>} d'une squelette, <b>dans l'ordre</b>
     * — SF-89-17. Sert à <b>étiqueter</b> les cadres relevés : le cadre attaché n°{@code i} prend le tid
     * de la n<sup>e</sup> iframe du document principal (p. ex. {@code hwc-iframe}), à défaut {@code iframe#N}.
     * Corrélation d'ordre, best-effort : l'étiquette est un confort de lecture, la valeur utile est la forme
     * du cadre. Ne porte jamais qu'un tid déjà assaini (aucune valeur).
     */
    static List<String> iframeTids(Survey survey) {
        List<String> out = new ArrayList<>();
        for (Node node : survey.nodes()) {
            if ("iframe".equals(node.tag())) {
                out.add(node.tid());
            }
        }
        return out;
    }

    /** Une squelette vide, réutilisée pour les zones absentes d'un cadre (SF-89-18). */
    private static final Survey EMPTY = new Survey(false, false, List.of());

    /**
     * La forme d'un iframe <b>same-origin</b> descendu par {@link #framesScript} (F-89 / SF-89-18) : son
     * {@code label} (assaini), et — s'il est lisible — la forme de sa racine large, de sa <b>liste des
     * chats</b>, de son rail et de son runway. Un cadre <b>cross-origin</b> ({@code blocked}) ne porte que
     * son label : {@code contentDocument} était nul, rien n'a été lu.
     */
    record FrameShape(String label, boolean blocked, Survey root, Survey list, Survey rail, Survey runway) {
    }

    /**
     * La forme d'une <b>zone de layout</b> du document principal (F-89 / SF-89-19) : son {@code area} (le
     * {@code data-tid} de la zone, assaini, ex. {@code app-layout-area--sidebar}), la forme de la zone
     * elle-même ({@code shape}) et — s'il y en a une — la forme de la <b>liste des chats</b> qui y vit
     * ({@code list}, ciblée par {@link #LIST_SELECTORS}). C'est dans ces zones (autres que {@code --main})
     * que se trouve la liste des conversations v2.
     */
    record AreaShape(String area, Survey shape, Survey list) {
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

    /**
     * <b>Le script qui descend dans les iframes same-origin</b> (F-89 / SF-89-18), à passer à
     * {@code Runtime.evaluate} <b>dans l'onglet</b> (via {@link PageActions#readScript(String)}) — aucune
     * nouvelle commande CDP. Il parcourt les {@code <iframe>} du document (bornés à {@link #MAX_FRAMES}) et,
     * pour chacun, tente d'accéder à {@code iframe.contentDocument} :
     *
     * <ul>
     *   <li><b>same-origin</b> ({@code contentDocument} non nul) : il relève la forme de la racine large,
     *       de la <b>liste des chats</b> ({@link #LIST_SELECTORS}), du rail et du runway <b>dans ce
     *       {@code contentDocument}</b>, avec exactement les mêmes gardes que {@link #surveyScript} (texte
     *       élidé en longueur, valeurs d'attributs jamais lues hors {@code data-tid}/{@code role}/{@code
     *       class}, ni {@code textContent}/{@code innerText}/cookie/stockage) ;</li>
     *   <li><b>cross-origin</b> ({@code contentDocument} nul ou accès qui lève) : le cadre est marqué
     *       {@code blocked} — <b>aucune URL du cadre n'est lue</b>, on ne franchit pas la barrière
     *       d'origine.</li>
     * </ul>
     *
     * <p>Chaque cadre porte un {@code label} best-effort : le {@code data-tid} de l'{@code <iframe>} (p. ex.
     * {@code hwc-iframe}, lisible car le document parent est same-origin), sinon {@code iframe#N}. Le label
     * est assaini côté Java ({@link #refilterFrames}).</p>
     */
    static String framesScript(ObjectMapper mapper) {
        ArrayNode root = mapper.createArrayNode();
        ROOT_SELECTORS.forEach(root::add);
        ArrayNode list = mapper.createArrayNode();
        LIST_SELECTORS.forEach(list::add);
        ArrayNode rail = mapper.createArrayNode();
        RAIL_SELECTORS.forEach(rail::add);
        ArrayNode runway = mapper.createArrayNode();
        RUNWAY_SELECTORS.forEach(runway::add);
        return "(() => { /*cg-domframes*/"
                + " const MAX_DEPTH = " + MAX_DEPTH + ", MAX_CHILDREN = " + MAX_CHILDREN
                + ", MAX_NODES = " + MAX_NODES + ", MAX_FRAMES = " + MAX_FRAMES + ";"
                + " const ROOT = " + root + ", LIST = " + list + ", RAIL = " + rail
                + ", RUNWAY = " + runway + ";"
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
                + " const surveyRoot = (doc, roots) => {"
                + "   let root = null;"
                + "   for (const s of roots) { let e = null; try { e = doc.querySelector(s); } catch (x) { e = null; }"
                + "     if (e) { root = e; break; } }"
                + "   if (!root) { return { found: false, truncated: false, nodes: [] }; }"
                + "   const nodes = []; let truncated = false;"
                + "   const walk = (el, depth, repeat) => {"
                + "     if (nodes.length >= MAX_NODES) { truncated = true; return; }"
                + "     const names = attrNames(el);"
                + "     const kids = el.children ? el.children.length : 0;"
                + "     nodes.push({ d: depth, n: repeat, tag: (el.tagName || '').toLowerCase(),"
                + "       tid: el.getAttribute('data-tid') || '', role: el.getAttribute('role') || '',"
                + "       aria: names.aria, attrs: names.other, cls: classesOf(el), text: textLen(el), kids: kids });"
                + "     if (depth >= MAX_DEPTH) { if (kids > 0) { truncated = true; } return; }"
                + "     const children = el.children ? Array.prototype.slice.call(el.children) : [];"
                + "     let emitted = 0, i = 0;"
                + "     while (i < children.length) {"
                + "       if (emitted >= MAX_CHILDREN) { truncated = true; break; }"
                + "       const s = sig(children[i]); let j = i + 1;"
                + "       while (j < children.length && sig(children[j]) === s) { j++; }"
                + "       walk(children[i], depth + 1, j - i); emitted++; i = j;"
                + "     }"
                + "   };"
                + "   walk(root, 0, 1);"
                + "   return { found: true, truncated: truncated, nodes: nodes };"
                + " };"
                + " const frames = []; let iframes = [];"
                + " try { iframes = Array.prototype.slice.call(document.querySelectorAll('iframe')); }"
                + "   catch (e) { iframes = []; }"
                + " for (let i = 0; i < iframes.length && frames.length < MAX_FRAMES; i++) {"
                + "   const f = iframes[i];"
                + "   const label = f.getAttribute('data-tid') || ('iframe#' + (i + 1));"
                + "   let cd = null; try { cd = f.contentDocument; } catch (e) { cd = null; }"
                + "   if (!cd) { frames.push({ label: label, blocked: true }); continue; }"
                + "   frames.push({ label: label, blocked: false,"
                + "     root: surveyRoot(cd, ROOT), list: surveyRoot(cd, LIST),"
                + "     rail: surveyRoot(cd, RAIL), runway: surveyRoot(cd, RUNWAY) });"
                + " }"
                + " return { frames: frames };"
                + "})()";
    }

    /**
     * <b>Le script qui énumère toutes les zones de layout</b> (F-89 / SF-89-19), à passer à
     * {@code Runtime.evaluate} <b>dans l'onglet</b> (via {@link PageActions#readScript(String)}) — aucune
     * nouvelle commande CDP. Il parcourt {@code document.querySelectorAll('[data-tid^="app-layout-area--"]')}
     * (bornées à {@link #MAX_AREAS}) et, pour chaque zone :
     *
     * <ul>
     *   <li>relève la <b>forme de la zone</b> elle-même (racine = l'élément de zone), avec exactement les
     *       mêmes gardes que {@link #surveyScript} (texte élidé en longueur, valeurs d'attributs jamais lues
     *       hors {@code data-tid}/{@code role}/{@code class}, ni {@code textContent}/{@code innerText}/
     *       cookie/stockage) ;</li>
     *   <li>relève, <b>dans</b> cette zone, la forme de la <b>liste des chats</b> (premier
     *       {@link #LIST_SELECTORS} trouvé sous la zone) — c'est elle qu'on cherche ; vide si absente.</li>
     * </ul>
     *
     * <p>Chaque zone porte son {@code area} : le {@code data-tid} de l'élément de zone (p. ex.
     * {@code app-layout-area--sidebar}), assaini côté Java ({@link #refilterAreas}).</p>
     */
    static String areasScript(ObjectMapper mapper) {
        ArrayNode list = mapper.createArrayNode();
        LIST_SELECTORS.forEach(list::add);
        return "(() => { /*cg-domareas*/"
                + " const MAX_DEPTH = " + MAX_DEPTH + ", MAX_CHILDREN = " + MAX_CHILDREN
                + ", MAX_NODES = " + MAX_NODES + ", MAX_AREAS = " + MAX_AREAS + ";"
                + " const AREA = " + mapper.getNodeFactory().textNode(AREA_DISCOVERY) + ", LIST = " + list + ";"
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
                + " const surveyFrom = (root) => {"
                + "   if (!root) { return { found: false, truncated: false, nodes: [] }; }"
                + "   const nodes = []; let truncated = false;"
                + "   const walk = (el, depth, repeat) => {"
                + "     if (nodes.length >= MAX_NODES) { truncated = true; return; }"
                + "     const names = attrNames(el);"
                + "     const kids = el.children ? el.children.length : 0;"
                + "     nodes.push({ d: depth, n: repeat, tag: (el.tagName || '').toLowerCase(),"
                + "       tid: el.getAttribute('data-tid') || '', role: el.getAttribute('role') || '',"
                + "       aria: names.aria, attrs: names.other, cls: classesOf(el), text: textLen(el), kids: kids });"
                + "     if (depth >= MAX_DEPTH) { if (kids > 0) { truncated = true; } return; }"
                + "     const children = el.children ? Array.prototype.slice.call(el.children) : [];"
                + "     let emitted = 0, i = 0;"
                + "     while (i < children.length) {"
                + "       if (emitted >= MAX_CHILDREN) { truncated = true; break; }"
                + "       const s = sig(children[i]); let j = i + 1;"
                + "       while (j < children.length && sig(children[j]) === s) { j++; }"
                + "       walk(children[i], depth + 1, j - i); emitted++; i = j;"
                + "     }"
                + "   };"
                + "   walk(root, 0, 1);"
                + "   return { found: true, truncated: truncated, nodes: nodes };"
                + " };"
                + " const listIn = (zone) => {"
                + "   for (const s of LIST) { let e = null; try { e = zone.querySelector(s); } catch (x) { e = null; }"
                + "     if (e) { return surveyFrom(e); } }"
                + "   return { found: false, truncated: false, nodes: [] };"
                + " };"
                + " const areas = []; let zones = [];"
                + " try { zones = Array.prototype.slice.call(document.querySelectorAll(AREA)); }"
                + "   catch (e) { zones = []; }"
                + " for (let i = 0; i < zones.length && areas.length < MAX_AREAS; i++) {"
                + "   const z = zones[i];"
                + "   const area = z.getAttribute('data-tid') || ('area#' + (i + 1));"
                + "   areas.push({ area: area, shape: surveyFrom(z), list: listIn(z) });"
                + " }"
                + " return { areas: areas };"
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

    /**
     * Ce que {@link #framesScript} a rendu, <b>re-filtré</b> (F-89 / SF-89-18) : la liste des cadres iframe
     * descendus, bornée à {@link #MAX_FRAMES}, label <b>assaini</b> ({@link #scrubToken}), et chaque zone
     * re-filtrée par {@link #refilter} (même cœur de vie privée que le document principal — aucune valeur
     * ne franchit cette couche, {@code contentDocument} inclus). Un cadre {@code blocked} ne porte que son
     * label.
     */
    static List<FrameShape> refilterFrames(JsonNode raw) {
        List<FrameShape> out = new ArrayList<>();
        if (raw == null || !raw.isObject()) {
            return out;
        }
        JsonNode frames = raw.path("frames");
        if (!frames.isArray()) {
            return out;
        }
        for (JsonNode frame : frames) {
            if (out.size() >= MAX_FRAMES) {
                break;
            }
            if (!frame.isObject()) {
                continue;
            }
            String label = scrubToken(frame.path("label").asText(""), MAX_TID_CHARS);
            if (label.isEmpty()) {
                label = "iframe#" + (out.size() + 1);
            }
            if (frame.path("blocked").asBoolean(false)) {
                out.add(new FrameShape(label, true, EMPTY, EMPTY, EMPTY, EMPTY));
                continue;
            }
            out.add(new FrameShape(label, false, refilter(frame.path("root")),
                    refilter(frame.path("list")), refilter(frame.path("rail")),
                    refilter(frame.path("runway"))));
        }
        return out;
    }

    /**
     * Ce que {@link #areasScript} a rendu, <b>re-filtré</b> (F-89 / SF-89-19) : la liste des zones de layout
     * énumérées, bornée à {@link #MAX_AREAS}, {@code area} <b>assaini</b> ({@link #scrubToken}), et chaque
     * forme (zone + liste des chats) re-filtrée par {@link #refilter} (même cœur de vie privée que le
     * document principal — aucune valeur ne franchit cette couche, zone {@code --sidebar} incluse).
     */
    static List<AreaShape> refilterAreas(JsonNode raw) {
        List<AreaShape> out = new ArrayList<>();
        if (raw == null || !raw.isObject()) {
            return out;
        }
        JsonNode areas = raw.path("areas");
        if (!areas.isArray()) {
            return out;
        }
        for (JsonNode area : areas) {
            if (out.size() >= MAX_AREAS) {
                break;
            }
            if (!area.isObject()) {
                continue;
            }
            String label = scrubToken(area.path("area").asText(""), MAX_TID_CHARS);
            if (label.isEmpty()) {
                label = "area#" + (out.size() + 1);
            }
            out.add(new AreaShape(label, refilter(area.path("shape")), refilter(area.path("list"))));
        }
        return out;
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
