package fr.claudegateway.runner.teams;

import java.util.List;

/**
 * Ce que vaut la lecture (F-87 / SF-87-01) : le verdict, <b>combien de champs attendus ont été
 * reconnus</b>, lesquels manquent, et la version observée du service.
 *
 * <p>C'est l'objet que rend la sonde de santé (SF-87-03), et c'est aussi celui que porte chaque
 * lecture : le produit doit s'apercevoir qu'il ne sait plus lire <b>avant</b> l'utilisateur.</p>
 *
 * @param verdict            tout reconnu, partiellement, rien
 * @param recognizedFields   nombre de champs attendus effectivement trouvés
 * @param expectedFields     nombre de champs attendus ; {@code 0} si la sonde n'a rien pu comparer
 * @param missingFields      noms des champs attendus et absents — la <b>seule</b> place où un nom de
 *                           champ Microsoft est écrit, parce que c'est ce qui permet de réparer
 * @param observedApiVersions versions d'interface observées dans les URL (« v1 », « beta »…)
 * @param detail             précision en français, ou {@code ""}
 */
public record TeamsHealth(TeamsHealthVerdict verdict, int recognizedFields, int expectedFields,
        List<String> missingFields, List<String> observedApiVersions, String detail) {

    public TeamsHealth {
        verdict = verdict == null ? TeamsHealthVerdict.NONE : verdict;
        recognizedFields = Math.max(0, recognizedFields);
        expectedFields = Math.max(0, expectedFields);
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        observedApiVersions = observedApiVersions == null ? List.of() : List.copyOf(observedApiVersions);
        detail = detail == null ? "" : detail.strip();
    }

    /** Santé d'une lecture nominale : tout ce qui était attendu a été trouvé. */
    public static TeamsHealth full(int expectedFields) {
        return new TeamsHealth(TeamsHealthVerdict.FULL, expectedFields, expectedFields, List.of(),
                List.of(), "");
    }

    /** Santé déduite du décompte : c'est la règle unique, pour que trois sondes ne divergent pas. */
    public static TeamsHealth of(int recognized, int expected, List<String> missing,
            List<String> versions, String detail) {
        TeamsHealthVerdict verdict;
        if (expected <= 0 || recognized <= 0) {
            verdict = TeamsHealthVerdict.NONE;
        } else if (recognized >= expected) {
            verdict = TeamsHealthVerdict.FULL;
        } else {
            verdict = TeamsHealthVerdict.PARTIAL;
        }
        return new TeamsHealth(verdict, recognized, expected, missing, versions, detail);
    }

    /** Phrase française complète, telle qu'elle est écrite à l'utilisateur. */
    public String describe() {
        StringBuilder text = new StringBuilder(verdict.sentence());
        if (expectedFields > 0) {
            text.append(' ').append(recognizedFields).append(" champs reconnus sur ")
                    .append(expectedFields).append('.');
        }
        if (!missingFields.isEmpty()) {
            text.append(" Non reconnus : ").append(String.join(", ", missingFields)).append('.');
        }
        if (!observedApiVersions.isEmpty()) {
            text.append(" Version observée : ").append(String.join(", ", observedApiVersions))
                    .append('.');
        }
        if (!detail.isEmpty()) {
            text.append(' ').append(detail);
        }
        return text.toString();
    }
}
