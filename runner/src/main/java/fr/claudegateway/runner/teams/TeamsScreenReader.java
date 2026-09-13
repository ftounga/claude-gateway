package fr.claudegateway.runner.teams;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * <b>Lire un écran qui défile</b> (F-89 / SF-89-06) : la boucle de lecture, de défilement et de
 * recollage sur une vue de {@link TeamsScreen}.
 *
 * <p>Même problème que {@link TeamsHarvester}, côté écran : la liste est <b>virtualisée</b> — seuls
 * les éléments visibles existent. La boucle lit, fusionne sans doublon, fait défiler d'un écran, et
 * nomme ce qu'elle n'a pas pu garantir : un écran qui n'apporte plus rien, deux écrans <b>sans
 * recouvrement</b> (un morceau a pu être sauté), le plafond de gestes. À la fin, la position de
 * défilement est <b>remise</b>.</p>
 *
 * <p>Tout passe par {@link PageActions#readScript} : la garde de domaine et d'identification de F-108
 * s'applique avant chaque script.</p>
 */
final class TeamsScreenReader {

    /** Gestes de défilement au plus pour un fil ou une transcription. */
    static final int MAX_GESTURES = BrowserLink.MAX_SCROLL_GESTURES;

    /** Deux gestes de suite sans rien de nouveau : on arrête et on le dit. */
    static final int IDLE_GESTURES = 2;

    private final PageActions actions;
    private final BrowserLink.Sleeper sleeper;
    private final ObjectMapper mapper = new ObjectMapper();

    TeamsScreenReader(PageActions actions, BrowserLink.Sleeper sleeper) {
        this.actions = actions;
        this.sleeper = sleeper;
    }

    /**
     * Ce qu'une lecture d'écran a rendu.
     *
     * @param found      la vue a été trouvée
     * @param items      les éléments fusionnés, dans l'ordre de première lecture
     * @param gaps       ce qui n'a pas pu être garanti
     * @param reachedEdge la liste ne défilait plus (début ou fin atteint)
     * @param capReached le plafond de gestes a été atteint
     */
    record Collected(boolean found, List<Map<String, String>> items, List<TeamsGap> gaps, boolean reachedEdge,
            boolean capReached) {

        Collected {
            items = List.copyOf(items);
            gaps = List.copyOf(gaps);
        }
    }

    /**
     * Lit une vue en faisant défiler.
     *
     * @param view        la vue de la table
     * @param up          vrai pour remonter (fil : vers le plus ancien), faux pour descendre (transcription)
     * @param maxGestures gestes au plus
     * @param covered     vrai quand ce qui est lu couvre la demande : on s'arrête
     * @param keyOf       l'identité d'un élément, pour recoller sans doublon
     */
    Collected collect(TeamsScreen.View view, boolean up, int maxGestures,
            Predicate<List<Map<String, String>>> covered, Function<Map<String, String>, String> keyOf) {
        List<TeamsGap> gaps = new ArrayList<>();
        double position = number(actions.readScript(TeamsScreen.positionScript(mapper, view)));
        TeamsScreen.Reading reading = read(view);
        if (!reading.found()) {
            gaps.add(changed(view, "structure attendue introuvable"));
            return new Collected(false, List.of(), gaps, false, false);
        }
        Map<String, Map<String, String>> merged = new LinkedHashMap<>();
        reading.items().forEach(item -> merged.putIfAbsent(keyOf.apply(item), item));
        boolean edge = false;
        boolean cap = false;
        int idle = 0;
        int gestures = Math.max(0, Math.min(maxGestures, MAX_GESTURES));
        for (int gesture = 0; ; gesture++) {
            if (covered.test(new ArrayList<>(merged.values()))) {
                break;
            }
            if (up ? reading.atStart() : reading.atEnd()) {
                edge = true;
                break;
            }
            if (gesture >= gestures) {
                cap = true;
                gaps.add(TeamsGap.of(TeamsGapKind.CAP_REACHED, view.name(), gestures + " défilements d'écran"));
                break;
            }
            JsonNode scrolled = actions.readScript(TeamsScreen.scrollScript(mapper, view, up));
            if (scrolled == null || !scrolled.path("moved").asBoolean(false)) {
                edge = true;
                break;
            }
            if (sleeper != null) {
                sleeper.sleep(BrowserLink.SCROLL_SETTLE_MS);
            }
            reading = read(view);
            if (!reading.found()) {
                gaps.add(changed(view, "la vue a disparu pendant la lecture"));
                break;
            }
            int fresh = 0;
            boolean overlap = false;
            for (Map<String, String> item : reading.items()) {
                String key = keyOf.apply(item);
                if (merged.containsKey(key)) {
                    overlap = true;
                } else {
                    merged.put(key, item);
                    fresh++;
                }
            }
            if (fresh > 0 && !overlap) {
                // Deux écrans qui ne se touchent pas : ce qui était entre eux n'a jamais été affiché.
                fold(gaps, TeamsGap.of(TeamsGapKind.PAGINATION_STOPPED, view.name(),
                        "deux écrans successifs sans recouvrement : un morceau a pu être sauté"));
            }
            idle = fresh == 0 ? idle + 1 : 0;
            if (idle >= IDLE_GESTURES) {
                gaps.add(TeamsGap.of(TeamsGapKind.PAGINATION_STOPPED, view.name(),
                        "l'écran a défilé sans plus rien afficher de nouveau"));
                break;
            }
        }
        if (position >= 0) {
            actions.readScript(TeamsScreen.restoreScript(mapper, view, position));
        }
        return new Collected(true, new ArrayList<>(merged.values()), gaps, edge, cap);
    }

    /**
     * L'état du téléchargement de la transcription : {@code null} si le panneau n'est pas à l'écran ;
     * vrai si le bouton est absent ou désactivé.
     */
    Boolean downloadBlocked() {
        JsonNode control = actions.readScript(TeamsScreen.downloadControlScript(mapper));
        if (control == null || !control.path("panel").asBoolean(false)) {
            return null;
        }
        return !control.path("present").asBoolean(false) || control.path("disabled").asBoolean(false);
    }

    /** Ouvre le panneau de transcription par un clic gardé ; vrai si un déclencheur a été cliqué. */
    boolean openTranscript() {
        for (String selector : TeamsScreen.TRANSCRIPT_OPENERS) {
            if (actions.click(selector)) {
                return true;
            }
        }
        return false;
    }

    private TeamsScreen.Reading read(TeamsScreen.View view) {
        return TeamsScreen.refilter(view, actions.readScript(TeamsScreen.readScript(mapper, view)));
    }

    static TeamsGap changed(TeamsScreen.View view, String why) {
        return TeamsGap.of(TeamsGapKind.SCREEN_CHANGED, view.name(), why + " — lecture d'écran " + TeamsScreen.VERSION);
    }

    private static double number(JsonNode value) {
        return value != null && value.isNumber() ? value.asDouble() : -1;
    }

    private static void fold(List<TeamsGap> gaps, TeamsGap gap) {
        for (int index = 0; index < gaps.size(); index++) {
            TeamsGap existing = gaps.get(index);
            if (existing.kind() == gap.kind() && existing.where().equals(gap.where())
                    && existing.detail().equals(gap.detail())) {
                gaps.set(index, new TeamsGap(existing.kind(), existing.where(), existing.detail(),
                        existing.count() + gap.count()));
                return;
            }
        }
        gaps.add(gap);
    }
}
