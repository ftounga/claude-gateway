package fr.claudegateway.jarvis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * <b>Le détail du rendu de Jarvis en 3D sur une journée</b> (F-236 / SF-236-06, cadrage §18) —
 * « r_manual=2;sec_high=640;fps_50_60=3;t_subject=4 ». Vocabulaire <b>fermé</b> de clés et des nombres : aucun
 * contenu ne peut y entrer. Pur et déterministe.
 */
public final class RenderCounts {

    /** Les clés admises : raisons du repli 2D, secondes par niveau, classe d'i/s p50, gestes, touchers, etc. */
    public static final Set<String> KEYS = Set.of(
            // pourquoi la session est restée en 2D (cadrage T10 / T11, §6.3)
            "r_no_webgl", "r_perf_caveat", "r_reduced_motion", "r_save_data", "r_sober", "r_manual",
            "r_context_lost", "r_low_perf",
            // secondes passées à chaque niveau de qualité
            "sec_high", "sec_medium", "sec_low",
            // classe de l'i/s p50 de la session (histogramme à 6 classes)
            "fps_0_20", "fps_20_30", "fps_30_40", "fps_40_50", "fps_50_60", "fps_60p",
            // gestes (tourner, pincer, molette, double toucher) et touchers par type de nœud
            "gestures", "t_client", "t_subject", "t_chantier", "t_step", "t_person", "t_core",
            // passages au grand cerveau, entrées jouées / sautées, bascules 3D → 2D en cours de session
            "brains", "entries", "entries_skipped", "to2d",
            // SF-236-26 — vues de l'itération 4 ouvertes : replays, présentation, cockpit externe, veilleur, activité
            "replays", "presentations", "cockpits", "nights", "activity");

    /** Au plus autant par clé et par déclaration (une session : 24 h de secondes au plus). */
    public static final int MAX_PER_REPORT = 86_400;

    private RenderCounts() {
    }

    /** Lit le détail ; une entrée illisible ou inconnue est ignorée. */
    public static Map<String, Integer> parse(String raw) {
        Map<String, Integer> out = new TreeMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).strip();
            try {
                int n = Integer.parseInt(part.substring(eq + 1).strip());
                if (KEYS.contains(key) && n >= 0) {
                    out.merge(key, n, Integer::sum);
                }
            } catch (NumberFormatException e) {
                // entrée illisible : ignorée
            }
        }
        return out;
    }

    /** Écrit le détail, trié (le vocabulaire fermé borne sa longueur). */
    public static String format(Map<String, Integer> counts) {
        StringBuilder out = new StringBuilder();
        for (var e : new TreeMap<>(counts).entrySet()) {
            if (!out.isEmpty()) {
                out.append(';');
            }
            out.append(e.getKey()).append('=').append(e.getValue());
        }
        return out.toString();
    }

    /**
     * Valide une déclaration de l'écran : clés du vocabulaire, nombres de 0 à {@link #MAX_PER_REPORT}.
     *
     * @throws IllegalArgumentException clé inconnue ou nombre hors bornes
     */
    public static Map<String, Integer> validate(Map<String, Integer> raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (var e : raw.entrySet()) {
            Integer n = e.getValue();
            if (e.getKey() == null || !KEYS.contains(e.getKey())) {
                throw new IllegalArgumentException("Compteur de rendu inconnu.");
            }
            if (n == null || n < 0 || n > MAX_PER_REPORT) {
                throw new IllegalArgumentException("Compteur de rendu hors bornes.");
            }
            out.put(e.getKey(), n);
        }
        return out;
    }
}
