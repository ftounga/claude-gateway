package fr.claudegateway.atelier;

import java.util.List;

/**
 * La réponse de l'utilisateur à <b>une</b> question d'un lot {@code demander} (F-164 / SF-164-01),
 * telle qu'elle est rendue au modèle une fois le tour repris.
 *
 * <p>Une réponse porte les options <b>cochées</b> ({@code selected}) et/ou une <b>réponse libre</b>
 * ({@code other}, l'option « autre » toujours acceptée). Au moins l'un des deux est présent — la
 * contrainte est portée par le DTO d'entrée.</p>
 *
 * @param header   rappel de l'intitulé de la question (peut être vide), pour un compte rendu lisible
 * @param selected libellés des options choisies (peut être vide si réponse libre seule)
 * @param other    réponse libre saisie par l'utilisateur (peut être vide)
 */
public record AtelierAnswerEntry(String header, List<String> selected, String other) {

    public AtelierAnswerEntry {
        selected = selected == null ? List.of() : List.copyOf(selected);
        header = header == null ? "" : header.trim();
        other = other == null ? "" : other.trim();
    }

    /** Vrai si la réponse ne porte ni choix ni texte libre — refusée en amont (400). */
    public boolean isEmpty() {
        return selected.isEmpty() && other.isBlank();
    }

    /**
     * Compose le <b>compte rendu</b> des réponses rendu au modèle comme résultat de l'outil
     * {@code demander}. Volontairement textuel et lisible : c'est ce sur quoi le modèle raisonne pour
     * décider de la suite (et, éventuellement, reposer une question).
     */
    public static String compose(List<AtelierAnswerEntry> answers) {
        if (answers == null || answers.isEmpty()) {
            return "L'utilisateur n'a fourni aucune réponse.";
        }
        StringBuilder out = new StringBuilder("Réponses de l'utilisateur :");
        int i = 1;
        for (AtelierAnswerEntry answer : answers) {
            out.append("\n").append(i++).append('.');
            if (!answer.header().isBlank()) {
                out.append(" [").append(answer.header()).append(']');
            }
            out.append(" → ");
            if (!answer.selected().isEmpty()) {
                out.append(String.join(", ", answer.selected()));
            }
            if (!answer.other().isBlank()) {
                if (!answer.selected().isEmpty()) {
                    out.append(" ; ");
                }
                out.append("(réponse libre) ").append(answer.other());
            }
            if (answer.selected().isEmpty() && answer.other().isBlank()) {
                out.append("(aucune réponse)");
            }
        }
        return out.toString();
    }
}
