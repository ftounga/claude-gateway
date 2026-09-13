package fr.claudegateway.radar.analysis;

import java.util.Set;

/**
 * Ce que le tri a rendu (F-101 / SF-101-02).
 *
 * @param lisible  vrai si <b>tous</b> les appels ont été compris
 * @param retained indices (à partir de 0) des échanges retenus ; vide si illisible
 * @param tokens   consommation de tous les appels faits, en passe « tri »
 * @param code     motif d'un tri non lisible ({@code TRIAGE_UNREADABLE}, {@code PROVIDER_UNAVAILABLE},
 *                 {@code PROVIDER_ERROR}) ; {@code null} si lisible
 */
public record RadarTriageResult(boolean lisible, Set<Integer> retained, RadarAnalysisTokens tokens, String code) {

    public static final String UNREADABLE = "TRIAGE_UNREADABLE";
    public static final String PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE";
    public static final String PROVIDER_ERROR = "PROVIDER_ERROR";

    public RadarTriageResult {
        retained = retained == null ? Set.of()
                : java.util.Collections.unmodifiableSortedSet(new java.util.TreeSet<>(retained));
        tokens = tokens == null ? RadarAnalysisTokens.NONE : tokens;
    }

    static RadarTriageResult failed(String code, RadarAnalysisTokens tokens) {
        return new RadarTriageResult(false, Set.of(), tokens, code);
    }
}
