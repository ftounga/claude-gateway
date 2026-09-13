package fr.claudegateway.radar.analysis;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Le <b>bloc de tri</b> (F-101 / SF-101-02) : {@value #MARQUEUR} suivi de {@code {"retenus": ["E1"]}}.
 *
 * <p>Tolérance de forme, jamais de fond : un libellé qui ne désigne aucun échange soumis rend le tri
 * <b>illisible</b> — un tri qui invente un échange n'a pas été compris, et le prendre au mot écarterait
 * ou retiendrait au hasard.</p>
 *
 * @param lisible  vrai si le bloc a été trouvé et compris
 * @param retained indices (à partir de 0) des échanges retenus ; vide si rien n'est retenu ou si illisible
 */
public record RadarTriageVerdict(boolean lisible, Set<Integer> retained) {

    /** La ligne qui ouvre le bloc. Immuable : elle est écrite dans la consigne. */
    public static final String MARQUEUR = "===TRI===";

    private static final Pattern LABEL = Pattern.compile("E([1-9][0-9]{0,3})");
    private static final RadarTriageVerdict ILLISIBLE = new RadarTriageVerdict(false, Set.of());

    public RadarTriageVerdict {
        retained = retained == null ? Set.of()
                : java.util.Collections.unmodifiableSortedSet(new TreeSet<>(retained));
    }

    public static RadarTriageVerdict illisible() {
        return ILLISIBLE;
    }

    /**
     * Lit la réponse d'un tri portant sur {@code offset + 1 … offset + count} échanges.
     *
     * @param response la réponse du modèle
     * @param first    numéro du premier échange soumis dans cet appel (1 pour le premier)
     * @param count    nombre d'échanges soumis dans cet appel
     */
    public static RadarTriageVerdict parse(String response, int first, int count) {
        JsonNode block = RadarOutputBlock.read(response, MARQUEUR).orElse(null);
        if (block == null) {
            return ILLISIBLE;
        }
        JsonNode labels = block.get("retenus");
        if (labels == null || !labels.isArray()) {
            return ILLISIBLE;
        }
        Set<Integer> retained = new TreeSet<>();
        for (JsonNode label : labels) {
            if (!label.isTextual()) {
                return ILLISIBLE;
            }
            Matcher m = LABEL.matcher(label.asText().strip());
            if (!m.matches()) {
                return ILLISIBLE;
            }
            int number = Integer.parseInt(m.group(1));
            if (number < first || number >= first + count) {
                return ILLISIBLE;
            }
            retained.add(number - 1);
        }
        return new RadarTriageVerdict(true, retained);
    }
}
