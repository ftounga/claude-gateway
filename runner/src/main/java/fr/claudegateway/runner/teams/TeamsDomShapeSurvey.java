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
        emit(LIST_CODE, captureAt(conversationsRoute(before)), true);
        boolean opened = openFirstThread();
        emit(THREAD_CODE, capture(), opened);
        restore(before);
    }

    /** Navigue vers la vue Conversations puis relève la forme. */
    private TeamsDomShape.Survey captureAt(String route) {
        try {
            actions.navigate(route);
            settle();
        } catch (BrowserLinkException ignored) {
            // Destination refusée par la garde F-108 : on relève ce qui est à l'écran, sans naviguer.
        }
        return capture();
    }

    /** Relève la forme de la racine large sur la page courante. Rend une squelette vide si refusé. */
    private TeamsDomShape.Survey capture() {
        try {
            JsonNode raw = actions.readScript(TeamsDomShape.surveyScript(mapper, TeamsDomShape.ROOT_SELECTORS));
            return TeamsDomShape.refilter(raw);
        } catch (BrowserLinkException e) {
            return new TeamsDomShape.Survey(false, false, java.util.List.of());
        }
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

    private void emit(String code, TeamsDomShape.Survey survey, boolean opened) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("kind", "header");
        header.put("found", survey.found());
        header.put("nodes", survey.nodes().size());
        header.put("depth", survey.maxDepth());
        header.put("truncated", survey.truncated());
        header.put("opened", opened);
        // Émis en INFO : produit uniquement quand le relevé tourne (gardé DEBUG), donc visible dans le
        // panneau par défaut et robuste à un retour de niveau en cours de rafale.
        RunnerDiag.info(CATEGORY, code, "releve DOM " + code + " : " + survey.nodes().size()
                + " noeud(s), profondeur " + survey.maxDepth() + (survey.truncated() ? ", tronque" : "")
                + (opened ? "" : " (aucun fil ouvert)"), header);
        int seq = 1;
        for (TeamsDomShape.Node node : survey.nodes()) {
            RunnerDiag.info(CATEGORY, code, TeamsDomShape.line(node), TeamsDomShape.fields(node, seq));
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
