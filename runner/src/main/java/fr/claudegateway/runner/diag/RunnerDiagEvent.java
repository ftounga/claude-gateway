package fr.claudegateway.runner.diag;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Un événement de diagnostic du runner (F-132 / SF-132-01) : une <b>forme</b> et un <b>état</b>,
 * jamais un contenu.
 *
 * <p>Immuable et déjà <b>expurgé</b> : il est construit via {@link RunnerDiag}, qui passe le message
 * et les champs par {@link RunnerDiagRedaction}. Aucun secret, aucune URL brute (seulement sa
 * classe), aucun chemin sensible, aucun contenu Teams ne doit s'y trouver — la confidentialité du
 * poste client/banque est un invariant (cadrage §4).</p>
 *
 * @param ts     l'instant d'observation (horloge du runner)
 * @param level  le niveau ({@link RunnerDiagLevel})
 * @param cat    la catégorie courte (ex. {@code chrome}, {@code teams}, {@code capture}, {@code vigie}, {@code error})
 * @param code   le code court de l'événement (ex. {@code chrome_state})
 * @param msg    un message court expurgé (peut être {@code null})
 * @param fields une petite carte de champs scalaires expurgés (jamais {@code null} ; peut être vide)
 */
public record RunnerDiagEvent(
        Instant ts,
        RunnerDiagLevel level,
        String cat,
        String code,
        String msg,
        Map<String, Object> fields) {

    public RunnerDiagEvent {
        fields = fields == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(fields));
    }
}
