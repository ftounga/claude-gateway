package fr.claudegateway.jarvis.state;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * <b>La question d'état</b> (F-247 / SF-247-01, cadrage §7) : classification <b>déterministe</b> d'une parole adressée à
 * Jarvis — verbe ou objet d'état, clé de ticket ou de MR, « où en est », « version », « bloqué », « attend », « non
 * lus », « qu'a dit », « m'a dit ». Aucun appel modèle ; rien n'est gardé du texte.
 */
public final class JarvisStateQuestion {

    /** Une clé de ticket ({@code PROJ-123}), de MR ({@code !42}) ou de PR ({@code #17}). */
    private static final Pattern KEY = Pattern.compile("\\b[A-Z][A-Z0-9]{1,9}-\\d{1,6}\\b|(?<![\\w])[!#]\\d{1,6}\\b");

    /** Les marques d'une question d'état, sur le texte aplati (minuscules, sans accents). */
    private static final List<Pattern> MARKS = List.of(
            Pattern.compile("\\bou en (est|sont|etait|etaient)\\b"),
            Pattern.compile("\\bou ca en est\\b"),
            Pattern.compile("\\b(quel|quelle) (est )?(le |la )?(statut|etat|version)\\b"),
            Pattern.compile("\\b(statut|etat) (du|de la|des|de l)\\b"),
            Pattern.compile("\\bversion\\b"),
            Pattern.compile("\\bdeploye(e|s|es)?\\b|\\bdeploiement\\b|\\ben prod\\b"),
            Pattern.compile("\\bbloque(e|s|es)?\\b|\\bbloquant\\b"),
            Pattern.compile("\\b(qu est ce qui|qui|quoi) (m )?attend\\b|\\ben attente\\b|\\battentes?\\b"),
            Pattern.compile("\\bnon lus?\\b"),
            Pattern.compile("\\bqu a dit\\b|\\bm a dit\\b|\\bm a ecrit\\b|\\bqu a repondu\\b"),
            Pattern.compile("\\b(le |la |les )?(ticket|tickets|mr|merge request|pr|pull request|pipeline|pipelines|"
                    + "build|ci|jira|sprint)\\b"),
            Pattern.compile("\\bbesoins?\\b"),
            Pattern.compile("\\b(passe|passee|valide|validee|merge|mergee|fusionne|fusionnee)\\b\\s*\\?"));

    private JarvisStateQuestion() {
    }

    /** Vrai si la parole est une question d'état. {@code null} ou vide : faux. */
    public static boolean isStateQuestion(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (KEY.matcher(text).find()) {
            return true;
        }
        String flat = flatten(text);
        return MARKS.stream().anyMatch(p -> p.matcher(flat).find());
    }

    /** Minuscules, sans accents, apostrophes et ponctuation légère ramenées à des espaces. */
    static String flatten(String text) {
        String n = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("['’`\\-]", " ").replaceAll("\\s+", " ").strip();
    }
}
