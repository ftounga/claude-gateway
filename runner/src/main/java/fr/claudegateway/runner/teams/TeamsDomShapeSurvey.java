package fr.claudegateway.runner.teams;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.diag.RunnerDiag;
import fr.claudegateway.runner.diag.RunnerDiagLevel;

/**
 * <b>Le relevé de forme du DOM des conversations</b> (F-89 / SF-89-16), gardé par le niveau de
 * diagnostic F-132.
 *
 * <p>Quand — et <b>seulement</b> quand — le poste a été passé en {@code DEBUG} (geste admin explicite
 * et borné dans le temps, F-132 / SF-132-05, bouton « Activer DEBUG » déjà livré), ce pilote navigue
 * vers la vue Conversations (chemin confirmé), relève la <b>squelette</b> du DOM ({@link TeamsDomShape}),
 * ouvre un fil, relève sa forme, puis <b>remet la vue</b>. La squelette part dans le Journal du runner
 * (événements {@code cat=shape}, {@code code=conversations_list} / {@code thread}) et l'on peut la lire
 * en base pour recaler les sélecteurs {@link TeamsScreen} v2.</p>
 *
 * <p><b>Hors {@code DEBUG}, le runner est strictement inchangé</b> : aucune navigation, aucun script,
 * aucun événement. Le relevé ne lève <b>jamais</b> vers l'appelant : la lecture des conversations n'est
 * pas cassée par le diagnostic. Aucune valeur, aucun texte, aucun nom ne sort : uniquement la forme
 * (triple garde — script, {@link TeamsDomShape#refilter}, {@code RunnerDiagRedaction}).</p>
 */
final class TeamsDomShapeSurvey {

    /** Catégorie des événements de forme du DOM dans le Journal du runner (F-132). */
    static final String CATEGORY = "shape";
    static final String LIST_CODE = "conversations_list";
    static final String THREAD_CODE = "thread";
    /** SF-89-17 : la forme du rail de gauche (barre d'icônes d'app), zone de layout distincte du volet main. */
    static final String RAIL_CODE = "chat_rail";
    /**
     * SF-89-18 : la <b>liste des chats</b> (les conversations) relevée dans un cadre same-origin — distincte
     * du {@link #RAIL_CODE} (barre d'icônes d'app) qui la captait à tort. C'est elle qui portait le
     * <i>0 conversation</i>.
     */
    static final String CHAT_LIST_CODE = "chat_list";
    /**
     * SF-89-19 : la <b>forme d'une zone de layout</b> du document principal
     * ({@code [data-tid^="app-layout-area--"]} : main, sidebar, rail, header…). SF-89-16→18 ne relevaient
     * que {@code --main} ; c'est dans une <b>autre</b> zone (panneau de gauche) que vit la liste des chats.
     * {@code fields.area} porte le {@code data-tid} de la zone (ex. {@code app-layout-area--sidebar}).
     */
    static final String LAYOUT_CODE = "layout_area";
    /**
     * SF-89-17 : un cadre iframe inaccessible — <b>SF-89-18</b> le recentre sur les iframes réellement
     * cross-origin ({@code contentDocument} nul). Dit, jamais tu.
     */
    static final String FRAME_BLOCKED_CODE = "frame_blocked";

    /** L'étiquette du document principal, opposée au label d'un cadre intégré (SF-89-17). */
    static final String MAIN = "main";
    static final String RAIL_AREA = "rail";
    static final String RUNWAY_AREA = "message_runway";
    /** SF-89-18 : la zone de la liste des chats à l'intérieur d'un cadre same-origin. */
    static final String LIST_AREA = "chat_list";

    /** On ne relance pas le relevé plus d'une fois par minute, même à {@code DEBUG} soutenu. */
    static final long MIN_INTERVAL_MS = 60_000L;

    private static final AtomicLong LAST_RUN_MILLIS = new AtomicLong(0);

    private final PageActions actions;
    private final BrowserLink.Sleeper sleeper;
    private final ObjectMapper mapper = new ObjectMapper();

    TeamsDomShapeSurvey(PageActions actions, BrowserLink.Sleeper sleeper) {
        this.actions = actions;
        this.sleeper = sleeper;
    }

    /**
     * Lance le relevé <b>si</b> le poste est en {@code DEBUG} et que l'anti-rafale le permet. Construit
     * la liaison de page seulement dans ce cas. Ne lève jamais.
     */
    static void run(BrowserLink link, BrowserLink.Sleeper sleeper,
            Consumer<PageActions.GestureRecord> journal) {
        if (RunnerDiag.level() != RunnerDiagLevel.DEBUG) {
            return;
        }
        run(new PageActions(link, sleeper, journal), sleeper);
    }

    /** Même chose à partir d'une liaison de page déjà construite (couture de test). Ne lève jamais. */
    static void run(PageActions actions, BrowserLink.Sleeper sleeper) {
        try {
            if (RunnerDiag.level() != RunnerDiagLevel.DEBUG) {
                return;
            }
            long now = System.currentTimeMillis();
            long last = LAST_RUN_MILLIS.get();
            if (last != 0 && now - last < MIN_INTERVAL_MS) {
                return;
            }
            LAST_RUN_MILLIS.set(now);
            new TeamsDomShapeSurvey(actions, sleeper).survey();
        } catch (RuntimeException ignored) {
            // Le diagnostic ne casse jamais le runner : on avale tout (même esprit que RunnerDiag).
        }
    }

    /** Oublie l'anti-rafale (tests, et à l'arrêt du runner). */
    static void resetThrottle() {
        LAST_RUN_MILLIS.set(0);
    }

    /**
     * Le relevé : vue liste, puis vue fil, puis remise de la vue. Chaque étape est isolée — une étape
     * qui échoue (garde F-108, page inattendue) est <b>dite</b> par un en-tête {@code found=false} et
     * n'empêche pas les autres. Ne lève jamais.
     */
    void survey() {
        String before = safeCurrentUrl();
        // Document principal — la coquille (SF-89-16), désormais aussi le rail de gauche (SF-89-17).
        TeamsDomShape.Survey mainList = captureAt(conversationsRoute(before));
        emit(LIST_CODE, mainList, true, MAIN, null);
        emit(RAIL_CODE, capture(TeamsDomShape.RAIL_SELECTORS), true, MAIN, RAIL_AREA);
        // SF-89-19 : TOUTES les zones de layout du document principal (dont --sidebar), et la liste des
        // chats qui y vit — SF-89-16→18 ne relevaient que --main, où la liste des conversations n'est pas.
        surveyLayoutAreas();
        boolean opened = openFirstThread();
        emit(THREAD_CODE, capture(TeamsDomShape.ROOT_SELECTORS), opened, MAIN, null);
        emit(THREAD_CODE, capture(TeamsDomShape.RUNWAY_SELECTORS), opened, MAIN, RUNWAY_AREA);
        // Les cadres intégrés cross-origin — chemin d'attache CDP (SF-89-17), conservé pour les vrais OOPIF.
        surveyFrames(mainList);
        // Les iframes SAME-ORIGIN (dont hwc-iframe) — par leur contentDocument (SF-89-18) : c'est là que
        // vit vraiment la liste des conversations v2, invisible à l'auto-attache car pas une cible séparée.
        surveySameOriginFrames();
        restore(before);
    }

    /**
     * <b>Relève toutes les zones de layout du document principal</b> (SF-89-19) : exécute <b>un</b> script
     * dans l'onglet ({@link PageActions#readScript}, {@code Runtime.evaluate} déjà en liste blanche — aucune
     * nouvelle commande CDP) qui énumère {@code [data-tid^="app-layout-area--"]} et, pour chaque zone, relève
     * sa forme (émise sous {@link #LAYOUT_CODE}, {@code fields.area} = le {@code data-tid} de la zone) et la
     * <b>liste des chats</b> qui y vit (émise sous {@link #CHAT_LIST_CODE} avec la même {@code area}, si
     * trouvée). C'est dans une zone autre que {@code --main} (le panneau de gauche) que vit la liste des
     * conversations v2, jamais captée par SF-89-16→18. Ne lève jamais.
     */
    private void surveyLayoutAreas() {
        java.util.List<TeamsDomShape.AreaShape> areas;
        try {
            JsonNode raw = actions.readScript(TeamsDomShape.areasScript(mapper));
            areas = TeamsDomShape.refilterAreas(raw);
        } catch (RuntimeException e) {
            // Page sortie du domaine (garde F-108) ou lecture refusée : le document principal a déjà été relevé.
            return;
        }
        for (TeamsDomShape.AreaShape area : areas) {
            emit(LAYOUT_CODE, area.shape(), true, MAIN, area.area());
            // La liste des chats n'est émise que si une zone la porte : on ne noie pas le Journal de vues vides.
            if (area.list().found() || !area.list().nodes().isEmpty()) {
                emit(CHAT_LIST_CODE, area.list(), true, MAIN, area.area());
            }
        }
    }

    /**
     * <b>Entre dans les iframes same-origin</b> (SF-89-18) : exécute <b>un</b> script dans l'onglet
     * ({@link PageActions#readScript}, {@code Runtime.evaluate} déjà en liste blanche — aucune nouvelle
     * commande CDP) qui descend dans {@code iframe.contentDocument} de chaque cadre same-origin et relève sa
     * forme (racine large, <b>liste des chats</b>, rail, runway). Un cadre <b>cross-origin</b>
     * ({@code contentDocument} nul) est <b>dit</b> par {@link #FRAME_BLOCKED_CODE}, jamais un crash — et
     * aucune URL du cadre n'est lue. Ne lève jamais.
     */
    private void surveySameOriginFrames() {
        java.util.List<TeamsDomShape.FrameShape> frames;
        try {
            JsonNode raw = actions.readScript(TeamsDomShape.framesScript(mapper));
            frames = TeamsDomShape.refilterFrames(raw);
        } catch (RuntimeException e) {
            // Page sortie du domaine (garde F-108) ou lecture refusée : le document principal a déjà été relevé.
            return;
        }
        for (TeamsDomShape.FrameShape frame : frames) {
            if (frame.blocked()) {
                emitBlockedFrame(frame.label());
                continue;
            }
            emit(LIST_CODE, frame.root(), true, frame.label(), null);
            emit(CHAT_LIST_CODE, frame.list(), true, frame.label(), LIST_AREA);
            emit(RAIL_CODE, frame.rail(), true, frame.label(), RAIL_AREA);
            emit(THREAD_CODE, frame.runway(), true, frame.label(), RUNWAY_AREA);
        }
    }

    /**
     * Dit qu'un cadre iframe est <b>cross-origin</b> (son {@code contentDocument} était nul) — avec son seul
     * label. <b>Aucune URL du cadre n'est lue</b> : on ne franchit pas la barrière d'origine (SF-89-18, D4).
     */
    private void emitBlockedFrame(String label) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("kind", "frame_blocked");
        fields.put("frame", label);
        RunnerDiag.info(CATEGORY, FRAME_BLOCKED_CODE,
                "cadre iframe cross-origin (contentDocument nul) : " + label, fields);
    }

    /** Navigue vers la vue Conversations puis relève la forme de la racine large. */
    private TeamsDomShape.Survey captureAt(String route) {
        try {
            actions.navigate(route);
            settle();
        } catch (BrowserLinkException ignored) {
            // Destination refusée par la garde F-108 : on relève ce qui est à l'écran, sans naviguer.
        }
        return capture(TeamsDomShape.ROOT_SELECTORS);
    }

    /** Relève la forme des racines données sur la page courante. Rend une squelette vide si refusé. */
    private TeamsDomShape.Survey capture(java.util.List<String> roots) {
        try {
            JsonNode raw = actions.readScript(TeamsDomShape.surveyScript(mapper, roots));
            return TeamsDomShape.refilter(raw);
        } catch (BrowserLinkException e) {
            return new TeamsDomShape.Survey(false, false, java.util.List.of());
        }
    }

    /**
     * <b>Entre dans les cadres iframe</b> (SF-89-17, cause n°1) : passe l'onglet en auto-attache (mécanisme
     * F-100, aucune nouvelle commande CDP), puis relève la forme DANS chaque cadre iframe attaché sur un
     * domaine Microsoft — sa racine large, son rail, et le sous-arbre des messages. Un cadre inaccessible
     * (cross-origin non attachable, détaché) est <b>dit</b> par {@link #FRAME_BLOCKED_CODE}, jamais un crash.
     */
    private void surveyFrames(TeamsDomShape.Survey mainList) {
        java.util.List<PageActions.Frame> frames;
        try {
            frames = actions.attachedFrames();
        } catch (RuntimeException e) {
            return; // pas d'attache disponible : le document principal a déjà été relevé.
        }
        java.util.List<String> tids = TeamsDomShape.iframeTids(mainList);
        int i = 0;
        for (PageActions.Frame frame : frames) {
            if (i >= TeamsDomShape.MAX_FRAMES) {
                break;
            }
            String label = frameLabel(tids, i);
            i++;
            TeamsDomShape.Survey shape = captureInFrame(frame, TeamsDomShape.ROOT_SELECTORS);
            if (!shape.found() && shape.nodes().isEmpty()) {
                emitBlocked(label, frame);
                continue;
            }
            emit(LIST_CODE, shape, true, label, null);
            emit(RAIL_CODE, captureInFrame(frame, TeamsDomShape.RAIL_SELECTORS), true, label, RAIL_AREA);
            emit(THREAD_CODE, captureInFrame(frame, TeamsDomShape.RUNWAY_SELECTORS), true, label,
                    RUNWAY_AREA);
        }
    }

    /** Relève la forme des racines données DANS la session d'un cadre. Vide (inaccessible) si refusé. */
    private TeamsDomShape.Survey captureInFrame(PageActions.Frame frame, java.util.List<String> roots) {
        try {
            JsonNode raw = actions.readScriptInFrame(frame.sessionId(),
                    TeamsDomShape.surveyScript(mapper, roots));
            return TeamsDomShape.refilter(raw);
        } catch (BrowserLinkException e) {
            return new TeamsDomShape.Survey(false, false, java.util.List.of());
        }
    }

    /** Le label d'un cadre : le tid de la n<sup>e</sup> iframe du document, sinon {@code iframe#N}. */
    private static String frameLabel(java.util.List<String> tids, int index) {
        if (index < tids.size() && tids.get(index) != null && !tids.get(index).isEmpty()) {
            return tids.get(index);
        }
        return "iframe#" + (index + 1);
    }

    /** Dit qu'un cadre iframe attaché n'a pas pu être relevé — avec son label et son motif d'hôte. */
    private void emitBlocked(String label, PageActions.Frame frame) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("kind", "frame_blocked");
        fields.put("frame", label);
        fields.put("host", SurveyPaths.hostMotif(frame.url()));
        RunnerDiag.info(CATEGORY, FRAME_BLOCKED_CODE,
                "cadre inaccessible (cross-origin/detache) : " + label, fields);
    }

    /** Clique le premier fil plausible (sélecteurs larges) ; vrai si un fil a été ouvert. */
    private boolean openFirstThread() {
        try {
            for (String selector : TeamsDomShape.THREAD_OPENERS) {
                if (actions.click(selector)) {
                    settle();
                    return true;
                }
            }
        } catch (BrowserLinkException ignored) {
            // Garde F-108 : pas d'ouverture, la forme de la liste reste relevée.
        }
        return false;
    }

    /** La route Conversations reportée sur l'hôte Teams de l'onglet (même discipline que le repli écran). */
    private static String conversationsRoute(String tabUrl) {
        return TeamsRoutes.onTabHost(TeamsRoutes.CONVERSATIONS, tabUrl);
    }

    /**
     * Émet une vue relevée dans le Journal (F-132), <b>étiquetée</b> par son cadre ({@code main} ou le label
     * d'un cadre iframe) et, le cas échéant, sa zone ({@code rail}/{@code message_runway}) — SF-89-17. Ces
     * étiquettes sont sur l'en-tête <b>et</b> sur chaque nœud, pour se filtrer en base.
     */
    private void emit(String code, TeamsDomShape.Survey survey, boolean opened, String frame, String area) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("kind", "header");
        header.put("frame", frame);
        if (area != null) {
            header.put("area", area);
        }
        header.put("found", survey.found());
        header.put("nodes", survey.nodes().size());
        header.put("depth", survey.maxDepth());
        header.put("truncated", survey.truncated());
        header.put("opened", opened);
        // Émis en INFO : produit uniquement quand le relevé tourne (gardé DEBUG), donc visible dans le
        // panneau par défaut et robuste à un retour de niveau en cours de rafale.
        RunnerDiag.info(CATEGORY, code, "releve DOM " + code + " [" + frame
                + (area == null ? "" : "/" + area) + "] : " + survey.nodes().size()
                + " noeud(s), profondeur " + survey.maxDepth() + (survey.truncated() ? ", tronque" : "")
                + (opened ? "" : " (aucun fil ouvert)"), header);
        int seq = 1;
        for (TeamsDomShape.Node node : survey.nodes()) {
            Map<String, Object> fields = TeamsDomShape.fields(node, seq);
            fields.put("frame", frame);
            if (area != null) {
                fields.put("area", area);
            }
            RunnerDiag.info(CATEGORY, code, TeamsDomShape.line(node), fields);
            seq++;
        }
    }

    private String safeCurrentUrl() {
        try {
            return actions.currentUrl();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private void restore(String before) {
        try {
            actions.restore(before);
        } catch (RuntimeException ignored) {
            // La remise de la vue est un confort, pas une garantie : on ne casse rien si elle échoue.
        }
    }

    private void settle() {
        if (sleeper != null) {
            sleeper.sleep(BrowserLink.SCROLL_SETTLE_MS);
        }
    }
}
